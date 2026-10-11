package com.settle.demo;

import com.settle.trade.TradeLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

@RestController
public class DemoController {
    private final DemoDataService demoData;
    private final TradeLoader loader;

    public DemoController(DemoDataService demoData, TradeLoader loader) {
        this.demoData = demoData;
        this.loader = loader;
    }

    /**
     * Start fresh: deletes all trades, SWIFT messages, events and investigations (the HIST stats seed is kept).
     * Learned recipes are kept unless recipes=true.
     */
    @PostMapping("/api/demo/clear")
    public synchronized Map<String, Object> clear(@RequestParam(defaultValue = "false") boolean recipes) {
        int deleted = demoData.clear(recipes);
        return Map.of("tradesDeleted", deleted, "recipesDeleted", recipes);
    }

    /** Full demo reset: wipe demo data and learned recipes, reload trades.csv, send everything. */
    @PostMapping("/api/demo/reset")
    public synchronized TradeLoader.LoadResult reset() throws IOException {
        demoData.clear(true);
        try (InputStream in = new ClassPathResource("data/trades.csv").getInputStream()) {
            return loader.loadAndSend(in);
        }
    }
}
