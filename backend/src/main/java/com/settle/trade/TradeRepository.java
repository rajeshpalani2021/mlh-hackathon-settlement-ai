package com.settle.trade;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public class TradeRepository {
    /** Columns the approve flow may change. */
    private static final List<String> FIXABLE_COLUMNS = List.of("pset_bic", "counterparty_bic", "quantity");

    private static final RowMapper<Trade> MAPPER = (rs, i) -> new Trade(
            rs.getString("trade_ref"), rs.getString("side"), rs.getString("isin"), rs.getString("security_name"),
            rs.getBigDecimal("quantity"), rs.getBigDecimal("price"), rs.getString("currency"),
            rs.getObject("exec_ts", OffsetDateTime.class), rs.getDate("trade_date").toLocalDate(),
            rs.getDate("settle_date").toLocalDate(), rs.getString("counterparty_bic"), rs.getString("agent_id"),
            rs.getString("pset_bic"), rs.getString("safe_account"), rs.getString("status"));

    private final JdbcTemplate jdbc;

    public TradeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(Trade t) {
        jdbc.update("""
                INSERT INTO trade (trade_ref, side, isin, security_name, quantity, price, currency, exec_ts,
                                   trade_date, settle_date, counterparty_bic, agent_id, pset_bic, safe_account, status)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,'NEW')
                ON CONFLICT (trade_ref) DO UPDATE SET side = EXCLUDED.side, isin = EXCLUDED.isin,
                  security_name = EXCLUDED.security_name, quantity = EXCLUDED.quantity, price = EXCLUDED.price,
                  currency = EXCLUDED.currency, exec_ts = EXCLUDED.exec_ts, trade_date = EXCLUDED.trade_date,
                  settle_date = EXCLUDED.settle_date, counterparty_bic = EXCLUDED.counterparty_bic,
                  agent_id = EXCLUDED.agent_id, pset_bic = EXCLUDED.pset_bic, safe_account = EXCLUDED.safe_account,
                  status = 'NEW'
                """,
                t.tradeRef(), t.side(), t.isin(), t.securityName(), t.quantity(), t.price(), t.currency(), t.execTs(),
                t.tradeDate(), t.settleDate(), t.counterpartyBic(), t.agentId(), t.psetBic(), t.safeAccount());
    }

    public Optional<Trade> find(String tradeRef) {
        return jdbc.query("SELECT * FROM trade WHERE trade_ref = ?", MAPPER, tradeRef).stream().findFirst();
    }

    /** Grid rows for /api/trades, newest loaded first. */
    public List<Map<String, Object>> listView() {
        return jdbc.query("""
                SELECT trade_ref, side, security_name, isin, quantity, price, currency, trade_date, settle_date,
                       status, created_at
                FROM trade ORDER BY created_at DESC, trade_ref
                """, (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("tradeRef", rs.getString("trade_ref"));
            m.put("side", rs.getString("side"));
            m.put("securityName", rs.getString("security_name"));
            m.put("isin", rs.getString("isin"));
            m.put("quantity", rs.getBigDecimal("quantity"));
            m.put("price", rs.getBigDecimal("price"));
            m.put("currency", rs.getString("currency"));
            m.put("tradeDate", rs.getDate("trade_date").toLocalDate().toString());
            m.put("settleDate", rs.getDate("settle_date").toLocalDate().toString());
            m.put("status", rs.getString("status"));
            m.put("createdAt", rs.getObject("created_at", OffsetDateTime.class).toString());
            return m;
        });
    }

    public List<Trade> findAll() {
        return jdbc.query("SELECT * FROM trade ORDER BY created_at DESC, trade_ref", MAPPER);
    }

    public void updateStatus(String tradeRef, String status) {
        jdbc.update("UPDATE trade SET status = ? WHERE trade_ref = ?", status, tradeRef);
    }

    /** column must be one of pset_bic, counterparty_bic, quantity. */
    public void updateField(String tradeRef, String column, String value) {
        if (!FIXABLE_COLUMNS.contains(column)) throw new IllegalArgumentException("Not fixable: " + column);
        String param = column.equals("quantity") ? "?::numeric" : "?";
        jdbc.update("UPDATE trade SET " + column + " = " + param + " WHERE trade_ref = ?", value, tradeRef);
    }

    public static Map<String, Object> toMap(Trade t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tradeRef", t.tradeRef());
        m.put("side", t.side());
        m.put("isin", t.isin());
        m.put("securityName", t.securityName());
        m.put("quantity", t.quantity());
        m.put("price", t.price());
        m.put("currency", t.currency());
        m.put("execTs", t.execTs().toString());
        m.put("tradeDate", t.tradeDate().toString());
        m.put("settleDate", t.settleDate().toString());
        m.put("counterpartyBic", t.counterpartyBic());
        m.put("agentId", t.agentId());
        m.put("psetBic", t.psetBic());
        m.put("safeAccount", t.safeAccount());
        m.put("status", t.status());
        return m;
    }

    public void deleteDemoData() {
        jdbc.update("DELETE FROM trade WHERE trade_ref NOT LIKE 'HIST%'");
    }
}
