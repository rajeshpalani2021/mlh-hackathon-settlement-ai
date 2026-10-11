package com.settle.agent;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Smoke test: does the configured NVIDIA model make a real tool call through Spring AI? */
@RestController
public class LlmPingController {
    private final ChatClient chatClient;
    private final String model;

    public LlmPingController(ChatClient chatClient, @Value("${spring.ai.openai.chat.options.model}") String model) {
        this.chatClient = chatClient;
        this.model = model;
    }

    public static class PingTools {
        final AtomicBoolean called = new AtomicBoolean();

        @Tool(description = "Returns today's secret settlement number. The number cannot be known without calling this tool.")
        public int getSecretNumber() {
            called.set(true);
            return 4711;
        }
    }

    @GetMapping("/api/llm/ping")
    public Map<String, Object> ping() {
        PingTools tools = new PingTools();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", model);
        try {
            String answer = chatClient.prompt()
                    .user("What is today's secret settlement number? Use the tool, then answer with just the number.")
                    .tools(tools)
                    .call()
                    .content();
            out.put("toolCalled", tools.called.get());
            out.put("answer", answer);
            out.put("ok", tools.called.get() && answer != null && answer.contains("4711"));
        } catch (Exception e) {
            out.put("ok", false);
            out.put("error", e.getMessage());
        }
        return out;
    }
}
