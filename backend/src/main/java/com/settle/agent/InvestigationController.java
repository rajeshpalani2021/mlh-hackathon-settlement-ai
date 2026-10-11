package com.settle.agent;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
public class InvestigationController {
    private final InvestigationService service;

    public InvestigationController(InvestigationService service) {
        this.service = service;
    }

    public record InvestigateRequest(String tradeRef) {}

    @PostMapping("/api/investigations")
    public Map<String, Object> investigate(@RequestBody InvestigateRequest req) {
        if (req == null || req.tradeRef() == null || req.tradeRef().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tradeRef is required");
        }
        return service.investigate(req.tradeRef().strip());
    }

    @GetMapping("/api/investigations/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        return service.response(id);
    }

    @PostMapping("/api/investigations/{id}/approve")
    public Map<String, Object> approve(@PathVariable UUID id) {
        return service.approve(id);
    }

    @GetMapping("/api/metrics")
    public Map<String, Object> metrics() {
        return service.metrics();
    }
}
