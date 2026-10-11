package com.settle.trade;

import com.settle.swift.MessageRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trades")
public class TradeController {
    private final TradeRepository trades;
    private final MessageRepository messages;
    private final TradeLoader loader;

    public TradeController(TradeRepository trades, MessageRepository messages, TradeLoader loader) {
        this.trades = trades;
        this.messages = messages;
        this.loader = loader;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return trades.listView();
    }

    @PostMapping("/upload")
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file) throws IOException {
        List<Trade> loaded = loader.loadAndSend(file.getInputStream());
        return Map.of("loaded", loaded.size());
    }

    @GetMapping("/{ref}/messages")
    public ResponseEntity<Map<String, Object>> messages(@PathVariable String ref) {
        if (trades.find(ref).isEmpty()) return ResponseEntity.notFound().build();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tradeRef", ref);
        body.put("messages", messages.messages(ref));
        body.put("events", messages.events(ref));
        return ResponseEntity.ok(body);
    }
}
