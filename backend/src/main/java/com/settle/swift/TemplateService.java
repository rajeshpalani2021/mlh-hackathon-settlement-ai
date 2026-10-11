package com.settle.swift;

import com.settle.trade.Trade;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Renders SWIFT text templates from classpath:templates/NAME.fin with ${var} placeholders. */
@Service
public class TemplateService {
    private static final Pattern VAR = Pattern.compile("\\$\\{(\\w+)}");
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.BASIC_ISO_DATE;

    public String render(String name, Map<String, ?> vars) {
        String text = load(name);
        Matcher m = VAR.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            Object v = vars.get(m.group(1));
            if (v == null) throw new IllegalArgumentException("Template " + name + " missing var " + m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v.toString()));
        }
        m.appendTail(sb);
        return sb.toString().replace("\r\n", "\n").stripTrailing();
    }

    /** Renders the MT541 (buy) / MT543 (sell) settlement instruction for a trade. */
    public String renderInstruction(Trade t, String msgRef) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("msgRef", msgRef);
        vars.put("tradeDate", t.tradeDate().format(YYYYMMDD));
        vars.put("settleDate", t.settleDate().format(YYYYMMDD));
        vars.put("currency", t.currency());
        vars.put("price", swiftAmount(t.price()));
        vars.put("isin", t.isin());
        vars.put("securityName", t.securityName() == null ? "" : t.securityName());
        vars.put("quantity", swiftAmount(t.quantity()));
        vars.put("safeAccount", t.safeAccount());
        vars.put("counterpartyBic", t.counterpartyBic());
        vars.put("agentId", t.agentId());
        vars.put("psetBic", t.psetBic());
        vars.put("amount", swiftAmount(t.quantity().multiply(t.price())));
        return render(t.messageType(), vars);
    }

    /** SEME reference: trade ref for the first send, trade ref + "R1" (max 16 chars) for a resend. */
    public static String msgRef(String tradeRef, boolean resend) {
        String ref = resend ? tradeRef + "R1" : tradeRef;
        return ref.length() > 16 ? ref.substring(0, 16) : ref;
    }

    private String load(String name) {
        try {
            return new ClassPathResource("templates/" + name + ".fin").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Unknown template " + name, e);
        }
    }

    /** SWIFT decimal: comma separator, trailing comma for whole numbers (185,25 / 1000,). */
    public static String swiftAmount(BigDecimal value) {
        BigDecimal v = value.stripTrailingZeros();
        if (v.scale() <= 0) return v.toPlainString() + ",";
        return v.toPlainString().replace('.', ',');
    }
}
