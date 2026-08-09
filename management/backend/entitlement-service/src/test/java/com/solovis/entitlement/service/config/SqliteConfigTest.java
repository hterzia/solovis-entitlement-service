package com.solovis.entitlement.service.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SqliteConfigTest {

	@Autowired
	EntitlementDatabaseProperties properties;

	@Autowired
	@Qualifier("entitlementWriteDataSource")
	DataSource writeDataSource;

	@Autowired
	@Qualifier("entitlementReadDataSource")
	DataSource readDataSource;

	@Autowired
	@Qualifier("entitlementWriteJdbcClient")
	JdbcClient writeJdbcClient;

	@Autowired
	@Qualifier("entitlementReadJdbcClient")
	JdbcClient readJdbcClient;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Test
	void propertiesAreBound() {
		assertThat(properties.path()).contains("entitlement-test-");
		assertThat(properties.writePoolSize()).isEqualTo(1);
		assertThat(properties.readPoolSize()).isEqualTo(4);
		assertThat(properties.busyTimeoutMillis()).isEqualTo(5000);
	}

	@Test
	void writeAndReadDataSourcesAreDistinctPoolsOverTheSameFile() throws Exception {
		assertThat(writeDataSource).isNotSameAs(readDataSource);

		try (Connection c = writeDataSource.getConnection(); Statement s = c.createStatement()) {
			ResultSet rs = s.executeQuery("PRAGMA journal_mode");
			rs.next();
			assertThat(rs.getString(1)).isEqualToIgnoringCase("wal");

			rs = s.executeQuery("PRAGMA foreign_keys");
			rs.next();
			assertThat(rs.getInt(1)).isEqualTo(1);
		}

		try (Connection c = readDataSource.getConnection(); Statement s = c.createStatement()) {
			ResultSet rs = s.executeQuery("PRAGMA journal_mode");
			rs.next();
			assertThat(rs.getString(1)).isEqualToIgnoringCase("wal");
		}
	}

	@Test
	void flywayHasMigratedTheWriteDatabase() {
		Integer tableCount = writeJdbcClient
				.sql("SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'capability'")
				.query(Integer.class)
				.single();
		assertThat(tableCount).isEqualTo(1);
	}

	@Test
	void aRowCommittedOnTheWritePoolIsVisibleOnTheReadPool() {
		String key = "config-test.visibility-" + System.nanoTime();
		writeJdbcClient.sql("""
				INSERT INTO capability (key, area, display_name, value_type, default_bool, created_at, updated_at)
				VALUES (:key, 'config-test', 'Visibility probe', 'SWITCH', 0, '2026-08-09T00:00:00.000Z', '2026-08-09T00:00:00.000Z')
				""")
				.param("key", key)
				.update();

		try {
			Long count = readJdbcClient.sql("SELECT COUNT(*) FROM capability WHERE key = :key")
					.param("key", key)
					.query(Long.class)
					.single();
			assertThat(count).isEqualTo(1L);
		} finally {
			writeJdbcClient.sql("DELETE FROM capability WHERE key = :key").param("key", key).update();
		}
	}

	@Test
	void constraintViolationsTranslateToDataIntegrityViolationException() {
		assertThatThrownBy(() -> writeJdbcClient.sql("""
				INSERT INTO capability (key, area, display_name, value_type, created_at, updated_at)
				VALUES ('config-test.bad-default', 'config-test', 'Missing default', 'SWITCH',
				        '2026-08-09T00:00:00.000Z', '2026-08-09T00:00:00.000Z')
				""").update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}
