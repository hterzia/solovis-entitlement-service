package com.solovis.entitlement.service.client;

import com.solovis.entitlement.client.EntitlementClient;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.service.admin.dto.AccountCreateRequest;
import com.solovis.entitlement.service.admin.dto.CapabilityCreateRequest;
import com.solovis.entitlement.service.admin.dto.OverrideCreateRequest;
import com.solovis.entitlement.service.admin.dto.PlanCreateRequest;
import com.solovis.entitlement.service.admin.service.AccountAdminService;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.admin.service.OverrideAdminService;
import com.solovis.entitlement.service.admin.service.PlanAdminService;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.window.WindowBoundaryRoller;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>c14 — while the service is unreachable, a product goes on answering with the last value it
 * saw, an ended override included, and this is intended behaviour rather than a defect.</b>
 *
 * <p>This is the criterion the whole shape of 002 was chosen to satisfy, and until the SDK existed
 * it could not be demonstrated at all: there was no replica to cut off. It is the reason replicas
 * never evaluate windows. A replica that could would lapse an override <em>correctly</em> while
 * isolated — which sounds better and is precisely what the fixed outage posture forbids, because a
 * product that quietly withdraws a customer's access while it cannot reach the service has made a
 * decision nobody authorised on evidence it cannot check.
 *
 * <p>So the ending has to arrive as an ordinary published change or not at all. Here it does not
 * arrive, because the client is closed before the boundary is rolled, and the last snapshot it holds
 * still contains the override. The replica goes on granting.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutageHonoursEndedOverrideTest {

    private static final String PLAN = "t16-outage";
    private static final String CAPABILITY = "t16.reports.monthly";
    private static final String ACCOUNT = "acct_t16_outage";

    @LocalServerPort int port;

    @Autowired CapabilityAdminService capabilityService;
    @Autowired PlanAdminService planService;
    @Autowired AccountAdminService accountService;
    @Autowired OverrideAdminService overrideService;
    @Autowired WindowBoundaryRoller roller;

    private EntitlementClient client;

    @BeforeAll
    void seed() {
        planService.create(new PlanCreateRequest(PLAN, "Outage plan", null));
        // RANDOM_PORT gives this class its own Spring context and therefore its own database, so
        // there is no default plan until this one designates itself.
        planService.designateDefault(PLAN);
        capabilityService.create(new CapabilityCreateRequest(CAPABILITY, "Monthly reports", null, "QUANTITY",
            new ValueDto("QUANTITY", null, 50L, null, null, null), null, null));
        accountService.create(new AccountCreateRequest(ACCOUNT, "Outage Co"));
        // The plan leaves the capability unset, so the baseline is its default of 50.
    }

    @AfterAll
    void closeTheClient() {
        if (client != null) {
            client.close();
        }
    }

    @Test
    void aCutOffReplicaGoesOnHonouringAnOverrideThatHasSinceEnded() {
        // An override in force today and expiring today: in force when the replica syncs, ended the
        // moment the clock passes midnight.
        LocalDate today = LocalDate.now();
        overrideService.create(ACCOUNT, new OverrideCreateRequest(CAPABILITY, "GRANT",
            new ValueDto("QUANTITY", null, 200L, null, null, null), "Ends tonight",
            null, today.toString()));

        client = EntitlementClient.builder()
            .serviceUrl("http://127.0.0.1:" + port)
            .pollInterval(Duration.ofMillis(200))
            .startupTimeout(Duration.ofSeconds(20))
            .build();

        var whileConnected = client.check(ACCOUNT, CAPABILITY);
        assertThat(((EntitlementValue.Quantity) whileConnected.value()).amount())
            .as("the replica sees the grant while it can reach the service")
            .isEqualTo(200L);

        // The outage. Closing the client stops its sync loop, which is what "unreachable" means from
        // the replica's side — it keeps the snapshot it last saw and answers from that.
        client.close();

        // Midnight passes at the service. The ending is published, and there is nobody listening.
        roller.rollOneBoundary(today.plusDays(1));

        var duringOutage = client.check(ACCOUNT, CAPABILITY);
        assertThat(((EntitlementValue.Quantity) duringOutage.value()).amount())
            .as("c14 — an isolated product answers with the last value it saw, ended override and all. "
                + "This is the requirement, not a bug: a replica that lapsed it correctly would be "
                + "withdrawing access on evidence it could not check.")
            .isEqualTo(200L);

        client = null; // already closed; @AfterAll must not close it twice
    }
}
