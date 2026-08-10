package com.solovis.entitlement.service.store;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class PlanRepositoryTest {

	@Autowired
	PlanRepository repository;

	private static PlanRow plan(String key) {
		return new PlanRow(null, key, "Plan " + key, null, "ACTIVE", false,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z");
	}

	@Test
	void insertAndFindByKeyRoundTrip() {
		long id = repository.insert(plan("pro"));
		PlanRow saved = repository.findByKey("pro").orElseThrow();
		assertThat(saved.id()).isEqualTo(id);
		assertThat(saved.status()).isEqualTo("ACTIVE");
		assertThat(saved.defaultForNewAccounts()).isFalse();
	}

	@Test
	void findAllFiltersByStatus() {
		long proId = repository.insert(plan("pro"));
		repository.insert(plan("legacy"));
		repository.archive(repository.findByKey("legacy").orElseThrow().id(), "2026-08-09T01:00:00.000Z");

		assertThat(repository.findAll("ACTIVE")).extracting(PlanRow::key).contains("pro").doesNotContain("legacy");
		assertThat(repository.findAll("ARCHIVED")).extracting(PlanRow::key).contains("legacy").doesNotContain("pro");
		assertThat(proId).isPositive();
	}

	@Test
	void updateChangesNameAndDescriptionOnly() {
		long id = repository.insert(plan("pro"));
		int rows = repository.update(id, "Professional", "Now with a description", "2026-08-09T02:00:00.000Z");
		assertThat(rows).isEqualTo(1);
		PlanRow saved = repository.findById(id).orElseThrow();
		assertThat(saved.name()).isEqualTo("Professional");
		assertThat(saved.description()).isEqualTo("Now with a description");
	}

	@Test
	void archiveFailsOnAPlanThatIsStillTheDefault() {
		long id = repository.insert(plan("free"));
		repository.setDefault(id, "2026-08-09T00:00:00.000Z");

		assertThatThrownBy(() -> repository.archive(id, "2026-08-09T03:00:00.000Z"))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
	}

	@Test
	void onlyOnePlanCanBeTheDesignatedDefault() {
		long free = repository.insert(plan("free"));
		long pro = repository.insert(plan("pro"));

		assertThat(repository.setDefault(free, "2026-08-09T00:00:00.000Z")).isTrue();
		assertThat(repository.findDefault().orElseThrow().key()).isEqualTo("free");

		repository.clearDefault("2026-08-09T01:00:00.000Z");
		assertThat(repository.findDefault()).isEmpty();

		assertThat(repository.setDefault(pro, "2026-08-09T02:00:00.000Z")).isTrue();
		assertThat(repository.findDefault().orElseThrow().key()).isEqualTo("pro");
	}

	@Test
	void countAccountsIsZeroForANewPlan() {
		long id = repository.insert(plan("empty"));
		assertThat(repository.countAccounts(id)).isZero();
	}
}
