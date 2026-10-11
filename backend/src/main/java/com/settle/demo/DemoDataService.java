package com.settle.demo;

import com.settle.swift.MessageRepository;
import com.settle.swift.MockCustodianService;
import com.settle.trade.TradeRepository;
import com.settle.wiki.WikiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Wipes demo data (never the HIST history seed). */
@Service
public class DemoDataService {
    private static final Logger log = LoggerFactory.getLogger(DemoDataService.class);

    private final MockCustodianService custodian;
    private final MessageRepository messages;
    private final TradeRepository trades;
    private final JdbcTemplate jdbc;
    private final WikiService wiki;

    public DemoDataService(MockCustodianService custodian, MessageRepository messages, TradeRepository trades,
                           JdbcTemplate jdbc, WikiService wiki) {
        this.custodian = custodian;
        this.messages = messages;
        this.trades = trades;
        this.jdbc = jdbc;
        this.wiki = wiki;
    }

    /**
     * Cancels pending custodian replies and deletes trades, messages, events and investigations.
     * Returns the number of trades deleted.
     */
    public synchronized int clear(boolean alsoRecipes) {
        custodian.cancelAll();
        messages.deleteDemoData();
        // unknown_code (Nimble research drafts) references investigation: keep the drafts, drop the link
        jdbc.update("UPDATE unknown_code SET investigation_id = NULL WHERE investigation_id IS NOT NULL");
        jdbc.update("DELETE FROM investigation");
        int deleted = trades.deleteDemoData();
        if (alsoRecipes) wiki.deleteRecipes();
        log.info("Demo data cleared: {} trades deleted{}", deleted, alsoRecipes ? ", learned recipes deleted" : "");
        return deleted;
    }
}
