package com.settle.agent;

import com.settle.config.ReadOnlyDb;
import com.settle.swift.MessageRepository;
import com.settle.trade.TradeRepository;
import com.settle.wiki.WikiService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Tools the investigator LLM can call. Every call is logged as a step on the current investigation. */
@Component
public class SettleTools {
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(insert|update|delete|merge|drop|alter|create|truncate|grant|revoke|copy|call|do|execute|vacuum|"
                    + "refresh|set|reset|lock|comment|listen|notify|prepare|pg_sleep|pg_terminate_backend|dblink)\\b",
            Pattern.CASE_INSENSITIVE);

    private final TradeRepository trades;
    private final MessageRepository messages;
    private final WikiService wiki;
    private final JdbcTemplate jdbc;
    private final ReadOnlyDb readOnlyDb;
    private final InvestigationRepository investigations;

    public SettleTools(TradeRepository trades, MessageRepository messages, WikiService wiki, JdbcTemplate jdbc,
                       ReadOnlyDb readOnlyDb, InvestigationRepository investigations) {
        this.trades = trades;
        this.messages = messages;
        this.wiki = wiki;
        this.jdbc = jdbc;
        this.readOnlyDb = readOnlyDb;
        this.investigations = investigations;
    }

    @Tool(description = "Get the stored trade record (side, ISIN, quantity, price, dates, counterparty BIC, "
            + "agent id, PSET BIC, safekeeping account, current lifecycle status) for a trade reference.")
    public Object getTrade(@ToolParam(description = "Trade reference, e.g. HCK541000000002") String tradeRef) {
        Object result = trades.find(tradeRef).<Object>map(TradeRepository::toMap)
                .orElse(Map.of("error", "No trade " + tradeRef));
        step("getTrade", Map.of("tradeRef", tradeRef), result instanceof Map<?, ?> m && m.containsKey("error")
                ? "not found" : "trade found");
        return result;
    }

    @Tool(description = "Get the full SWIFT history of a trade, oldest first: every outbound instruction (MT541/MT543) "
            + "and inbound reply (ACK, NAK, MT548) with raw_text, parsed fields, status_code and reason_code, plus "
            + "lifecycle events (SENT, ACK, NAK, MATCHED, UNMATCHED, PENDING, SETTLED, FIXED). Call this first.")
    public Object getTradeHistory(@ToolParam(description = "Trade reference") String tradeRef) {
        List<Map<String, Object>> msgs = messages.messages(tradeRef);
        List<Map<String, Object>> events = messages.events(tradeRef);
        msgs.forEach(m -> m.remove("parsed")); // raw text is enough for the model and keeps the prompt small
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tradeRef", tradeRef);
        result.put("messages", msgs);
        result.put("events", events);
        step("getTradeHistory", Map.of("tradeRef", tradeRef),
                msgs.size() + " messages, " + events.size() + " events; last=" +
                        (events.isEmpty() ? "none" : events.get(events.size() - 1).get("eventType")));
        return result;
    }

    @Tool(description = "Search the settlement knowledge wiki by keywords (error codes like T27, reason codes like "
            + "LACK or DQUA, field tags like 95P PSET). Returns up to 5 hits {id, type, title, snippet}. Pages of "
            + "type 'recipe' are VERIFIED PAST FIXES for the same error and are ranked first - always check them first "
            + "and, if one matches, follow it and report its id as recipeUsed.")
    public Object wikiSearch(@ToolParam(description = "Keywords, e.g. 'NAK T27 95P PSET'") String query) {
        List<WikiService.Hit> hits = wiki.search(query);
        step("wikiSearch", Map.of("query", query), hits.size() + " hits: "
                + String.join(", ", hits.stream().map(WikiService.Hit::id).toList()));
        return hits;
    }

    @Tool(description = "Read the full markdown of a wiki page by its id (e.g. 'codes/nak-T27' or 'recipes/T27-95P-PSET').")
    public String wikiRead(@ToolParam(description = "Page id from wikiSearch") String pageId) {
        String md = wiki.read(pageId);
        step("wikiRead", Map.of("pageId", pageId), md == null ? "not found" : "read " + md.length() + " chars");
        return md == null ? "No wiki page with id " + pageId : md;
    }

    @Tool(description = "Historical settlement statistics from the last 60 days: average minutes a trade stayed "
            + "pending before settling, for a counterparty BIC and pending reason code (e.g. LACK). Also returns the "
            + "average across all counterparties for that reason for comparison. Use it to estimate when a PENDING trade will settle.")
    public Object getSettleStats(@ToolParam(description = "Counterparty BIC, e.g. BRKBUS33XXX") String counterpartyBic,
                                 @ToolParam(description = "Reason code, e.g. LACK", required = false) String reasonCode) {
        String reason = reasonCode == null || reasonCode.isBlank() || reasonCode.equalsIgnoreCase("null") ? null : reasonCode;
        String sql = """
                SELECT round(sum(avg_minutes * samples) / nullif(sum(samples), 0), 1) AS avg_minutes,
                       coalesce(sum(samples), 0) AS samples
                FROM settle_stats
                WHERE bucket > now() - interval '60 days' AND reason_code IS NOT DISTINCT FROM ?
                """;
        Map<String, Object> cpty = jdbc.queryForMap(sql + " AND counterparty_bic = ?", reason, counterpartyBic);
        Map<String, Object> all = jdbc.queryForMap(sql, reason);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("counterpartyBic", counterpartyBic);
        result.put("reasonCode", reason);
        result.put("avgMinutesPending", cpty.get("avg_minutes"));
        result.put("samples", cpty.get("samples"));
        result.put("allCounterpartiesAvgMinutes", all.get("avg_minutes"));
        result.put("allCounterpartiesSamples", all.get("samples"));
        step("getSettleStats", mapOf("counterpartyBic", counterpartyBic, "reasonCode", reason),
                "avg " + cpty.get("avg_minutes") + " min over " + cpty.get("samples") + " samples (all: "
                        + all.get("avg_minutes") + " min)");
        return result;
    }

    @Tool(description = "Run ONE read-only SQL SELECT against the settlement database (tables: trade, swift_message, "
            + "trade_event, investigation; continuous aggregate settle_stats). No semicolons, no DDL/DML. "
            + "At most 50 rows are returned; 5 second timeout. Prefer the other tools; use this only if they are not enough.")
    public Object querySource(@ToolParam(description = "A single SELECT statement") String sql) {
        String error = validateSelect(sql);
        if (error != null) {
            step("querySource", Map.of("sql", String.valueOf(sql)), "rejected: " + error);
            return Map.of("error", error);
        }
        String q = sql.strip();
        if (!Pattern.compile("\\blimit\\s+\\d+", Pattern.CASE_INSENSITIVE).matcher(q).find()) q = q + " LIMIT 50";
        try {
            List<Map<String, Object>> rows = readOnlyDb.query(q);
            List<Map<String, Object>> capped = rows.size() > 50 ? rows.subList(0, 50) : rows;
            step("querySource", Map.of("sql", q), capped.size() + " rows");
            return capped;
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            String msg = root.getMessage();
            step("querySource", Map.of("sql", q), "error: " + msg);
            return Map.of("error", String.valueOf(msg));
        }
    }

    /** Returns null if sql is a single plain SELECT, otherwise the reason it is rejected. */
    static String validateSelect(String sql) {
        if (sql == null || sql.isBlank()) return "empty query";
        String s = sql.strip();
        if (s.contains(";")) return "semicolons are not allowed (single statement only)";
        if (!s.toLowerCase(Locale.ROOT).startsWith("select")) return "only SELECT statements are allowed";
        if (FORBIDDEN.matcher(s).find()) return "query contains a forbidden keyword";
        return null;
    }

    private void step(String tool, Map<String, ?> args, String summary) {
        UUID id = InvestigationContext.get();
        if (id != null) investigations.appendStep(id, tool, args, summary);
    }

    private static Map<String, Object> mapOf(String k1, Object v1, String k2, Object v2) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k1, v1);
        m.put(k2, v2);
        return m;
    }
}
