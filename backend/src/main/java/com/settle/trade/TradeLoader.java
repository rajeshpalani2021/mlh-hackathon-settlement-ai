package com.settle.trade;

import com.settle.swift.MockCustodianService;
import com.settle.swift.TemplateService;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Loads a trade CSV, assigns trade/settle dates, stores trades and sends their instructions. */
@Service
public class TradeLoader {
    private final TradeRepository trades;
    private final TradeDateService dates;
    private final TemplateService templates;
    private final MockCustodianService custodian;

    public TradeLoader(TradeRepository trades, TradeDateService dates, TemplateService templates,
                       MockCustodianService custodian) {
        this.trades = trades;
        this.dates = dates;
        this.templates = templates;
        this.custodian = custodian;
    }

    public List<Trade> loadAndSend(InputStream csv) throws IOException {
        List<Trade> loaded = parse(csv);
        loaded.forEach(trades::upsert);
        for (Trade t : loaded) {
            custodian.send(t, templates.renderInstruction(t, TemplateService.msgRef(t.tradeRef(), false)), t.tradeRef());
        }
        return loaded;
    }

    List<Trade> parse(InputStream csv) throws IOException {
        List<Trade> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(csv, StandardCharsets.UTF_8))) {
            String header = r.readLine();
            if (header == null) return out;
            String[] cols = header.trim().split(",");
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] v = line.trim().split(",", -1);
                Map<String, String> row = new HashMap<>();
                for (int i = 0; i < cols.length && i < v.length; i++) row.put(cols[i].trim(), v[i].trim());
                OffsetDateTime exec = OffsetDateTime.parse(row.get("exec_ts"));
                LocalDate tradeDate = dates.tradeDate(exec);
                out.add(new Trade(row.get("trade_ref"), row.get("side").toUpperCase(), row.get("isin"),
                        row.get("security_name"), new BigDecimal(row.get("quantity")), new BigDecimal(row.get("price")),
                        row.get("currency"), exec, tradeDate, dates.settleDate(tradeDate),
                        row.get("counterparty_bic"), row.get("agent_id"), row.get("pset_bic"),
                        row.get("safe_account"), "NEW"));
            }
        }
        return out;
    }
}
