package com.solovis.entitlement.service.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class PlanEntitlementRepositoryTest {

	@Autowired
	PlanEntitlementRepository repository;

	@Autowired
	PlanRepository planRepository;

	@Autowired
	CapabilityRepository capabilityRepository;

	long planId;
	long capabilityId;

	@BeforeEach
	void seedParents() {
		planId = planRepository.insert(new PlanRow(null, "pro", "Pro", null, "ACTIVE", false,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
		capabilityId = capabilityRepository.insert(new CapabilityRow(null, "reports.monthly", null, "Monthly reports",
				null, "QUANTITY", null, 0L, false, null,
				true, 0L, null, "ACTIVE", null,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
	}

	@Test
	void upsertInsertsThenUpdatesTheSameRow() {
		repository.upsert(new PlanEntitlementRow(planId, capabilityId, null, 50L, false, null,
				"2026-08-09T01:00:00.000Z"));
		assertThat(repository.find(planId, capabilityId).orElseThrow().qtyValue()).isEqualTo(50L);

		repository.upsert(new PlanEntitlementRow(planId, capabilityId, null, 75L, false, null,
				"2026-08-09T02:00:00.000Z"));
		PlanEntitlementRow updated = repository.find(planId, capabilityId).orElseThrow();
		assertThat(updated.qtyValue()).isEqualTo(75L);
		assertThat(repository.findByPlan(planId)).hasSize(1);
	}

	@Test
	void deleteRemovesTheRowMakingThePlanSilentAboutTheCapability() {
		repository.upsert(new PlanEntitlementRow(planId, capabilityId, null, 50L, false, null,
				"2026-08-09T01:00:00.000Z"));

		int deleted = repository.delete(planId, capabilityId);

		assertThat(deleted).isEqualTo(1);
		assertThat(repository.find(planId, capabilityId)).isEmpty();
	}

	@Test
	void findPlanIdsUsingCapabilityListsEveryPlanThatSetsIt() {
		long otherPlanId = planRepository.insert(new PlanRow(null, "enterprise", "Enterprise", null, "ACTIVE", false,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
		repository.upsert(new PlanEntitlementRow(planId, capabilityId, null, 50L, false, null,
				"2026-08-09T01:00:00.000Z"));
		repository.upsert(new PlanEntitlementRow(otherPlanId, capabilityId, null, 100L, false, null,
				"2026-08-09T01:00:00.000Z"));

		List<Long> plans = repository.findPlanIdsUsingCapability(capabilityId);
		assertThat(plans).containsExactlyInAnyOrder(planId, otherPlanId);
	}
}
