package com.solovis.entitlement.service.audit;

import com.solovis.entitlement.service.store.AuditEventRepository;
import com.solovis.entitlement.service.store.CapabilityRepository;
import com.solovis.entitlement.service.store.CapabilityRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class AuditRecorderTest {

    @Autowired AuditEventRepository repository;
    @Autowired CapabilityRepository capabilityRepository;

    @Test
    void recordWritesEveryColumnAndReturnsTheAssignedSeq() {
        long capabilityId = capabilityRepository.insert(new CapabilityRow(null, "reports.monthly", null,
            "Monthly reports", null, "SWITCH", false, null, false, null,
            false, null, null, "ACTIVE", null,
            "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));

        var fixedClock = Clock.fixed(Instant.parse("2026-08-09T14:03:11.482Z"), ZoneOffset.UTC);
        var recorder = new AuditRecorder(repository, fixedClock);
        var entry = AuditEntry.builder()
            .actor(new Actor("dev-operator", Actor.Kind.PERSON))
            .source("UI")
            .entityType("CAPABILITY")
            .entityId("reports.monthly")
            .action("CREATE")
            .capabilityId(capabilityId)
            .afterJson("{\"displayName\":\"Monthly reports\"}")
            .build();

        long seq = recorder.record(entry);

        var row = repository.findBySeq(seq).orElseThrow();
        assertThat(row.occurredAt()).isEqualTo("2026-08-09T14:03:11.482Z");
        assertThat(row.actorKind()).isEqualTo("PERSON");
        assertThat(row.actorId()).isEqualTo("dev-operator");
        assertThat(row.source()).isEqualTo("UI");
        assertThat(row.entityType()).isEqualTo("CAPABILITY");
        assertThat(row.action()).isEqualTo("CREATE");
        assertThat(row.capabilityId()).isEqualTo(capabilityId);
        assertThat(row.accountId()).isNull();
        assertThat(row.afterJson()).contains("Monthly reports");
    }
}
