package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.engine.Resolver;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.service.store.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

// Not @Transactional, same reasoning as ReadTransactionIsolationTest/SnapshotPublisherTest: the
// assembler now reads via DecisionReadDao on the read pool (a different connection than the write
// pool), so seed data must actually commit on entitlementTransactionManager for the read pool to see it.
@SpringBootTest
class SnapshotAssemblerTest {

    @Autowired SnapshotAssembler assembler;
    @Autowired CapabilityRepository capabilityRepository;
    @Autowired PlanRepository planRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired PlatformTransactionManager entitlementTransactionManager;

    @Test
    void assembledSnapshotResolvesAnAccountOnAPlanBaseline() {
        new TransactionTemplate(entitlementTransactionManager).executeWithoutResult(status -> {
            long planId = planRepository.insert(new PlanRow(null, "t9-assembler-free", "T9 Assembler Free", null, "ACTIVE", false,
                "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
            capabilityRepository.insert(new CapabilityRow(null, "t9.assembler.seats.count", "t9", "Seats", null,
                "QUANTITY", null, 0L, false, null, false, null, null, "ACTIVE", null,
                "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
            accountRepository.insert(new AccountRow(null, "t9_assembler_acct_1", null, planId, "2026-08-09T00:00:00.000Z",
                "PERSON", "dev-operator", "ACTIVE", "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
        });

        var snapshot = assembler.assembleFull();
        var decision = Resolver.resolve(snapshot, "t9_assembler_acct_1", new CapabilityKey("t9.assembler.seats.count"), Instant.now());

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.value()).isEqualTo(com.solovis.entitlement.core.model.EntitlementValue.Quantity.of(0));
    }
}
