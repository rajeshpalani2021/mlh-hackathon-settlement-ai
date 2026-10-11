package com.settle.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.settle.agent.InvestigationResult.ProposedFix;
import com.settle.nimble.UnknownCodeRepository;
import com.settle.swift.MessageRepository;
import com.settle.swift.MockCustodianService;
import com.settle.swift.TemplateService;
import com.settle.trade.Trade;
import com.settle.trade.TradeRepository;
import com.settle.wiki.WikiService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Investigate -> (human) approve -> apply fix -> resend -> verify on ACK -> write recipe. */
@Service
public class InvestigationService {
    private static final Logger log = LoggerFactory.getLogger(InvestigationService.class);

    private final InvestigationRepository investigations;
    private final AgentService agent;
    private final TradeRepository trades;
    private final MessageRepository messages;
    private final TemplateService templates;
    private final MockCustodianService custodian;
    private final WikiService wiki;
    private final UnknownCodeRepository unknownCodes;
    private final ObjectMapper json;

    public InvestigationService(InvestigationRepository investigations, AgentService agent, TradeRepository trades,
                                MessageRepository messages, TemplateService templates, MockCustodianService custodian,
                                WikiService wiki, UnknownCodeRepository unknownCodes, ObjectMapper json) {
        this.investigations = investigations;
        this.agent = agent;
        this.trades = trades;
        this.messages = messages;
        this.templates = templates;
        this.custodian = custodian;
        this.wiki = wiki;
        this.unknownCodes = unknownCodes;
        this.json = json;
    }

    public Map<String, Object> investigate(String tradeRef) {
        Trade trade = trades.find(tradeRef)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown trade " + tradeRef));
        UUID id = investigations.create(tradeRef);

        InvestigationResult result;
        String status;
        try {
            result = validate(agent.investigate(id, tradeRef), trade);
            status = "PROPOSED";
        } catch (Exception e) {
            log.warn("Investigation {} for {} failed: {}", id, tradeRef, e.toString());
            result = new InvestigationResult("LLM unavailable", "The investigator could not complete: " + e.getMessage(),
                    0.0, null, List.of(), null, null);
            status = "ERROR";
        }
        Map<String, Object> stored = new LinkedHashMap<>(json.convertValue(result, Map.class));
        stored.put("failureCode", messages.latestFailureCode(tradeRef));
        investigations.saveResult(id, stored, result.recipeUsed(), status);

        // Queue unknown codes for background research via Nimble
        if (result.unknownCodes() != null) {
            for (String code : result.unknownCodes()) {
                if (code != null && !code.isBlank()) {
                    unknownCodes.save(code.strip().toUpperCase(), id);
                }
            }
        }

        return response(id);
    }

    /** Code-side guardrails: whitelist of fixable fields, oldValue must equal the DB value, recipe must exist. */
    InvestigationResult validate(InvestigationResult r, Trade trade) {
        String recipe = r.recipeUsed();
        if (recipe != null) {
            recipe = recipe.strip().replaceAll("\\.md$", "");
            if (recipe.isEmpty() || recipe.equalsIgnoreCase("null") || !recipe.startsWith("recipes/") || wiki.read(recipe) == null) {
                recipe = null;
            }
        }
        r = r.withRecipeUsed(recipe);

        ProposedFix fix = r.fix();
        if (fix == null || fix.field() == null) return r.withFix(null, r.confidence(), r.explanation());

        String field = fix.field().strip().toUpperCase(Locale.ROOT).replace(":", "");
        String qualifier = fix.qualifier() == null ? "" : fix.qualifier().strip().toUpperCase(Locale.ROOT);
        String column = column(field, qualifier);
        if (column == null) {
            return rejected(r, "field " + field + "/" + qualifier + " is not fixable in this demo");
        }
        String current = currentValue(trade, column);
        String oldValue = normalize(column, fix.oldValue());
        String newValue = normalize(column, fix.newValue());
        if (oldValue == null || !oldValue.equals(current)) {
            return rejected(r, "oldValue '" + fix.oldValue() + "' does not match current value '" + current + "'");
        }
        if (newValue == null || newValue.isBlank() || newValue.equals(current)) {
            return rejected(r, "newValue '" + fix.newValue() + "' is empty or unchanged");
        }
        return r.withFix(new ProposedFix(field, qualifier, oldValue, newValue), r.confidence(), r.explanation());
    }

    private static InvestigationResult rejected(InvestigationResult r, String why) {
        return r.withFix(null, Math.min(r.confidence(), 0.3),
                r.explanation() + " [Proposed fix rejected by validation: " + why + "]");
    }

    static String column(String field, String qualifier) {
        if (field.equals("95P") && qualifier.equals("PSET")) return "pset_bic";
        if (field.equals("95P") && (qualifier.equals("BUYR") || qualifier.equals("SELL"))) return "counterparty_bic";
        if (field.equals("36B") && qualifier.equals("SETT")) return "quantity";
        return null;
    }

    private static String currentValue(Trade t, String column) {
        return switch (column) {
            case "pset_bic" -> t.psetBic();
            case "counterparty_bic" -> t.counterpartyBic();
            case "quantity" -> t.quantity().stripTrailingZeros().toPlainString();
            default -> throw new IllegalArgumentException(column);
        };
    }

    /** Accepts LLM-style values like "//DTCYUS3", "UNIT/500," and returns DB-comparable ones. */
    private static String normalize(String column, String value) {
        if (value == null) return null;
        String v = value.strip().replaceAll("^/+", "");
        if (column.equals("quantity")) {
            v = v.replaceAll("(?i)^UNIT/", "").replace(",", ".");
            if (v.endsWith(".")) v = v.substring(0, v.length() - 1);
            try {
                return new BigDecimal(v).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return v.toUpperCase(Locale.ROOT);
    }

    public Map<String, Object> approve(UUID id) {
        Map<String, Object> inv = investigations.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown investigation " + id));
        if (!"PROPOSED".equals(inv.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Investigation is " + inv.get("status") + ", not PROPOSED");
        }
        JsonNode result = (JsonNode) inv.get("result");
        JsonNode fixNode = result.get("fix");
        if (fixNode == null || fixNode.isNull()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Investigation has no validated fix to apply");
        }
        ProposedFix fix = json.convertValue(fixNode, ProposedFix.class);
        String tradeRef = (String) inv.get("tradeRef");
        String column = column(fix.field(), fix.qualifier());

        // 1. apply the fix (code, not the LLM)
        trades.updateField(tradeRef, column, fix.newValue());
        messages.insertEvent(tradeRef, "FIXED", null, null, null, Map.of(
                "investigationId", id.toString(), "field", fix.field(), "qualifier", fix.qualifier(),
                "oldValue", fix.oldValue(), "newValue", fix.newValue()));

        // 2. re-render and resend
        Trade fixed = trades.find(tradeRef).orElseThrow();
        String msgRef = TemplateService.msgRef(tradeRef, true);
        OffsetDateTime since = messages.dbNow();
        custodian.send(fixed, templates.renderInstruction(fixed, msgRef), msgRef);

        // 3. wait for ACK/NAK
        Optional<String> reply = Optional.empty();
        for (int i = 0; i < 25 && reply.isEmpty(); i++) {
            sleep(200);
            reply = messages.ackNakFor(tradeRef, msgRef, since);
        }

        String status;
        String recipeWritten = null;
        if (reply.isPresent() && reply.get().equals("ACK")) {
            status = "VERIFIED";
            JsonNode recipeUsed = result.get("recipeUsed");
            if (recipeUsed == null || recipeUsed.isNull()) {
                recipeWritten = writeRecipe(result, fix, tradeRef);
            }
        } else {
            status = "FAILED";
        }
        investigations.updateStatus(id, status);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id.toString());
        out.put("status", status);
        out.put("newStatus", trades.find(tradeRef).map(Trade::status).orElse(null));
        out.put("msgRef", msgRef);
        out.put("recipeWritten", recipeWritten);
        return out;
    }

    private String writeRecipe(JsonNode result, ProposedFix fix, String tradeRef) {
        JsonNode code = result.get("failureCode");
        String failureCode = code == null || code.isNull() ? "FIX" : code.asText();
        JsonNode draft = result.get("recipeDraft");
        String body = """
                # Verified fix: %s on :%s::%s

                **Symptom:** %s
                **Fix applied:** change `:%s::%s` from `%s` to `%s`, keep everything else, resend with a new SEME reference.
                **Verified:** trade %s was resent after this change and the network returned ACK.

                %s
                """.formatted(failureCode, fix.field(), fix.qualifier(), text(result, "rootCause"),
                fix.field(), fix.qualifier(), fix.oldValue(), fix.newValue(), tradeRef,
                draft == null || draft.isNull() ? "" : draft.asText());
        return wiki.writeRecipe(failureCode, fix.field(), fix.qualifier(), body, tradeRef);
    }

    public Map<String, Object> response(UUID id) {
        Map<String, Object> inv = investigations.find(id).orElseThrow();
        JsonNode r = (JsonNode) inv.get("result");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", inv.get("id"));
        out.put("tradeRef", inv.get("tradeRef"));
        out.put("status", inv.get("status"));
        out.put("rootCause", text(r, "rootCause"));
        out.put("explanation", text(r, "explanation"));
        out.put("confidence", r == null || r.get("confidence") == null ? 0.0 : r.get("confidence").asDouble());
        out.put("fix", r == null ? null : nullIfMissing(r.get("fix")));
        out.put("wikiPagesUsed", r == null ? List.of() : nullIfMissing(r.get("wikiPagesUsed")));
        out.put("recipeUsed", inv.get("usedRecipe"));
        out.put("steps", inv.get("steps"));
        return out;
    }

    public Map<String, Object> metrics() {
        Map<String, Object> m = investigations.metrics();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("investigations", m.get("investigations"));
        out.put("verified", m.get("verified"));
        out.put("learnedFixesUsed", m.get("learned_fixes_used"));
        out.put("recipes", wiki.count("recipe"));
        return out;
    }

    private static String text(JsonNode node, String field) {
        return node == null || node.get(field) == null || node.get(field).isNull() ? null : node.get(field).asText();
    }

    private static JsonNode nullIfMissing(JsonNode n) {
        return n == null || n.isNull() ? null : n;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
