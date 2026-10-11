package com.settle.nimble;

import com.settle.wiki.WikiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Background job that researches unknown SWIFT codes via Nimble
 * and drafts wiki pages for human review.
 */
@Component
public class WikiDraftJob {
    private static final Logger log = LoggerFactory.getLogger(WikiDraftJob.class);

    private final UnknownCodeRepository unknownCodes;
    private final NimbleService nimble;
    private final WikiService wiki;

    public WikiDraftJob(UnknownCodeRepository unknownCodes, NimbleService nimble, WikiService wiki) {
        this.unknownCodes = unknownCodes;
        this.nimble = nimble;
        this.wiki = wiki;
    }

    @Scheduled(fixedDelay = 30_000) // every 30 seconds
    public void processPendingCodes() {
        List<Map<String, Object>> pending = unknownCodes.findPending(5);
        if (pending.isEmpty()) return;

        log.info("WikiDraftJob: {} pending unknown code(s) to research", pending.size());
        for (Map<String, Object> row : pending) {
            long id = ((Number) row.get("id")).longValue();
            String code = (String) row.get("code");
            try {
                researchAndDraft(id, code);
            } catch (Exception e) {
                log.warn("WikiDraftJob: failed to research {}: {}", code, e.toString());
            }
        }
    }

    private void researchAndDraft(long id, String code) {
        log.info("WikiDraftJob: researching code {} via Nimble", code);
        List<NimbleService.SearchResult> results = nimble.searchSwiftCode(code);

        if (results.isEmpty()) {
            log.info("WikiDraftJob: no Nimble results for {}", code);
            unknownCodes.markDrafted(id, null, List.of());
            return;
        }

        String body = buildDraftBody(code, results);
        String draftPath = wiki.draftPage(code, body, results);

        unknownCodes.markDrafted(id, draftPath,
                results.stream().map(NimbleService.SearchResult::url).toList());
        log.info("WikiDraftJob: drafted wiki page for {} -> {}", code, draftPath);
    }

    private String buildDraftBody(String code, List<NimbleService.SearchResult> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(code).append(" — Unknown SWIFT Code\n\n");
        sb.append("> **Status:** Draft — pending human review\n");
        sb.append("> **Researched by:** Nimble web search\n\n");
        sb.append("## What we found\n\n");

        for (int i = 0; i < results.size(); i++) {
            NimbleService.SearchResult r = results.get(i);
            sb.append("### ").append(i + 1).append(". ").append(r.title()).append("\n\n");
            sb.append(r.snippet()).append("\n\n");
            sb.append("**Source:** ").append(r.url()).append("\n\n");
        }

        sb.append("## Suggested actions\n\n");
        sb.append("- [ ] Review the sources above\n");
        sb.append("- [ ] Confirm the meaning of code ").append(code).append("\n");
        sb.append("- [ ] Add any custodian-specific guidance\n");
        sb.append("- [ ] Approve to publish to the wiki\n");

        return sb.toString();
    }
}
