package com.settle.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
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
    private static final int MAX_ITERATIONS = 8;

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
        factory.setReadTimeout(60_000);
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
            ObjectNode request = json.createObjectNode();
            request.put("model", model);
            request.put("temperature", temperature);
            request.put("max_tokens", maxTokens);
            if (!reasoningEffort.isBlank()) request.put("reasoning_effort", reasoningEffort);
            request.set("messages", messages);
            request.set("tools", toolDefs);

            long start = System.currentTimeMillis();
            JsonNode response = http.post().contentType(MediaType.APPLICATION_JSON).body(request)
                    .retrieve().body(JsonNode.class);
            log.info("LLM round {}: {} ms, finish_reason={}, usage={}", i + 1, System.currentTimeMillis() - start,
                    response == null ? null : response.path("choices").path(0).path("finish_reason").asText(),
                    response == null ? null : response.path("usage"));
            JsonNode message = response == null ? null : response.path("choices").path(0).path("message");
            if (message == null || message.isMissingNode()) {
                throw new IllegalStateException("LLM returned no message: " + response);
            }
            JsonNode toolCalls = message.path("tool_calls");
            if (!toolCalls.isArray() || toolCalls.isEmpty()) {
                return message.path("content").asText(null);
            }

            messages.add(message); // verbatim, keeps Gemini's thought_signature
            for (JsonNode call : toolCalls) {
                String name = call.path("function").path("name").asText();
                String args = call.path("function").path("arguments").asText("{}");
                ToolCallback tool = tools.get(name);
                String result;
                try {
                    result = tool == null ? "{\"error\":\"unknown tool " + name + "\"}" : tool.call(args.isBlank() ? "{}" : args);
                } catch (Exception e) {
                    result = "{\"error\":" + json.valueToTree(String.valueOf(e.getMessage())) + "}";
                }
                log.debug("tool {}({}) -> {} chars", name, args, result.length());
                messages.addObject().put("role", "tool").put("tool_call_id", call.path("id").asText())
                        .put("content", result);
            }
        }
        throw new IllegalStateException("LLM did not finish within " + MAX_ITERATIONS + " tool rounds");
    }

    private JsonNode readTree(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            return json.createObjectNode().put("type", "object");
        }
    }
}
