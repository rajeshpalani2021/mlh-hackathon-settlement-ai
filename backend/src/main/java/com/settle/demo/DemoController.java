package com.settle.demo;

import com.settle.swift.MessageRepository;
import com.settle.swift.MockCustodianService;
import com.settle.trade.Trade;
import com.settle.trade.TradeLoader;
import com.settle.trade.TradeRepository;
import com.settle.wiki.WikiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

@RestController
public class DemoController {
    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final MockCustodianService custodian;
    private final TradeRepository trades;
    private final MessageRepository messages;
    private final JdbcTemplate jdbc;
    private final TradeLoader loader;
    private final WikiService wiki;

    public DemoController(MockCustodianService custodian, TradeRepository trades, MessageRepository messages,
                          JdbcTemplate jdbc, TradeLoader loader, WikiService wiki) {
        this.custodian = custodian;
        this.trades = trades;
        this.messages = messages;
        this.jdbc = jdbc;
        this.loader = loader;
        this.wiki = wiki;
    }

    /**
     * Start fresh: deletes all trades, SWIFT messages, events and investigations (the HIST stats seed is kept).
     * Learned recipes are kept unless recipes=true.
     */
    @PostMapping("/api/demo/clear")
    public synchronized Map<String, Object> clear(@RequestParam(defaultValue = "false") boolean recipes) {
        int deleted = clearDemoData(recipes);
        log.info("Demo data cleared: {} trades deleted{}", deleted, recipes ? ", learned recipes deleted" : "");
        return Map.of("tradesDeleted", deleted, "recipesDeleted", recipes);
    }

    /** Main demo entry point: wipe demo data, reload trades.csv, send everything. */
    @PostMapping("/api/demo/reset")
    public synchronized Map<String, Object> reset() throws IOException {
        clearDemoData(true);
        try (InputStream in = new ClassPathResource("data/trades.csv").getInputStream()) {
            List<Trade> loaded = loader.loadAndSend(in);
            return Map.of("loaded", loaded.size());
        }
    }

    /** Returns the number of trades deleted. */
    private int clearDemoData(boolean alsoRecipes) {
        custodian.cancelAll();
        messages.deleteDemoData();
        // unknown_code (Nimble research drafts) references investigation: keep the drafts, drop the link
        jdbc.update("UPDATE unknown_code SET investigation_id = NULL WHERE investigation_id IS NOT NULL");
        jdbc.update("DELETE FROM investigation");
        int deleted = jdbc.queryForObject("SELECT count(*) FROM trade WHERE trade_ref NOT LIKE 'HIST%'", Integer.class);
        trades.deleteDemoData();
        if (alsoRecipes) wiki.deleteRecipes();
        return deleted;
    }
}
