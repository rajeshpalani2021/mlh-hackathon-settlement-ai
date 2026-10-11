package com.settle.agent;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GuardrailsTest {

    @Test
    void querySourceAcceptsOnlySingleSelect() {
        assertThat(SettleTools.validateSelect("SELECT trade_ref, settle_date FROM trade")).isNull();
        assertThat(SettleTools.validateSelect("select * from settle_stats where reason_code = 'LACK'")).isNull();
        assertThat(SettleTools.validateSelect("SELECT 1; DROP TABLE trade")).isNotNull();
        assertThat(SettleTools.validateSelect("DELETE FROM trade")).isNotNull();
        assertThat(SettleTools.validateSelect("SELECT pg_sleep(10)")).isNotNull();
        assertThat(SettleTools.validateSelect("WITH x AS (DELETE FROM trade RETURNING *) SELECT * FROM x")).isNotNull();
        assertThat(SettleTools.validateSelect(" ")).isNotNull();
    }

    @Test
    void onlyWhitelistedFieldsAreFixable() {
        assertThat(InvestigationService.column("95P", "PSET")).isEqualTo("pset_bic");
        assertThat(InvestigationService.column("95P", "BUYR")).isEqualTo("counterparty_bic");
        assertThat(InvestigationService.column("95P", "SELL")).isEqualTo("counterparty_bic");
        assertThat(InvestigationService.column("36B", "SETT")).isEqualTo("quantity");
        assertThat(InvestigationService.column("98A", "SETT")).isNull();
        assertThat(InvestigationService.column("97A", "SAFE")).isNull();
    }

    @Test
    void extractsJsonFromFencedAnswer() {
        assertThat(AgentService.extractJson("Here you go:\n```json\n{\"a\": {\"b\": 1}}\n```"))
                .isEqualTo("{\"a\": {\"b\": 1}}");
    }
}
