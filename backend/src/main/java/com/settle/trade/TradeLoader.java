package com.settle.trade;

import com.settle.agent.RecipeAutoFixer;
import com.settle.swift.MessageRepository;
import com.settle.swift.MockCustodianService;
import com.settle.swift.TemplateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(TradeLoader.class);

    private final TradeRepository trades;
    private final TradeDateService dates;
    private final TemplateService templates;
    private final MockCustodianService custodian;

    private final RecipeAutoFixer autoFixer;
    private final MessageRepository messages;

    public TradeLoader(TradeRepository trades, TradeDateService dates, TemplateService templates,
                       MockCustodianService custodian, RecipeAutoFixer autoFixer, MessageRepository messages) {
        this.trades = trades;
        this.dates = dates;
        this.templates = templates;
        this.custodian = custodian;
        this.autoFixer = autoFixer;
        this.messages = messages;
    }

    public record LoadResult(int loaded, int skipped, int autoFixed) {}

    /** Loads a CSV, inserts only trade refs not already in the DB, and sends them. */
    public LoadResult loadAndSend(InputStream csv) throws IOException {
        List<Trade> parsed = parse(csv);
        List<Trade> fresh = parsed.stream().filter(t -> trades.find(t.tradeRef()).isEmpty()).toList();
        log.info("Trade file: {} rows, {} new, {} already loaded (skipped)", parsed.size(), fresh.size(),
                parsed.size() - fresh.size());
        int autoFixed = storeAndSend(fresh);
        return new LoadResult(fresh.size(), parsed.size() - fresh.size(), autoFixed);
    }

    /**
     * Applies verified recipes (learned fixes) before sending, stores the trades and sends their instructions.
     * Returns how many trades were auto-fixed.
     */
    private int storeAndSend(List<Trade> loaded) {
        int autoFixed = 0;
        for (Trade original : loaded) {
            RecipeAutoFixer.Result fixed = autoFixer.apply(original);
            Trade t = fixed.trade();
            trades.upsert(t);
            for (Map<String, Object> fix : fixed.applied()) {
                messages.insertEvent(t.tradeRef(), "AUTO_FIXED", null, t.counterpartyBic(), null, fix);
                log.info("AUTO_FIXED {} :{}::{} {} -> {} using {}", t.tradeRef(), fix.get("field"), fix.get("qualifier"),
                        fix.get("oldValue"), fix.get("newValue"), fix.get("recipe"));
            }
            if (!fixed.applied().isEmpty()) autoFixed++;
            custodian.send(t, templates.renderInstruction(t, TemplateService.msgRef(t.tradeRef(), false)), t.tradeRef());
        }
        return autoFixed;
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
