package com.solovis.entitlement.service.store;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class CapabilityRepositoryTest {

	@Autowired
	CapabilityRepository repository;

	private static CapabilityRow switchCapability(String key) {
		return new CapabilityRow(null, key, null, "Display " + key, "A description",
				"SWITCH", false, null, false, null,
				false, null, null,
				"ACTIVE", null,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z");
	}

	@Test
	void insertDerivesAreaFromKeyRegardlessOfWhatTheRowCarries() {
		CapabilityRow row = new CapabilityRow(null, "export.parquet", "ignored-area", "Parquet export",
				null, "SWITCH", false, null, false, null,
				false, null, null, "ACTIVE", null,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z");

		long id = repository.insert(row);

		CapabilityRow saved = repository.findById(id).orElseThrow();
		assertThat(saved.area()).isEqualTo("export");
		assertThat(saved.key()).isEqualTo("export.parquet");
	}

	@Test
	void insertRejectsAKeyWithNoDot() {
		CapabilityRow row = switchCapability("nodothere");
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> repository.insert(row))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void findByKeyReturnsEmptyWhenAbsent() {
		assertThat(repository.findByKey("does.not-exist")).isEmpty();
	}

	@Test
	void existsByKeyReflectsInsert() {
		assertThat(repository.existsByKey("reports.monthly")).isFalse();
		repository.insert(switchCapability("reports.monthly"));
		assertThat(repository.existsByKey("reports.monthly")).isTrue();
	}

	@Test
	void insertPersistsAllValueVariants() {
		long switchId = repository.insert(switchCapability("api.access"));
		assertThat(repository.findById(switchId).orElseThrow().defaultBool()).isFalse();

		CapabilityRow quantity = new CapabilityRow(null, "seats.count", null, "Seats", null,
				"QUANTITY", null, null, true, null,
				true, 0L, null, "ACTIVE", null,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z");
		long qtyId = repository.insert(quantity);
		CapabilityRow savedQty = repository.findById(qtyId).orElseThrow();
		assertThat(savedQty.defaultQtyUnlimited()).isTrue();
		assertThat(savedQty.hasOffValue()).isTrue();
		assertThat(savedQty.offQty()).isZero();
	}

	@Test
	void findAllFiltersByAreaStatusAndSearchTerm() {
		repository.insert(switchCapability("reports.monthly"));
		repository.insert(switchCapability("reports.annual"));
		long exportId = repository.insert(switchCapability("export.parquet"));
		repository.retire(exportId, "2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z");

		List<CapabilityRow> reportsOnly = repository.findAll("reports", null, null);
		assertThat(reportsOnly).extracting(CapabilityRow::key)
				.containsExactlyInAnyOrder("reports.monthly", "reports.annual");

		List<CapabilityRow> retiredOnly = repository.findAll(null, "RETIRED", null);
		assertThat(retiredOnly).extracting(CapabilityRow::key).containsExactly("export.parquet");

		List<CapabilityRow> searched = repository.findAll(null, null, "monthly");
		assertThat(searched).extracting(CapabilityRow::key).containsExactly("reports.monthly");
	}

	@Test
	void updateChangesDisplayFieldsAndDefaultButNeverValueType() {
		long id = repository.insert(switchCapability("api.access"));
		CapabilityRow original = repository.findById(id).orElseThrow();

		CapabilityRow patched = new CapabilityRow(id, original.key(), original.area(),
				"New display name", "New description", original.valueType(),
				true, null, false, null,
				false, null, null,
				original.status(), original.retiredAt(), original.createdAt(),
				"2026-08-09T01:00:00.000Z");
		int rows = repository.update(patched);

		assertThat(rows).isEqualTo(1);
		CapabilityRow saved = repository.findById(id).orElseThrow();
		assertThat(saved.displayName()).isEqualTo("New display name");
		assertThat(saved.defaultBool()).isTrue();
		assertThat(saved.valueType()).isEqualTo("SWITCH");
	}

	@Test
	void retireIsOneWayAndReportsWhetherItApplied() {
		long id = repository.insert(switchCapability("export.csv"));

		assertThat(repository.retire(id, "2026-08-09T02:00:00.000Z", "2026-08-09T02:00:00.000Z")).isTrue();
		assertThat(repository.findById(id).orElseThrow().status()).isEqualTo("RETIRED");

		assertThat(repository.retire(id, "2026-08-09T03:00:00.000Z", "2026-08-09T03:00:00.000Z")).isFalse();
	}

	@Test
	void tiersRoundTripOrderedByOrdinal() {
		long tierCapabilityId = repository.insert(new CapabilityRow(null, "support.level", null, "Support level",
				null, "TIER", null, null, false, "community",
				false, null, null, "ACTIVE", null,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));

		repository.insertTier(new CapabilityTierRow(tierCapabilityId, "community", 0, "Community"));
		repository.insertTier(new CapabilityTierRow(tierCapabilityId, "gold", 1, "Gold"));

		List<CapabilityTierRow> tiers = repository.findTiers(tierCapabilityId);
		assertThat(tiers).extracting(CapabilityTierRow::tierKey).containsExactly("community", "gold");

		assertThat(repository.findMaxOrdinal(tierCapabilityId)).contains(1);
		assertThat(repository.findTier(tierCapabilityId, "gold")).isPresent();
		assertThat(repository.findTier(tierCapabilityId, "platinum")).isEmpty();
	}

	@Test
	void findMaxOrdinalIsEmptyWithNoTiers() {
		long id = repository.insert(switchCapability("no.tiers"));
		assertThat(repository.findMaxOrdinal(id)).isEmpty();
	}
}
