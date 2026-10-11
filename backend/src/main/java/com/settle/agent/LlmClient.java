package com.settle.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.sentry.ITransaction;
import io.sentry.Sentry;
import io.sentry.SpanStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Minimal tool-calling loop against an OpenAI-compatible /chat/completions endpoint (Gemini).
 * Used instead of Spring AI's ChatClient because Gemini 3 requires each assistant tool call to be sent back
 * with its thought_signature (extra_content), which Spring AI drops. Here the assistant message is echoed
 * back verbatim. Tools are still the Spring AI @Tool methods, exposed through ToolCallbacks.
 */
@Component
public class LlmClient {
    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    private static final int MAX_ITERATIONS = 10;

    private final RestClient http;
    private final ObjectMapper json;
    private final String model;
    private final double temperature;
    private final int maxTokens;
    private final String reasoningEffort;

    public LlmClient(ObjectMapper json,
                     @Value("${spring.ai.openai.base-url}") String baseUrl,
                     @Value("${spring.ai.openai.chat.completions-path}") String completionsPath,
                     @Value("${spring.ai.openai.api-key}") String apiKey,
                     @Value("${spring.ai.openai.chat.options.model}") String model,
                     @Value("${spring.ai.openai.chat.options.temperature:0.2}") double temperature,
                     @Value("${spring.ai.openai.chat.options.max-tokens:8192}") int maxTokens,
                     @Value("${settle.llm-reasoning-effort:low}") String reasoningEffort) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(120_000); // Gemma's final answer round can take 50s+
        this.http = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(baseUrl + completionsPath)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
        this.json = json;
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.reasoningEffort = reasoningEffort;
    }

    public String model() {
        return model;
    }

    /** Runs system + user prompt, executing tool calls on toolObject's @Tool methods, and returns the final text. */
    public String chat(String system, String user, Object toolObject) {
        Map<String, ToolCallback> tools = Arrays.stream(ToolCallbacks.from(toolObject))
                .collect(Collectors.toMap(t -> t.getToolDefinition().name(), Function.identity()));

        ArrayNode messages = json.createArrayNode();
        if (system != null) messages.addObject().put("role", "system").put("content", system);
        messages.addObject().put("role", "user").put("content", user);

        ArrayNode toolDefs = json.createArrayNode();
        for (ToolCallback t : tools.values()) {
            ObjectNode fn = toolDefs.addObject().put("type", "function").putObject("function");
            fn.put("name", t.getToolDefinition().name());
            fn.put("description", t.getToolDefinition().description());
            fn.set("parameters", readTree(t.getToolDefinition().inputSchema()));
        }

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            if (i == MAX_ITERATIONS - 1) {
                messages.addObject().put("role", "user")
                        .put("content", "Tool budget used up. Do not call any more tools. Answer now with the final JSON.");
            }
            ObjectNode request = json.createObjectNode();
            request.put("model", model);
            request.put("temperature", temperature);
            request.put("max_tokens", maxTokens);
            // Gemma models reject reasoning_effort ("Thinking level is not supported for this model")
            if (!reasoningEffort.isBlank() && !model.startsWith("gemma")) request.put("reasoning_effort", reasoningEffort);
            request.set("messages", messages);
            request.set("tools", toolDefs);
            if (i == MAX_ITERATIONS - 1) {
                // last round: no more tools, the model must answer with what it has gathered
                request.put("tool_choice", "none");
            }

            ITransaction llmSpan = Sentry.startTransaction("llm.round", "llm");
            llmSpan.setData("model", model);
            llmSpan.setData("round", i + 1);
            llmSpan.setData("temperature", temperature);
            llmSpan.setData("maxTokens", maxTokens);

            long start = System.currentTimeMillis();
            JsonNode response;
            try {
                response = post(request);
            } catch (Exception e) {
                llmSpan.setStatus(SpanStatus.INTERNAL_ERROR);
                llmSpan.setThrowable(e);
                llmSpan.finish();
                Sentry.captureException(e);
                throw e;
            }
            long latencyMs = System.currentTimeMillis() - start;
            log.info("LLM round {}: {} ms, finish_reason={}, usage={}", i + 1, latencyMs,
                    response == null ? null : response.path("choices").path(0).path("finish_reason").asText(),
                    response == null ? null : response.path("usage"));

            JsonNode message = response == null ? null : response.path("choices").path(0).path("message");
            if (message == null || message.isMissingNode()) {
                llmSpan.setStatus(SpanStatus.INTERNAL_ERROR);
                llmSpan.finish();
                throw new IllegalStateException("LLM returned no message: " + response);
            }
            JsonNode toolCalls = message.path("tool_calls");
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                JsonNode usage = response.path("usage");
                if (usage != null) {
                    llmSpan.setData("promptTokens", usage.path("prompt_tokens").asInt(0));
                    llmSpan.setData("completionTokens", usage.path("completion_tokens").asInt(0));
                    llmSpan.setData("totalTokens", usage.path("total_tokens").asInt(0));
                }
                // finish_reason is on the choice, not the message; Sentry span data rejects null values
                llmSpan.setData("finishReason", response.path("choices").path(0).path("finish_reason").asText(""));
                llmSpan.setStatus(SpanStatus.OK);
                llmSpan.finish();
                String content = message.path("content").asText(null);
                // Gemma returns its reasoning inline as <thought>...</thought>; keep only the answer
                return content == null ? null : content.replaceAll("(?s)<thought>.*?</thought>", "").strip();
            }

            llmSpan.setData("toolCalls", toolCalls.size());
            llmSpan.setStatus(SpanStatus.OK);
            llmSpan.finish();

            messages.add(message); // verbatim, keeps Gemini's thought_signature
            for (JsonNode call : toolCalls) {
                String name = call.path("function").path("name").asText();
                String args = call.path("function").path("arguments").asText("{}");
                ToolCallback tool = tools.get(name);

                ITransaction toolSpan = Sentry.startTransaction("tool." + name, "tool");
                toolSpan.setData("toolName", name);
                toolSpan.setData("argsLength", args.length());

                String result;
                try {
                    result = tool == null ? "{\"error\":\"unknown tool " + name + "\"}" : tool.call(args.isBlank() ? "{}" : args);
                    toolSpan.setData("resultLength", result.length());
                    toolSpan.setStatus(SpanStatus.OK);
                } catch (Exception e) {
                    result = "{\"error\":" + json.valueToTree(String.valueOf(e.getMessage())) + "}";
                    toolSpan.setStatus(SpanStatus.INTERNAL_ERROR);
                    toolSpan.setThrowable(e);
                    Sentry.captureException(e);
                } finally {
                    toolSpan.finish();
                }
                log.debug("tool {}({}) -> {} chars", name, args, result.length());
                messages.addObject().put("role", "tool").put("tool_call_id", call.path("id").asText())
                        .put("content", result);
            }
        }
        throw new IllegalStateException("LLM did not finish within " + MAX_ITERATIONS + " tool rounds");
    }

    /** POST with up to 4 retries (2s, 4s, 8s, 16s) on transient 5xx errors, e.g. Gemini 503 "high demand". */
    private JsonNode post(ObjectNode request) {
        for (int attempt = 1; ; attempt++) {
            try {
                return http.post().contentType(MediaType.APPLICATION_JSON).body(request).retrieve().body(JsonNode.class);
            } catch (HttpServerErrorException e) {
                if (attempt >= 5) throw e;
                log.warn("LLM HTTP {} (attempt {}), retrying", e.getStatusCode().value(), attempt);
                try {
                    Thread.sleep(1000L << attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private JsonNode readTree(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            return json.createObjectNode().put("type", "object");
        }
    }
}
