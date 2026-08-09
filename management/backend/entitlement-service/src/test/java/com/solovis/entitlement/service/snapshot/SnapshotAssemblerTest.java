package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.engine.Resolver;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.service.store.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class SnapshotAssemblerTest {

    @Autowired SnapshotAssembler assembler;
    @Autowired CapabilityRepository capabilityRepository;
    @Autowired PlanRepository planRepository;
    @Autowired AccountRepository accountRepository;

    @Test
    void assembledSnapshotResolvesAnAccountOnAPlanBaseline() {
        long capId = capabilityRepository.insert(new CapabilityRow(null, "seats.count", "seats", "Seats", null,
            "QUANTITY", null, 0L, false, null, false, null, null, "ACTIVE", null,
            "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
        long planId = planRepository.insert(new PlanRow(null, "free", "Free", null, "ACTIVE", false,
            "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
        accountRepository.insert(new AccountRow(null, "acct_1", null, planId, "2026-08-09T00:00:00.000Z",
            "PERSON", "dev-operator", "ACTIVE", "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));

        var snapshot = assembler.assembleFull();
        var decision = Resolver.resolve(snapshot, "acct_1", new CapabilityKey("seats.count"), Instant.now());

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.value()).isEqualTo(com.solovis.entitlement.core.model.EntitlementValue.Quantity.of(0));
    }
}
