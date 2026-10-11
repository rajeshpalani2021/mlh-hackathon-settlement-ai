package com.settle.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class InvestigationRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public InvestigationRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public UUID create(String tradeRef) {
        return jdbc.queryForObject("INSERT INTO investigation (trade_ref) VALUES (?) RETURNING id", UUID.class, tradeRef);
    }

    /** Appends {n, tool, args, summary, ts} to the investigation's steps. */
    public void appendStep(UUID id, String tool, Object args, String summary) {
        jdbc.update("""
                UPDATE investigation SET steps = steps || jsonb_build_array(jsonb_build_object(
                  'n', jsonb_array_length(steps) + 1, 'tool', ?::text, 'args', ?::jsonb, 'summary', ?::text, 'ts', now()))
                WHERE id = ?
                """, tool, toJson(args), summary, id);
    }

    public void saveResult(UUID id, Object result, String usedRecipe, String status) {
        jdbc.update("UPDATE investigation SET result = ?::jsonb, used_recipe = ?, status = ? WHERE id = ?",
                toJson(result), usedRecipe, status, id);
    }

    public void updateStatus(UUID id, String status) {
        jdbc.update("UPDATE investigation SET status = ? WHERE id = ?", status, id);
    }

    public Optional<Map<String, Object>> find(UUID id) {
        return jdbc.query("SELECT id, trade_ref, status, used_recipe, steps, result FROM investigation WHERE id = ?",
                (rs, i) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("tradeRef", rs.getString("trade_ref"));
                    m.put("status", rs.getString("status"));
                    m.put("usedRecipe", rs.getString("used_recipe"));
                    m.put("steps", readJson(rs.getString("steps")));
                    m.put("result", readJson(rs.getString("result")));
                    return m;
                }, id).stream().findFirst();
    }

    public Map<String, Object> metrics() {
        return jdbc.queryForMap("""
                SELECT count(*) AS investigations,
                       count(*) FILTER (WHERE status = 'VERIFIED') AS verified,
                       count(*) FILTER (WHERE used_recipe IS NOT NULL) AS learned_fixes_used
                FROM investigation
                """);
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
