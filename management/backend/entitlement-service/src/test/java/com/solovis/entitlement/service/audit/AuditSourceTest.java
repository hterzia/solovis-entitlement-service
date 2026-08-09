package com.solovis.entitlement.service.audit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditSourceTest {

    @Test
    void defaultsToUi() {
        var auditSource = new AuditSource();
        assertThat(auditSource.current()).isEqualTo("UI");
    }

    @Test
    void runAsSetsAndRestoresTheSource() {
        var auditSource = new AuditSource();
        var seenInside = new String[1];

        auditSource.runAs("SEED", () -> seenInside[0] = auditSource.current());

        assertThat(seenInside[0]).isEqualTo("SEED");
        assertThat(auditSource.current()).isEqualTo("UI");
    }

    @Test
    void nestedRunAsRestoresThePreviousValueNotAlwaysUi() {
        var auditSource = new AuditSource();
        var seenInInnerCall = new String[1];

        auditSource.runAs("SEED", () -> {
            auditSource.runAs("BILLING", () -> seenInInnerCall[0] = auditSource.current());
            assertThat(auditSource.current()).isEqualTo("SEED");
        });

        assertThat(seenInInnerCall[0]).isEqualTo("BILLING");
        assertThat(auditSource.current()).isEqualTo("UI");
    }

    @Test
    void runAsRestoresThePreviousValueEvenWhenTheActionThrows() {
        var auditSource = new AuditSource();

        assertThatThrownBy(() -> auditSource.runAs("SEED", () -> {
            throw new RuntimeException("boom");
        })).isInstanceOf(RuntimeException.class).hasMessage("boom");

        assertThat(auditSource.current()).isEqualTo("UI");
    }
}
