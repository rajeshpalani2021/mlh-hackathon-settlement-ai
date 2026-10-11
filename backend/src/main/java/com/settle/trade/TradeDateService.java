package com.settle.trade;

import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;

/** US equity trade/settle dates: 20:00 ET trade-date boundary, T+1 settlement. */
@Service
public class TradeDateService {
    public static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final int TRADE_DATE_CUTOFF_HOUR = 20;

    // Small hardcoded NYSE holiday list (demo only).
    private static final Set<LocalDate> HOLIDAYS = Set.of(
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 19), LocalDate.of(2026, 2, 16),
            LocalDate.of(2026, 4, 3), LocalDate.of(2026, 5, 25), LocalDate.of(2026, 6, 19),
            LocalDate.of(2026, 7, 3), LocalDate.of(2026, 9, 7), LocalDate.of(2026, 11, 26),
            LocalDate.of(2026, 12, 25), LocalDate.of(2027, 1, 1), LocalDate.of(2027, 1, 18),
            LocalDate.of(2027, 2, 15), LocalDate.of(2027, 3, 26), LocalDate.of(2027, 5, 31),
            LocalDate.of(2027, 6, 18), LocalDate.of(2027, 7, 5), LocalDate.of(2027, 9, 6),
            LocalDate.of(2027, 11, 25), LocalDate.of(2027, 12, 24));

    public LocalDate tradeDate(OffsetDateTime execTs) {
        ZonedDateTime ny = execTs.atZoneSameInstant(NEW_YORK);
        LocalDate d = ny.toLocalDate();
        if (ny.getHour() >= TRADE_DATE_CUTOFF_HOUR || !isBusinessDay(d)) {
            return nextBusinessDay(d);
        }
        return d;
    }

    public LocalDate settleDate(LocalDate tradeDate) {
        return nextBusinessDay(tradeDate);
    }

    public boolean isBusinessDay(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY && !HOLIDAYS.contains(d);
    }

    public LocalDate nextBusinessDay(LocalDate d) {
        LocalDate n = d.plusDays(1);
        while (!isBusinessDay(n)) n = n.plusDays(1);
        return n;
    }
}
