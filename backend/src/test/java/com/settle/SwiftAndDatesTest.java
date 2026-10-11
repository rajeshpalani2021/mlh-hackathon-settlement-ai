package com.settle;

import com.settle.swift.LiteParser;
import com.settle.swift.TemplateService;
import com.settle.trade.Trade;
import com.settle.trade.TradeDateService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SwiftAndDatesTest {
    private final TradeDateService dates = new TradeDateService();
    private final TemplateService templates = new TemplateService();

    @Test
    void tradeDateRollsAfter8pmNewYork() {
        // Friday 2026-10-09 22:15 ET -> Monday 10-12, settles Tuesday 10-13
        LocalDate td = dates.tradeDate(OffsetDateTime.parse("2026-10-09T22:15:00-04:00"));
        assertThat(td).isEqualTo(LocalDate.of(2026, 10, 12));
        assertThat(dates.settleDate(td)).isEqualTo(LocalDate.of(2026, 10, 13));
    }

    @Test
    void tradeDateSameDayBefore8pm() {
        LocalDate td = dates.tradeDate(OffsetDateTime.parse("2026-10-09T19:59:00-04:00"));
        assertThat(td).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(dates.settleDate(td)).isEqualTo(LocalDate.of(2026, 10, 12));
    }

    @Test
    void settleDateSkipsHoliday() {
        // Wed 2026-11-25 trade -> Thanksgiving 11-26 skipped -> Fri 11-27
        assertThat(dates.settleDate(LocalDate.of(2026, 11, 25))).isEqualTo(LocalDate.of(2026, 11, 27));
    }

    @Test
    void swiftAmountFormatting() {
        assertThat(TemplateService.swiftAmount(new BigDecimal("185.25"))).isEqualTo("185,25");
        assertThat(TemplateService.swiftAmount(new BigDecimal("1000"))).isEqualTo("1000,");
        assertThat(TemplateService.swiftAmount(new BigDecimal("412.10"))).isEqualTo("412,1");
        assertThat(TemplateService.swiftAmount(new BigDecimal("185250.00"))).isEqualTo("185250,");
    }

    @Test
    void msgRefTruncatesResendTo16() {
        assertThat(TemplateService.msgRef("HCK541000000002", false)).isEqualTo("HCK541000000002");
        assertThat(TemplateService.msgRef("HCK541000000002", true)).isEqualTo("HCK541000000002R");
    }

    @Test
    void rendersAndParsesMt541() {
        Trade t = new Trade("HCK541000000002", "BUY", "US5949181045", "MICROSOFT CORP", new BigDecimal("500"),
                new BigDecimal("412.10"), "USD", OffsetDateTime.parse("2026-10-09T10:42:00-04:00"),
                LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 12), "BRKCUS33XXX", "0999", "DTCYUS3",
                "HF0012345", "NEW");
        String raw = templates.renderInstruction(t, "HCK541000000002");
        assertThat(raw).contains(":95P::SELL//BRKCUS33XXX", ":95R::DEAG/DTCYID/0999", ":19A::SETT//USD206050,");

        List<Map<String, String>> f = LiteParser.parseFields(raw);
        assertThat(LiteParser.find(f, "95P", "PSET")).isEqualTo("DTCYUS3");
        assertThat(LiteParser.find(f, "36B", "SETT")).isEqualTo("UNIT/500,");
        assertThat(LiteParser.find(f, "98A", "TRAD")).isEqualTo("20261009");
        assertThat(LiteParser.find(f, "95R", "DEAG")).isEqualTo("0999");
        assertThat(LiteParser.find(f, "35B", null)).isEqualTo("ISIN US5949181045");
    }

    @Test
    void parsesNakAndMt548() {
        String nak = templates.render("NAK", Map.of("ts", "2610091042", "errorCode", "T27", "msgRef", "HCK541000000002"));
        Map<String, Object> p = LiteParser.parseAckNak(nak);
        assertThat(p).containsEntry("ack", false).containsEntry("errorCode", "T27").containsEntry("ref", "HCK541000000002");

        String pend = templates.render("MT548_PENDING", Map.of("statusRef", "SX", "msgRef", "HCK543000000005",
                "reasonCode", "LACK", "narrative", "INSUFFICIENT SECURITIES IN ACCOUNT"));
        List<Map<String, String>> f = LiteParser.parseFields(pend);
        assertThat(LiteParser.find(f, "25D", "SETT")).isEqualTo("PEND");
        assertThat(LiteParser.find(f, "24B", "PEND")).isEqualTo("LACK");
        assertThat(LiteParser.find(f, "20C", "RELA")).isEqualTo("HCK543000000005");
    }
}
