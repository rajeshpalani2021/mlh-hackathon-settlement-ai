package com.settle.nimble;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** API for reviewing drafted wiki pages. */
@RestController
public class WikiDraftController {

    private final UnknownCodeRepository unknownCodes;

    public WikiDraftController(UnknownCodeRepository unknownCodes) {
        this.unknownCodes = unknownCodes;
    }

    @GetMapping("/api/wiki/drafts")
    public List<Map<String, Object>> listDrafts() {
        return unknownCodes.findAll();
    }

    @PostMapping("/api/wiki/drafts/{id}/review")
    public Map<String, Object> review(@PathVariable long id) {
        unknownCodes.markReviewed(id);
        return Map.of("id", id, "status", "reviewed");
    }
}
