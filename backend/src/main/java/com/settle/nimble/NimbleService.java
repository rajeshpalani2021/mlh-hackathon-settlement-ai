package com.settle.nimble;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/** Thin client for the Nimble search API. */
@Service
public class NimbleService {
    private static final Logger log = LoggerFactory.getLogger(NimbleService.class);

    private final RestTemplate rest = new RestTemplate();
    private final String apiKey;
    private final String baseUrl;

    public NimbleService(@Value("${nimble.api-key:}") String apiKey,
                         @Value("${nimble.base-url:https://api.nimbleway.com}") String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    public record SearchResult(String title, String url, String snippet) {}

    /** Searches the web for the given query. Returns up to {limit} results. */
    public List<SearchResult> search(String query, int limit) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Nimble API key not configured, skipping search for: {}", query);
            return List.of();
        }
        try {
            Map<String, Object> body = Map.of("query", query, "limit", limit);
            @SuppressWarnings("unchecked")
            Map<String, Object> response = rest.postForObject(
                    baseUrl + "/v1/search", body, Map.class);
            if (response == null) return List.of();
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results = (List<Map<String, Object>>) response.get("results");
            if (results == null) return List.of();
            return results.stream()
                    .map(r -> new SearchResult(
                            (String) r.getOrDefault("title", ""),
                            (String) r.getOrDefault("url", ""),
                            (String) r.getOrDefault("snippet", "")))
                    .filter(r -> !r.snippet().isBlank())
                    .limit(limit)
                    .toList();
        } catch (Exception e) {
            log.warn("Nimble search failed for '{}': {}", query, e.toString());
            return List.of();
        }
    }

    /** Searches for a SWIFT reason or error code. */
    public List<SearchResult> searchSwiftCode(String code) {
        return search("ISO 15022 SWIFT " + code + " reason code", 5);
    }
}
