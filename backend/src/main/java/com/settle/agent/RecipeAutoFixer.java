package com.settle.agent;

import com.settle.trade.Trade;
import com.settle.wiki.WikiService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies verified recipes BEFORE a trade is sent: if a field still carries a recipe's known-bad value
 * (e.g. PSET DTCYUS3), it is replaced with the verified correct value. Deterministic code, never the LLM;
 * only the same whitelisted fields as the approve flow, and only exact matches.
 */
@Service
public class RecipeAutoFixer {

    public record Result(Trade trade, List<Map<String, Object>> applied) {}

    private final WikiService wiki;

    public RecipeAutoFixer(WikiService wiki) {
        this.wiki = wiki;
    }

    public Result apply(Trade trade) {
        Trade t = trade;
        List<Map<String, Object>> applied = new ArrayList<>();
        for (WikiService.Page recipe : wiki.recipes()) {
            Map<String, String> fm = recipe.frontmatter();
            String field = fm.get("fix_field");
            String qualifier = fm.get("fix_qualifier");
            String oldValue = fm.get("fix_old");
            String newValue = fm.get("fix_new");
            if (field == null || qualifier == null || oldValue == null || newValue == null) continue;
            String column = InvestigationService.column(field, qualifier);
            if (column == null || !oldValue.equals(current(t, column))) continue;

            t = with(t, column, newValue);
            Map<String, Object> fix = new LinkedHashMap<>();
            fix.put("recipe", recipe.id());
            fix.put("field", field);
            fix.put("qualifier", qualifier);
            fix.put("oldValue", oldValue);
            fix.put("newValue", newValue);
            fix.put("verifiedBy", fm.get("verified_by"));
            applied.add(fix);
        }
        return new Result(t, applied);
    }

    private static String current(Trade t, String column) {
        return switch (column) {
            case "pset_bic" -> t.psetBic();
            case "counterparty_bic" -> t.counterpartyBic();
            case "quantity" -> t.quantity().stripTrailingZeros().toPlainString();
            default -> null;
        };
    }

    private static Trade with(Trade t, String column, String value) {
        return switch (column) {
            case "pset_bic" -> new Trade(t.tradeRef(), t.side(), t.isin(), t.securityName(), t.quantity(), t.price(),
                    t.currency(), t.execTs(), t.tradeDate(), t.settleDate(), t.counterpartyBic(), t.agentId(), value,
                    t.safeAccount(), t.status());
            case "counterparty_bic" -> new Trade(t.tradeRef(), t.side(), t.isin(), t.securityName(), t.quantity(),
                    t.price(), t.currency(), t.execTs(), t.tradeDate(), t.settleDate(), value, t.agentId(), t.psetBic(),
                    t.safeAccount(), t.status());
            case "quantity" -> new Trade(t.tradeRef(), t.side(), t.isin(), t.securityName(), new BigDecimal(value),
                    t.price(), t.currency(), t.execTs(), t.tradeDate(), t.settleDate(), t.counterpartyBic(), t.agentId(),
                    t.psetBic(), t.safeAccount(), t.status());
            default -> t;
        };
    }
}
