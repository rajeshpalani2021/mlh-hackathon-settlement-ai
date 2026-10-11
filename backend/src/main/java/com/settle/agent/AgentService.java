package com.settle.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.settle.config.SettleProperties;
import io.sentry.ITransaction;
import io.sentry.Sentry;
import io.sentry.SpanStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
            If you encounter a reason code or error code that is NOT in the wiki, list it in unknownCodes.
            The wiki may contain draft pages (type "draft") researched by a background job via Nimble.
            These drafts are unverified but can be used as evidence - note them in wikiPagesUsed.
            Be efficient: stop calling tools as soon as you have the evidence (usually 3-5 calls; at most 8).
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
              "recipeDraft": "if no recipe was used and you propose a fix: short markdown body describing symptom, check and fix for future cases; else null",
              "unknownCodes": ["reason or error codes not found in the wiki"]
            }
            """;

    private final LlmClient llm;
    private final SettleTools tools;
    private final ObjectMapper json;
    private final int timeoutSeconds;
    private final ExecutorService executor = Executors.newCachedThreadPool();

    public AgentService(LlmClient llm, SettleTools tools, ObjectMapper json, SettleProperties props) {
        this.llm = llm;
        this.tools = tools;
        this.json = json;
        this.timeoutSeconds = props.llmTimeoutSeconds();
    }

    /** Runs the agent for a trade; throws on LLM failure, timeout or unparseable output. */
    public InvestigationResult investigate(UUID investigationId, String tradeRef) throws Exception {
        ITransaction transaction = Sentry.startTransaction("agent.investigate", "llm");
        transaction.setData("investigationId", investigationId.toString());
        transaction.setData("tradeRef", tradeRef);

        Future<String> future = executor.submit(() -> {
            InvestigationContext.set(investigationId);
            try {
                return llm.chat(SYSTEM_PROMPT, "Investigate trade " + tradeRef + ". Return only the JSON object.", tools);
            } finally {
                InvestigationContext.clear();
            }
        });
        String content;
        try {
            content = future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            transaction.setStatus(SpanStatus.DEADLINE_EXCEEDED);
            transaction.setThrowable(e);
            Sentry.captureException(e);
            throw new IllegalStateException("LLM did not answer within " + timeoutSeconds + "s");
        } catch (Exception e) {
            transaction.setStatus(SpanStatus.INTERNAL_ERROR);
            transaction.setThrowable(e);
            Sentry.captureException(e);
            throw e;
        }
        log.info("Agent answer for {}: {}", tradeRef, content);

        InvestigationResult result;
        try {
            result = json.readValue(extractJson(content), InvestigationResult.class);
            transaction.setData("confidence", result.confidence());
            transaction.setData("rootCause", String.valueOf(result.rootCause())); // Sentry rejects null data
            transaction.setStatus(SpanStatus.OK);
        } catch (Exception e) {
            transaction.setStatus(SpanStatus.INTERNAL_ERROR);
            transaction.setThrowable(e);
            Sentry.captureException(e);
            throw e;
        } finally {
            transaction.finish();
        }
        return result;
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
