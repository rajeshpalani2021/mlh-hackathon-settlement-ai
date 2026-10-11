package com.settle.demo;

import com.settle.swift.MessageRepository;
import com.settle.swift.MockCustodianService;
import com.settle.trade.Trade;
import com.settle.trade.TradeLoader;
import com.settle.trade.TradeRepository;
import com.settle.wiki.WikiService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

@RestController
public class DemoController {
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

    /** Main demo entry point: wipe demo data, reload trades.csv, send everything. */
    @PostMapping("/api/demo/reset")
    public synchronized Map<String, Object> reset() throws IOException {
        custodian.cancelAll();
        messages.deleteDemoData();
        jdbc.update("DELETE FROM investigation");
        trades.deleteDemoData();
        wiki.deleteRecipes();
        try (InputStream in = new ClassPathResource("data/trades.csv").getInputStream()) {
            List<Trade> loaded = loader.loadAndSend(in);
            return Map.of("loaded", loaded.size());
        }
    }
}
