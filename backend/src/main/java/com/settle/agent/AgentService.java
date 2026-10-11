package com.settle.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.settle.config.SettleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Runs the investigator LLM with tools and parses its JSON answer. */
@Service
public class AgentService {
    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    static final String SYSTEM_PROMPT = """
            You are a settlement operations investigator for securities trades. For the given trade:
            (1) call getTradeHistory, (2) identify the failing message and its code (NAK 405 code or MT548 status/reason),
            (3) ALWAYS call wikiSearch for that code first - if a recipe matches, use it and put its id in recipeUsed,
            (4) inspect the original raw outbound message to find the exact field and value at fault,
            (5) for PENDING trades call getSettleStats and give an expected settle time.
            Propose at most ONE field change. Never invent codes or values; if unsure, lower confidence and say why.
            Respond ONLY with JSON matching the schema.

            Fixable fields (anything else: set fix to null):
            - field "95P", qualifier "PSET" (place of settlement BIC)
            - field "95P", qualifier "BUYR" or "SELL" (counterparty BIC)
            - field "36B", qualifier "SETT" (quantity; plain number like 500, no UNIT/ prefix or commas)
            oldValue must be exactly the current value in the trade/outbound message (BIC without slashes).

            JSON schema (no markdown, no prose outside the JSON):
            {
              "rootCause": "short one-line root cause",
              "explanation": "2-4 sentences: failing message, code, faulty field/value, evidence; for PENDING the expected settle time",
              "confidence": 0.0-1.0,
              "fix": {"field": "95P", "qualifier": "PSET", "oldValue": "...", "newValue": "..."} or null,
              "wikiPagesUsed": ["page ids you read or relied on"],
              "recipeUsed": "recipes/... id if a recipe matched, else null",
              "recipeDraft": "if no recipe was used and you propose a fix: short markdown body describing symptom, check and fix for future cases; else null"
            }
            """;

    private final ChatClient chatClient;
    private final SettleTools tools;
    private final ObjectMapper json;
    private final int timeoutSeconds;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public AgentService(ChatClient chatClient, SettleTools tools, ObjectMapper json, SettleProperties props) {
        this.chatClient = chatClient;
        this.tools = tools;
        this.json = json;
        this.timeoutSeconds = props.llmTimeoutSeconds();
    }

    /** Runs the agent for a trade; throws on LLM failure, timeout or unparseable output. */
    public InvestigationResult investigate(UUID investigationId, String tradeRef) throws Exception {
        Future<String> future = executor.submit(() -> {
            InvestigationContext.set(investigationId);
            try {
                return chatClient.prompt()
                        .system(SYSTEM_PROMPT)
                        .user("Investigate trade " + tradeRef + ". Return only the JSON object.")
                        .tools(tools)
                        .call()
                        .content();
            } finally {
                InvestigationContext.clear();
            }
        });
        String content;
        try {
            content = future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("LLM did not answer within " + timeoutSeconds + "s");
        }
        log.info("Agent answer for {}: {}", tradeRef, content);
        return json.readValue(extractJson(content), InvestigationResult.class);
    }

    /** Strips ```json fences / prose and returns the outermost {...}. */
    static String extractJson(String content) {
        if (content == null) throw new IllegalStateException("LLM returned no content");
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IllegalStateException("LLM answer is not JSON: " + abbreviate(content));
        return content.substring(start, end + 1);
    }

    private static String abbreviate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
