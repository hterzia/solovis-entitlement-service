package com.solovis.entitlement.core.error;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class EntitlementExceptionsTest {

    @Test
    void unknownAccountExceptionCarriesTheExternalId() {
        var ex = new UnknownAccountException("acct_9931");
        assertThat(ex.accountExternalId()).isEqualTo("acct_9931");
        assertThat(ex.getMessage()).contains("acct_9931");
    }

    @Test
    void unknownCapabilityExceptionCarriesTheKey() {
        var ex = new UnknownCapabilityException("export.parquet");
        assertThat(ex.capabilityKey()).isEqualTo("export.parquet");
        assertThat(ex.getMessage()).contains("export.parquet");
    }

    @Test
    void retiredCapabilityExceptionCarriesTheKey() {
        var ex = new RetiredCapabilityException("legacy.feature");
        assertThat(ex.capabilityKey()).isEqualTo("legacy.feature");
        assertThat(ex.getMessage()).contains("legacy.feature");
    }
}
