package com.solovis.entitlement.service.error;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RefIdTest {

    @Test
    void parsesCanonicalReferences() {
        assertThat(RefId.parse("ovr_7", "ovr_")).isEqualTo(7L);
        assertThat(RefId.parse("ovr_0", "ovr_")).isEqualTo(0L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ovr_007", "ovr_+7", "ovr_", "ovr_x", "ovr_12345678901234567890"})
    void rejectsNonCanonicalOrOverflowingSuffixes(String ref) {
        assertThatThrownBy(() -> RefId.parse(ref, "ovr_"))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void rejectsNullRef() {
        assertThatThrownBy(() -> RefId.parse(null, "ovr_"))
            .isInstanceOf(EntitlementApiException.class)
            .extracting("errorCode").isEqualTo(ErrorCode.VALIDATION_FAILED);
    }
}
