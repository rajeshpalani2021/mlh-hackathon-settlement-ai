package com.settle.agent;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Smoke test: does the configured model make a real tool call? */
@RestController
public class LlmPingController {
    private final LlmClient llm;

    public LlmPingController(LlmClient llm) {
        this.llm = llm;
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
        out.put("model", llm.model());
        try {
            String answer = llm.chat(null,
                    "What is today's secret settlement number? Use the tool, then answer with just the number.", tools);
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
