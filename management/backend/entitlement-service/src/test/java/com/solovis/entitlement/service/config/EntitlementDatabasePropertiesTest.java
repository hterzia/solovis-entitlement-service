package com.solovis.entitlement.service.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Plain unit test, no Spring context: EntitlementDatabaseProperties is a record whose invariants
// live entirely in its compact constructor, so there's nothing to gain from booting the app.
class EntitlementDatabasePropertiesTest {

	@Test
	void writePoolSizeOfOneIsAccepted() {
		var properties = new EntitlementDatabaseProperties("/tmp/entitlement-test.db", 1, 4, 5000);
		assertThat(properties.writePoolSize()).isEqualTo(1);
	}

	@Test
	void writePoolSizeAboveOneIsRejected() {
		assertThatThrownBy(() -> new EntitlementDatabaseProperties("/tmp/entitlement-test.db", 2, 4, 5000))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("entitlement.database.write-pool-size must be exactly 1")
				.hasMessageContaining("SnapshotPublisher");
	}

	@Test
	void writePoolSizeOfZeroOrLessIsRejected() {
		assertThatThrownBy(() -> new EntitlementDatabaseProperties("/tmp/entitlement-test.db", 0, 4, 5000))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("entitlement.database.write-pool-size must be positive");
	}

	@Test
	void readPoolSizeOfZeroOrLessIsRejected() {
		assertThatThrownBy(() -> new EntitlementDatabaseProperties("/tmp/entitlement-test.db", 1, 0, 5000))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("entitlement.database.read-pool-size must be positive");
	}
}
