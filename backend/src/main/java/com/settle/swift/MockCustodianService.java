package com.settle.swift;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.settle.config.SettleProperties;
import com.settle.trade.Trade;
import com.settle.trade.TradeRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Mock custodian: validates BICs (NAK T27 on failure), otherwise replays the trade's scripted
 * scenario of ACK + MT548 replies on a demo clock (1 demo minute = settle.demo-minute-seconds).
 */
@Service
public class MockCustodianService {
    private static final Logger log = LoggerFactory.getLogger(MockCustodianService.class);
    private static final Pattern BIC = Pattern.compile("^[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?$");
    private static final DateTimeFormatter TS_177 = DateTimeFormatter.ofPattern("yyMMddHHmm");

    public record Step(int after, String template, Map<String, String> vars) {}

    private final TemplateService templates;
    private final MessageRepository messages;
    private final TradeRepository trades;
    private final int demoMinuteSeconds;
    private final Map<String, List<Step>> scenarios;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final List<ScheduledFuture<?>> pending = new CopyOnWriteArrayList<>();

    public MockCustodianService(TemplateService templates, MessageRepository messages, TradeRepository trades,
                                SettleProperties props, ObjectMapper json) throws IOException {
        this.templates = templates;
        this.messages = messages;
        this.trades = trades;
        this.demoMinuteSeconds = props.demoMinuteSeconds();
        try (InputStream in = new ClassPathResource("data/scenarios.json").getInputStream()) {
            this.scenarios = json.readValue(in, new TypeReference<>() {});
        }
    }

    /** Stores the outbound message and schedules the custodian's replies. */
    public void send(Trade trade, String rawMessage, String msgRef) {
        List<Map<String, String>> fields = LiteParser.parseFields(rawMessage);
        messages.insertMessage(trade.tradeRef(), "OUT", trade.messageType(), rawMessage, fields, null, null);
        messages.insertEvent(trade.tradeRef(), "SENT", null, trade.counterpartyBic(), null, Map.of("msgRef", msgRef));
        trades.updateStatus(trade.tradeRef(), "SENT");

        boolean bicsValid = fields.stream()
                .filter(f -> f.get("tag").equals("95P"))
                .allMatch(f -> BIC.matcher(f.get("value")).matches());
        if (!bicsValid) {
            log.info("OUT {} {} msgRef={} -> invalid BIC in :95P:, NAK T27 scheduled", trade.messageType(),
                    trade.tradeRef(), msgRef);
            schedule(trade.tradeRef(), msgRef, new Step(1, "NAK", Map.of("errorCode", "T27")));
            return;
        }
        boolean resend = !msgRef.equals(trade.tradeRef());
        List<Step> steps = resend ? scenarios.get("default")
                : scenarios.getOrDefault(trade.tradeRef(), scenarios.get("default"));
        log.info("OUT {} {} msgRef={} -> scenario {}", trade.messageType(), trade.tradeRef(), msgRef,
                steps.stream().map(s -> s.template() + "@" + s.after() * demoMinuteSeconds + "s").toList());
        steps.forEach(s -> schedule(trade.tradeRef(), msgRef, s));
    }

    /** Cancels all scheduled replies (used by demo reset). */
    public void cancelAll() {
        pending.forEach(f -> f.cancel(false));
        pending.clear();
    }

    private void schedule(String tradeRef, String msgRef, Step step) {
        pending.add(scheduler.schedule(() -> {
            try {
                reply(tradeRef, msgRef, step);
            } catch (Exception e) {
                log.error("Custodian reply {} for {} failed", step.template(), tradeRef, e);
            }
        }, (long) step.after() * demoMinuteSeconds, TimeUnit.SECONDS));
    }

    private void reply(String tradeRef, String msgRef, Step step) {
        Trade trade = trades.find(tradeRef).orElse(null);
        if (trade == null) return; // trade was reset meanwhile

        Map<String, Object> vars = new HashMap<>();
        vars.put("ts", OffsetDateTime.now(ZoneOffset.UTC).format(TS_177));
        vars.put("msgRef", msgRef);
        vars.put("statusRef", ("S" + msgRef).substring(0, Math.min(16, msgRef.length() + 1)));
        vars.put("narrative", "");
        if (step.vars() != null) vars.putAll(step.vars());
        String raw = templates.render(step.template(), vars);

        String eventType = eventType(step.template());
        String msgType;
        Object parsed;
        String statusCode;
        String reasonCode;
        if (LiteParser.isAckNak(raw)) {
            Map<String, Object> p = LiteParser.parseAckNak(raw);
            parsed = p;
            msgType = Boolean.TRUE.equals(p.get("ack")) ? "ACK" : "NAK";
            statusCode = msgType;
            reasonCode = (String) p.get("errorCode");
        } else {
            List<Map<String, String>> fields = LiteParser.parseFields(raw);
            parsed = fields;
            msgType = "MT548";
            statusCode = LiteParser.find(fields, "25D", null);
            reasonCode = LiteParser.find(fields, "24B", null);
        }
        messages.insertMessage(tradeRef, "IN", msgType, raw, parsed, statusCode, reasonCode);

        Double minutesPending = null;
        if (eventType.equals("SETTLED")) {
            minutesPending = messages.latestEventTs(tradeRef, "PENDING")
                    .map(ts -> Duration.between(ts, OffsetDateTime.now()).toMillis() / 1000.0 / demoMinuteSeconds)
                    .map(m -> Math.round(m * 10) / 10.0)
                    .orElse(null);
        }
        messages.insertEvent(tradeRef, eventType, reasonCode, trade.counterpartyBic(), minutesPending,
                Map.of("msgRef", msgRef));
        trades.updateStatus(tradeRef, eventType);
        log.info("IN  {} {} msgRef={} -> status {}{}", msgType, tradeRef, msgRef, eventType,
                reasonCode == null ? "" : " reason " + reasonCode);
    }

    private static String eventType(String template) {
        return switch (template) {
            case "ACK" -> "ACK";
            case "NAK" -> "NAK";
            case "MT548_MATCHED" -> "MATCHED";
            case "MT548_UNMATCHED" -> "UNMATCHED";
            case "MT548_PENDING" -> "PENDING";
            case "MT548_SETTLED" -> "SETTLED";
            default -> throw new IllegalArgumentException("Unknown template " + template);
        };
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
