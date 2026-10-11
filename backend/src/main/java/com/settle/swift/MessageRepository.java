package com.settle.swift;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** swift_message + trade_event access. */
@Repository
public class MessageRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public MessageRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void insertMessage(String tradeRef, String direction, String msgType, String raw, Object parsed,
                              String statusCode, String reasonCode) {
        jdbc.update("""
                INSERT INTO swift_message (event_ts, trade_ref, direction, msg_type, raw_text, parsed, status_code, reason_code)
                VALUES (clock_timestamp(), ?, ?, ?, ?, ?::jsonb, ?, ?)
                """,
                tradeRef, direction, msgType, raw, toJson(parsed), statusCode, reasonCode);
    }

    public void insertEvent(String tradeRef, String eventType, String reasonCode, String counterpartyBic,
                            Double minutesPending, Object detail) {
        jdbc.update("""
                INSERT INTO trade_event (event_ts, trade_ref, event_type, reason_code, counterparty_bic, minutes_pending, detail)
                VALUES (clock_timestamp(), ?, ?, ?, ?, ?, ?::jsonb)
                """,
                tradeRef, eventType, reasonCode, counterpartyBic, minutesPending, detail == null ? null : toJson(detail));
    }

    public List<Map<String, Object>> messages(String tradeRef) {
        return jdbc.query("""
                SELECT msg_id, event_ts, direction, msg_type, raw_text, parsed, status_code, reason_code
                FROM swift_message WHERE trade_ref = ? ORDER BY event_ts
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("msgId", rs.getString("msg_id"));
            m.put("eventTs", rs.getObject("event_ts", OffsetDateTime.class).toString());
            m.put("direction", rs.getString("direction"));
            m.put("msgType", rs.getString("msg_type"));
            m.put("rawText", rs.getString("raw_text"));
            m.put("parsed", readJson(rs.getString("parsed")));
            m.put("statusCode", rs.getString("status_code"));
            m.put("reasonCode", rs.getString("reason_code"));
            return m;
        }, tradeRef);
    }

    public List<Map<String, Object>> events(String tradeRef) {
        return jdbc.query("""
                SELECT event_ts, event_type, reason_code, counterparty_bic, minutes_pending, detail
                FROM trade_event WHERE trade_ref = ? ORDER BY event_ts
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("eventTs", rs.getObject("event_ts", OffsetDateTime.class).toString());
            m.put("eventType", rs.getString("event_type"));
            m.put("reasonCode", rs.getString("reason_code"));
            m.put("counterpartyBic", rs.getString("counterparty_bic"));
            m.put("minutesPending", rs.getBigDecimal("minutes_pending"));
            m.put("detail", readJson(rs.getString("detail")));
            return m;
        }, tradeRef);
    }

    /** Latest event of a type (e.g. PENDING), used for minutes_pending. */
    public Optional<OffsetDateTime> latestEventTs(String tradeRef, String eventType) {
        return jdbc.query("SELECT max(event_ts) AS ts FROM trade_event WHERE trade_ref = ? AND event_type = ?",
                        (rs, i) -> rs.getObject("ts", OffsetDateTime.class), tradeRef, eventType)
                .stream().filter(Objects::nonNull).findFirst();
    }

    /** First ACK or NAK received for msgRef at/after `since` (DB clock). Returns "ACK"/"NAK". */
    public Optional<String> ackNakFor(String tradeRef, String msgRef, OffsetDateTime since) {
        return jdbc.queryForList("""
                SELECT msg_type FROM swift_message
                WHERE trade_ref = ? AND direction = 'IN' AND msg_type IN ('ACK','NAK') AND parsed->>'ref' = ?
                  AND event_ts >= ?
                ORDER BY event_ts LIMIT 1
                """, String.class, tradeRef, msgRef, since).stream().findFirst();
    }

    public OffsetDateTime dbNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", OffsetDateTime.class);
    }

    /** Code of the most recent failure (NAK 405 code or MT548 reason) for a trade, or null. */
    public String latestFailureCode(String tradeRef) {
        return jdbc.queryForList("""
                SELECT reason_code FROM swift_message
                WHERE trade_ref = ? AND direction = 'IN' AND reason_code IS NOT NULL
                ORDER BY event_ts DESC LIMIT 1
                """, String.class, tradeRef).stream().findFirst().orElse(null);
    }

    public void deleteDemoData() {
        jdbc.update("DELETE FROM swift_message WHERE trade_ref NOT LIKE 'HIST%'");
        jdbc.update("DELETE FROM trade_event WHERE trade_ref NOT LIKE 'HIST%'");
    }

    public String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode readJson(String s) {
        try {
            return s == null ? null : json.readTree(s);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
