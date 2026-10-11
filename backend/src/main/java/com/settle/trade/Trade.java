package com.settle.trade;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record Trade(String tradeRef, String side, String isin, String securityName,
                    BigDecimal quantity, BigDecimal price, String currency, OffsetDateTime execTs,
                    LocalDate tradeDate, LocalDate settleDate, String counterpartyBic, String agentId,
                    String psetBic, String safeAccount, String status) {

    public boolean isBuy() {
        return "BUY".equalsIgnoreCase(side);
    }

    public String messageType() {
        return isBuy() ? "MT541" : "MT543";
    }
}
