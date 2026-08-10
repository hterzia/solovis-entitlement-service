# Entitlement Service — Database Layer Implementation Plan

> **Status: complete and merged.** Every task below is implemented and covered by the test suite. The `- [ ]` checkboxes were never ticked back — this file is an archived record of how the `store/` layer was built, not outstanding work. Verify against the code, not the boxes.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the complete database-access layer for `entitlement-service` — the dual-pool SQLite connection configuration and a hand-written `JdbcClient` repository for every table in `V1__baseline.sql` — so that a future service/controller layer has a tested, correct persistence API to build on.

**Architecture:** Two HikariCP-backed `DataSource` beans over the same SQLite file (`entitlementWriteDataSource`, pool size 1; `entitlementReadDataSource`, pool size N) per `research.md` §6, each carrying the required pragmas (`journal_mode=WAL`, `synchronous=NORMAL`, `foreign_keys=ON`, `busy_timeout=5000`, `temp_store=MEMORY`) on its JDBC URL. Flyway migrates via the write datasource at startup (it is the sole writer). One repository class per table (or per tightly-coupled table pair, e.g. `capability` + `capability_tier`), each using hand-written SQL through Spring's `JdbcClient` (no JPA/Hibernate, per `research.md` §5) and explicit `RowMapper` lambdas mapping onto plain Java records — one record per row shape, named `*Row`, living in `store/`.

**Scope decision, stated up front:** every repository method — reads and writes alike — is built against the **write** `JdbcClient`. The read pool is fully configured, pragma-verified and proven (by test) to see the write pool's committed data, so it is ready for a later service layer to route non-transactional list/search endpoints to it. But deciding *which* endpoint gets that treatment is a transaction-boundary decision that belongs to the service layer that doesn't exist yet — routing individual repository calls to the read pool now, with no caller-side transaction demarcation to reason about, would risk a caller seeing its own just-written row disappear (WAL readers on a different connection cannot see an uncommitted write on the write connection). Getting that wrong silently violates criterion 30 (an operator's next read shows their own change). Building on the write client everywhere is the correct, safe default; splitting specific reads to the read pool is a one-line change per call site once the service layer exists to decide where it's safe.

**Tech Stack:** Java 21, Spring Boot 4.0.7 (`spring-boot-starter-jdbc`, `spring-boot-starter-flyway`), Spring Framework `JdbcClient` (`org.springframework.jdbc.core.simple.JdbcClient`), HikariCP, Xerial `sqlite-jdbc` 3.49.1.0, Flyway, JUnit 5 + AssertJ.

## Global Constraints

- Java 21 language level; module is `management/backend/entitlement-service` (Maven reactor member of `entitlement-parent`, version `0.1.0-SNAPSHOT`).
- No JPA/Hibernate. All SQL is hand-written and executed through `JdbcClient`. (`research.md` §5)
- SQLite pragmas, exactly: `journal_mode=WAL`, `synchronous=NORMAL`, `foreign_keys=ON`, `busy_timeout=5000`, `temp_store=MEMORY`. (`data-model.md`, `research.md` §6)
- Write access is a single pooled connection (`maximumPoolSize=1`); a separate, small read pool exists alongside it. (`research.md` §6)
- Flyway migrations are forward-only, already versioned under `src/main/resources/db/migration`; `V1__baseline.sql` already matches `data-model.md`'s physical schema exactly — **do not edit it** as part of this plan.
- Timestamps are ISO-8601 UTC text with milliseconds, e.g. `2026-08-09T14:03:11.482Z`, always supplied by the caller (never computed inside SQL) so writes stay deterministic and testable. (`contracts/README.md`)
- `capability.area` is always derived from `capability.key` (substring before the first `.`) and is never trusted from a caller-supplied value, even inside the DB layer. (`data-model.md` capability validation rules, c40)
- Package root: `com.solovis.entitlement.service`. Config classes go in `config/`; repositories and row records go in `store/`.
- Tests live in `src/test/java/com/solovis/entitlement/service/{config,store}/`, run against the temp-file SQLite database already wired in `src/test/resources/application.yaml` (one unique file per test JVM run).
- Only files touched by this plan may be staged/committed at each checkpoint — the working tree has an unrelated pending deletion (`homepage.html`) and an unrelated untracked directory (`refs/`) that must not be touched.

---

## Task 1: Database configuration properties and dual-pool `SqliteConfig`

**A wrinkle discovered before writing any code, and why it matters to every later task:** the Xerial `sqlite-jdbc` driver reports `SQLException.getSQLState()` as `null` on every constraint violation (verified empirically against 3.49.1.0 — a `CHECK` failure, a trigger `RAISE(ABORT, …)`, a `UNIQUE` violation and a `FOREIGN KEY` violation all come back with `sqlState=null`, `errorCode=19`). Spring's default `SQLErrorCodeSQLExceptionTranslator` classifies exceptions first by vendor-specific error code (looked up by JDBC `DatabaseProductName`, here exactly `"SQLite"`) and only falls back to `SQLState` when no vendor entry exists. Spring's bundled `sql-error-codes.xml` has no `"SQLite"` entry, and `sqlState=null` defeats the fallback too — so without intervention, *every* constraint violation from this database surfaces as `org.springframework.jdbc.UncategorizedSQLException`, not `DataIntegrityViolationException`, which would make constraint violations unrecognizable to any caller (this plan's tests, and later a service layer) that catches `DataIntegrityViolationException` to turn a bad write into a 409/422. The fix is a project-owned `sql-error-codes.xml` on the classpath root, which Spring's `SQLErrorCodesFactory` merges with its built-in table at startup — this is the standard, documented extension point for a database Spring doesn't ship codes for. SQLite's error code `19` (`SQLITE_CONSTRAINT`) is the *base* code returned by `getErrorCode()` for every constraint kind (`CHECK`, `UNIQUE`, `FOREIGN KEY`, `NOT NULL`, trigger `ABORT`) — the driver only distinguishes them in the message text, not in `getErrorCode()` — so one mapping entry covers all of them.

**Files:**
- Create: `management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/config/EntitlementDatabaseProperties.java`
- Create: `management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/config/SqliteConfig.java`
- Create: `management/backend/entitlement-service/src/main/resources/sql-error-codes.xml`
- Modify: `management/backend/entitlement-service/src/main/resources/application.yaml`
- Modify: `management/backend/entitlement-service/src/test/resources/application.yaml`
- Test: `management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/config/SqliteConfigTest.java`

**Interfaces:**
- Consumes: nothing (foundation task).
- Produces: bean names `entitlementWriteDataSource` (`javax.sql.DataSource`, `@Primary`), `entitlementReadDataSource` (`javax.sql.DataSource`), `entitlementWriteJdbcClient` (`org.springframework.jdbc.core.simple.JdbcClient`), `entitlementReadJdbcClient` (`org.springframework.jdbc.core.simple.JdbcClient`), `entitlementTransactionManager` (`org.springframework.transaction.PlatformTransactionManager`, bound to the write datasource). All later tasks inject `entitlementWriteJdbcClient` via `@Qualifier("entitlementWriteJdbcClient")`. Every later task's constraint-violation tests rely on `sql-error-codes.xml` from this task to see `DataIntegrityViolationException` rather than `UncategorizedSQLException`.

- [ ] **Step 1: Replace the generic datasource properties with entitlement-specific ones**

Edit `management/backend/entitlement-service/src/main/resources/application.yaml`:

```yaml
server:
  port: 8081
  address: 0.0.0.0

spring:
  application:
    name: entitlement-service
  flyway:
    enabled: true
    locations: classpath:db/migration
  jackson:
    default-property-inclusion: non_null

entitlement:
  database:
    path: "${ENTITLEMENT_DB_PATH:./data/entitlement.db}"
    write-pool-size: 1
    read-pool-size: 4
    busy-timeout-millis: 5000

management:
  endpoints:
    web:
      exposure:
        include: health,info

springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html
```

Note what disappeared: `spring.datasource.*`. Spring Boot's `DataSourceAutoConfiguration` only activates when no user-defined `DataSource` bean exists; once Step 3 defines `entitlementWriteDataSource`/`entitlementReadDataSource`, that auto-configuration backs off on its own, so leaving stale `spring.datasource.*` keys behind would be dead, misleading configuration.

- [ ] **Step 2: Point the test datasource at the same custom properties**

Edit `management/backend/entitlement-service/src/test/resources/application.yaml`:

```yaml
entitlement:
  database:
    path: "${java.io.tmpdir}/entitlement-test-${random.uuid}.db"
    write-pool-size: 1
    read-pool-size: 4
    busy-timeout-millis: 5000
```

- [ ] **Step 3: Add the custom SQLite error-code mapping**

Create `management/backend/entitlement-service/src/main/resources/sql-error-codes.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="http://www.springframework.org/schema/beans
       https://www.springframework.org/schema/beans/spring-beans.xsd">

    <!--
      Xerial sqlite-jdbc reports SQLException.getSQLState() as null for every constraint
      violation, so Spring's SQLState fallback cannot classify them. Its vendor error code
      IS reliable: SQLite's base result code SQLITE_CONSTRAINT (19) is returned by
      getErrorCode() for every constraint kind (CHECK, UNIQUE, FOREIGN KEY, NOT NULL, and a
      trigger's RAISE(ABORT, ...)) -- the driver only distinguishes them in the message text.
      This file is Spring's documented extension point for a database its built-in
      sql-error-codes.xml does not cover; SQLErrorCodesFactory merges it with the defaults,
      matching on DatabaseMetaData.getDatabaseProductName(), which sqlite-jdbc reports as
      exactly "SQLite" (verified empirically against 3.49.1.0).
    -->
    <bean id="SQLite" class="org.springframework.jdbc.support.SQLErrorCodes">
        <property name="databaseProductName" value="SQLite"/>
        <property name="badSqlGrammarCodes">
            <value>1</value>
        </property>
        <property name="dataIntegrityViolationCodes">
            <value>19</value>
        </property>
    </bean>

</beans>
```

- [ ] **Step 4: Write the failing tests for the properties binding, the two datasources, and error-code translation**

```java
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
```

This last test is the one that actually proves Step 3's `sql-error-codes.xml` works: the insert omits `default_bool`, which fails the "exactly one default variant, matching `value_type`" `CHECK` — without the custom mapping this throws `UncategorizedSQLException` instead, and every later task's constraint tests would be asserting against the wrong exception type.

- [ ] **Step 5: Run the tests to verify they fail**

Run: `./mvnw -pl entitlement-service -am test -Dtest=SqliteConfigTest`
Expected: FAIL — no bean of type `EntitlementDatabaseProperties`/qualified `DataSource` exists yet.

- [ ] **Step 6: Implement `EntitlementDatabaseProperties`**

```java
package com.solovis.entitlement.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "entitlement.database")
public record EntitlementDatabaseProperties(
		String path,
		int writePoolSize,
		int readPoolSize,
		int busyTimeoutMillis) {

	public EntitlementDatabaseProperties {
		if (writePoolSize <= 0) {
			throw new IllegalArgumentException("entitlement.database.write-pool-size must be positive");
		}
		if (readPoolSize <= 0) {
			throw new IllegalArgumentException("entitlement.database.read-pool-size must be positive");
		}
	}

	String jdbcUrl() {
		return "jdbc:sqlite:%s?journal_mode=WAL&synchronous=NORMAL&foreign_keys=on&busy_timeout=%d&temp_store=MEMORY"
				.formatted(path, busyTimeoutMillis);
	}
}
```

- [ ] **Step 7: Implement `SqliteConfig`**

```java
package com.solovis.entitlement.service.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

@Configuration
@EnableConfigurationProperties(EntitlementDatabaseProperties.class)
public class SqliteConfig {

	@Bean
	@Primary
	public DataSource entitlementWriteDataSource(EntitlementDatabaseProperties properties) {
		return dataSource(properties, properties.writePoolSize(), "entitlement-write");
	}

	@Bean
	public DataSource entitlementReadDataSource(EntitlementDatabaseProperties properties) {
		return dataSource(properties, properties.readPoolSize(), "entitlement-read");
	}

	private static DataSource dataSource(EntitlementDatabaseProperties properties, int poolSize, String poolName) {
		HikariConfig config = new HikariConfig();
		config.setJdbcUrl(properties.jdbcUrl());
		config.setDriverClassName("org.sqlite.JDBC");
		config.setMaximumPoolSize(poolSize);
		config.setPoolName(poolName);
		return new HikariDataSource(config);
	}

	@Bean
	public JdbcClient entitlementWriteJdbcClient(
			@Qualifier("entitlementWriteDataSource") DataSource entitlementWriteDataSource) {
		return JdbcClient.create(entitlementWriteDataSource);
	}

	@Bean
	public JdbcClient entitlementReadJdbcClient(
			@Qualifier("entitlementReadDataSource") DataSource entitlementReadDataSource) {
		return JdbcClient.create(entitlementReadDataSource);
	}

	@Bean
	public PlatformTransactionManager entitlementTransactionManager(
			@Qualifier("entitlementWriteDataSource") DataSource entitlementWriteDataSource) {
		return new DataSourceTransactionManager(entitlementWriteDataSource);
	}
}
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./mvnw -pl entitlement-service -am test -Dtest=SqliteConfigTest`
Expected: PASS (5 tests).

- [ ] **Step 9: Run the full existing test suite to check nothing else broke**

Run: `./mvnw -pl entitlement-service -am test`
Expected: PASS, including the pre-existing `EntitlementServiceApplicationTests.contextLoads`.

- [ ] **Step 10: Commit**

```bash
git add management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/config/EntitlementDatabaseProperties.java \
        management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/config/SqliteConfig.java \
        management/backend/entitlement-service/src/main/resources/sql-error-codes.xml \
        management/backend/entitlement-service/src/main/resources/application.yaml \
        management/backend/entitlement-service/src/test/resources/application.yaml \
        management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/config/SqliteConfigTest.java
git commit -m "feat(db): dual-pool SQLite datasource config, pragma verification, and constraint error-code mapping"
```

---

## Task 2: Capability + capability tier repository

**Files:**
- Create: `.../store/CapabilityRow.java`
- Create: `.../store/CapabilityTierRow.java`
- Create: `.../store/CapabilityRepository.java`
- Test: `.../test/.../store/CapabilityRepositoryTest.java`

**Interfaces:**
- Consumes: `entitlementWriteJdbcClient` bean from Task 1.
- Produces: `CapabilityRow`, `CapabilityTierRow` records; `CapabilityRepository` with `long insert(CapabilityRow)`, `Optional<CapabilityRow> findByKey(String)`, `Optional<CapabilityRow> findById(long)`, `boolean existsByKey(String)`, `List<CapabilityRow> findAll(String area, String status, String query)`, `int update(CapabilityRow)`, `boolean retire(long id, String retiredAt, String updatedAt)`, `void insertTier(CapabilityTierRow)`, `List<CapabilityTierRow> findTiers(long capabilityId)`, `Optional<Integer> findMaxOrdinal(long capabilityId)`, `Optional<CapabilityTierRow> findTier(long capabilityId, String tierKey)`.

- [ ] **Step 1: Write the row records**

```java
package com.solovis.entitlement.service.store;

public record CapabilityRow(
		Long id,
		String key,
		String area,
		String displayName,
		String description,
		String valueType,
		Boolean defaultBool,
		Long defaultQty,
		boolean defaultQtyUnlimited,
		String defaultTier,
		boolean hasOffValue,
		Long offQty,
		String offTier,
		String status,
		String retiredAt,
		String createdAt,
		String updatedAt) {
}
```

```java
package com.solovis.entitlement.service.store;

public record CapabilityTierRow(
		long capabilityId,
		String tierKey,
		int ordinal,
		String displayName) {
}
```

- [ ] **Step 2: Write the failing tests for insert/find/existsByKey**

```java
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
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./mvnw -pl entitlement-service -am test -Dtest=CapabilityRepositoryTest`
Expected: FAIL — `CapabilityRepository` does not exist.

- [ ] **Step 4: Implement `CapabilityRepository` (insert, findByKey, findById, existsByKey)**

```java
package com.solovis.entitlement.service.store;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class CapabilityRepository {

	private static final RowMapper<CapabilityRow> ROW_MAPPER = (rs, rowNum) -> new CapabilityRow(
			rs.getLong("id"),
			rs.getString("key"),
			rs.getString("area"),
			rs.getString("display_name"),
			rs.getString("description"),
			rs.getString("value_type"),
			rs.getObject("default_bool") == null ? null : rs.getBoolean("default_bool"),
			rs.getObject("default_qty") == null ? null : rs.getLong("default_qty"),
			rs.getBoolean("default_qty_unlimited"),
			rs.getString("default_tier"),
			rs.getBoolean("has_off_value"),
			rs.getObject("off_qty") == null ? null : rs.getLong("off_qty"),
			rs.getString("off_tier"),
			rs.getString("status"),
			rs.getString("retired_at"),
			rs.getString("created_at"),
			rs.getString("updated_at"));

	private static final RowMapper<CapabilityTierRow> TIER_ROW_MAPPER = (rs, rowNum) -> new CapabilityTierRow(
			rs.getLong("capability_id"),
			rs.getString("tier_key"),
			rs.getInt("ordinal"),
			rs.getString("display_name"));

	private final JdbcClient jdbcClient;

	public CapabilityRepository(@Qualifier("entitlementWriteJdbcClient") JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	static String deriveArea(String key) {
		int dot = key.indexOf('.');
		if (dot <= 0) {
			throw new IllegalArgumentException("Capability key '%s' has no area prefix".formatted(key));
		}
		return key.substring(0, dot);
	}

	public long insert(CapabilityRow row) {
		String area = deriveArea(row.key());
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcClient.sql("""
				INSERT INTO capability (
				    key, area, display_name, description, value_type,
				    default_bool, default_qty, default_qty_unlimited, default_tier,
				    has_off_value, off_qty, off_tier,
				    status, retired_at, created_at, updated_at
				) VALUES (
				    :key, :area, :displayName, :description, :valueType,
				    :defaultBool, :defaultQty, :defaultQtyUnlimited, :defaultTier,
				    :hasOffValue, :offQty, :offTier,
				    :status, :retiredAt, :createdAt, :updatedAt
				)
				""")
				.param("key", row.key())
				.param("area", area)
				.param("displayName", row.displayName())
				.param("description", row.description())
				.param("valueType", row.valueType())
				.param("defaultBool", row.defaultBool())
				.param("defaultQty", row.defaultQty())
				.param("defaultQtyUnlimited", row.defaultQtyUnlimited() ? 1 : 0)
				.param("defaultTier", row.defaultTier())
				.param("hasOffValue", row.hasOffValue() ? 1 : 0)
				.param("offQty", row.offQty())
				.param("offTier", row.offTier())
				.param("status", row.status())
				.param("retiredAt", row.retiredAt())
				.param("createdAt", row.createdAt())
				.param("updatedAt", row.updatedAt())
				.update(keyHolder, "id");
		return keyHolder.getKey().longValue();
	}

	public Optional<CapabilityRow> findByKey(String key) {
		return jdbcClient.sql("SELECT * FROM capability WHERE key = :key")
				.param("key", key)
				.query(ROW_MAPPER)
				.optional();
	}

	public Optional<CapabilityRow> findById(long id) {
		return jdbcClient.sql("SELECT * FROM capability WHERE id = :id")
				.param("id", id)
				.query(ROW_MAPPER)
				.optional();
	}

	public boolean existsByKey(String key) {
		return jdbcClient.sql("SELECT COUNT(*) FROM capability WHERE key = :key")
				.param("key", key)
				.query(Integer.class)
				.single() > 0;
	}
}
```

- [ ] **Step 5: Run to verify these pass**

Run: `./mvnw -pl entitlement-service -am test -Dtest=CapabilityRepositoryTest`
Expected: PASS (5 tests).

- [ ] **Step 6: Write the failing tests for findAll, update, retire**

```java
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
```

(Append these three `@Test` methods to `CapabilityRepositoryTest`.)

- [ ] **Step 7: Run to verify they fail**

Run: `./mvnw -pl entitlement-service -am test -Dtest=CapabilityRepositoryTest`
Expected: FAIL — `findAll`/`update`/`retire` do not exist on `CapabilityRepository`.

- [ ] **Step 8: Implement `findAll`, `update`, `retire`**

Add to `CapabilityRepository`:

```java
	public List<CapabilityRow> findAll(String area, String status, String query) {
		StringBuilder sql = new StringBuilder("SELECT * FROM capability WHERE 1 = 1");
		if (area != null) {
			sql.append(" AND area = :area");
		}
		if (status != null) {
			sql.append(" AND status = :status");
		}
		if (query != null) {
			sql.append(" AND (key LIKE :query OR display_name LIKE :query)");
		}
		sql.append(" ORDER BY area, key");

		var spec = jdbcClient.sql(sql.toString());
		if (area != null) {
			spec = spec.param("area", area);
		}
		if (status != null) {
			spec = spec.param("status", status);
		}
		if (query != null) {
			spec = spec.param("query", "%" + query + "%");
		}
		return spec.query(ROW_MAPPER).list();
	}

	public int update(CapabilityRow row) {
		return jdbcClient.sql("""
				UPDATE capability SET
				    display_name = :displayName,
				    description = :description,
				    default_bool = :defaultBool,
				    default_qty = :defaultQty,
				    default_qty_unlimited = :defaultQtyUnlimited,
				    default_tier = :defaultTier,
				    has_off_value = :hasOffValue,
				    off_qty = :offQty,
				    off_tier = :offTier,
				    updated_at = :updatedAt
				WHERE id = :id
				""")
				.param("id", row.id())
				.param("displayName", row.displayName())
				.param("description", row.description())
				.param("defaultBool", row.defaultBool())
				.param("defaultQty", row.defaultQty())
				.param("defaultQtyUnlimited", row.defaultQtyUnlimited() ? 1 : 0)
				.param("defaultTier", row.defaultTier())
				.param("hasOffValue", row.hasOffValue() ? 1 : 0)
				.param("offQty", row.offQty())
				.param("offTier", row.offTier())
				.param("updatedAt", row.updatedAt())
				.update();
	}

	public boolean retire(long id, String retiredAt, String updatedAt) {
		int rows = jdbcClient.sql("""
				UPDATE capability SET status = 'RETIRED', retired_at = :retiredAt, updated_at = :updatedAt
				WHERE id = :id AND status = 'ACTIVE'
				""")
				.param("id", id)
				.param("retiredAt", retiredAt)
				.param("updatedAt", updatedAt)
				.update();
		return rows == 1;
	}
```

Note the `var spec = ...` reassignment pattern in `findAll`: `JdbcClient.StatementSpec` is fluent and immutable-looking but each `.param(...)` call returns the same builder type, so reassigning is required to keep the builder across the conditional branches — do not mark `spec` `final`.

- [ ] **Step 9: Run to verify all `CapabilityRepositoryTest` tests pass**

Run: `./mvnw -pl entitlement-service -am test -Dtest=CapabilityRepositoryTest`
Expected: PASS (8 tests).

- [ ] **Step 10: Write the failing tests for tier methods**

```java
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
```

- [ ] **Step 11: Run to verify they fail**

Run: `./mvnw -pl entitlement-service -am test -Dtest=CapabilityRepositoryTest`
Expected: FAIL — tier methods do not exist.

- [ ] **Step 12: Implement the tier methods**

Add to `CapabilityRepository`:

```java
	public void insertTier(CapabilityTierRow row) {
		jdbcClient.sql("""
				INSERT INTO capability_tier (capability_id, tier_key, ordinal, display_name)
				VALUES (:capabilityId, :tierKey, :ordinal, :displayName)
				""")
				.param("capabilityId", row.capabilityId())
				.param("tierKey", row.tierKey())
				.param("ordinal", row.ordinal())
				.param("displayName", row.displayName())
				.update();
	}

	public List<CapabilityTierRow> findTiers(long capabilityId) {
		return jdbcClient.sql("SELECT * FROM capability_tier WHERE capability_id = :capabilityId ORDER BY ordinal")
				.param("capabilityId", capabilityId)
				.query(TIER_ROW_MAPPER)
				.list();
	}

	public Optional<Integer> findMaxOrdinal(long capabilityId) {
		// MAX() over zero matching rows still returns exactly one row, with a null value —
		// .single() rejects a null result, but .optional() correctly collapses both "no rows"
		// and "one row, null value" to Optional.empty() (verified empirically against 7.0.8).
		return jdbcClient.sql("SELECT MAX(ordinal) FROM capability_tier WHERE capability_id = :capabilityId")
				.param("capabilityId", capabilityId)
				.query(Integer.class)
				.optional();
	}

	public Optional<CapabilityTierRow> findTier(long capabilityId, String tierKey) {
		return jdbcClient.sql("""
				SELECT * FROM capability_tier WHERE capability_id = :capabilityId AND tier_key = :tierKey
				""")
				.param("capabilityId", capabilityId)
				.param("tierKey", tierKey)
				.query(TIER_ROW_MAPPER)
				.optional();
	}
```

- [ ] **Step 13: Run the full `CapabilityRepositoryTest` class**

Run: `./mvnw -pl entitlement-service -am test -Dtest=CapabilityRepositoryTest`
Expected: PASS (10 tests).

- [ ] **Step 14: Commit**

```bash
git add management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/store/CapabilityRow.java \
        management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/store/CapabilityTierRow.java \
        management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/store/CapabilityRepository.java \
        management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/store/CapabilityRepositoryTest.java
git commit -m "feat(db): capability and capability-tier repository"
```

---

## Task 3: Plan repository

**Files:**
- Create: `.../store/PlanRow.java`
- Create: `.../store/PlanRepository.java`
- Test: `.../test/.../store/PlanRepositoryTest.java`

**Interfaces:**
- Consumes: `entitlementWriteJdbcClient`.
- Produces: `PlanRow`; `PlanRepository` with `long insert(PlanRow)`, `Optional<PlanRow> findByKey(String)`, `Optional<PlanRow> findById(long)`, `List<PlanRow> findAll(String status)`, `int update(long id, String name, String description, String updatedAt)`, `boolean archive(long id, String updatedAt)`, `Optional<PlanRow> findDefault()`, `void clearDefault(String updatedAt)`, `boolean setDefault(long id, String updatedAt)`, `long countAccounts(long planId)`.

- [ ] **Step 1: Write the row record**

```java
package com.solovis.entitlement.service.store;

public record PlanRow(
		Long id,
		String key,
		String name,
		String description,
		String status,
		boolean defaultForNewAccounts,
		String createdAt,
		String updatedAt) {
}
```

- [ ] **Step 2: Write the failing tests**

```java
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

		assertThat(repository.findAll("ACTIVE")).extracting(PlanRow::key).containsExactly("pro");
		assertThat(repository.findAll("ARCHIVED")).extracting(PlanRow::key).containsExactly("legacy");
		assertThat(repository.findAll(null)).hasSize(2);
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
```

- [ ] **Step 3: Run to verify it fails**

Run: `./mvnw -pl entitlement-service -am test -Dtest=PlanRepositoryTest`
Expected: FAIL — `PlanRepository` does not exist.

- [ ] **Step 4: Implement `PlanRepository`**

```java
package com.solovis.entitlement.service.store;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class PlanRepository {

	private static final RowMapper<PlanRow> ROW_MAPPER = (rs, rowNum) -> new PlanRow(
			rs.getLong("id"),
			rs.getString("key"),
			rs.getString("name"),
			rs.getString("description"),
			rs.getString("status"),
			rs.getBoolean("is_default_for_new_accounts"),
			rs.getString("created_at"),
			rs.getString("updated_at"));

	private final JdbcClient jdbcClient;

	public PlanRepository(@Qualifier("entitlementWriteJdbcClient") JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public long insert(PlanRow row) {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcClient.sql("""
				INSERT INTO plan (key, name, description, status, is_default_for_new_accounts, created_at, updated_at)
				VALUES (:key, :name, :description, :status, :defaultForNewAccounts, :createdAt, :updatedAt)
				""")
				.param("key", row.key())
				.param("name", row.name())
				.param("description", row.description())
				.param("status", row.status())
				.param("defaultForNewAccounts", row.defaultForNewAccounts() ? 1 : 0)
				.param("createdAt", row.createdAt())
				.param("updatedAt", row.updatedAt())
				.update(keyHolder, "id");
		return keyHolder.getKey().longValue();
	}

	public Optional<PlanRow> findByKey(String key) {
		return jdbcClient.sql("SELECT * FROM plan WHERE key = :key")
				.param("key", key)
				.query(ROW_MAPPER)
				.optional();
	}

	public Optional<PlanRow> findById(long id) {
		return jdbcClient.sql("SELECT * FROM plan WHERE id = :id")
				.param("id", id)
				.query(ROW_MAPPER)
				.optional();
	}

	public List<PlanRow> findAll(String status) {
		if (status == null) {
			return jdbcClient.sql("SELECT * FROM plan ORDER BY key").query(ROW_MAPPER).list();
		}
		return jdbcClient.sql("SELECT * FROM plan WHERE status = :status ORDER BY key")
				.param("status", status)
				.query(ROW_MAPPER)
				.list();
	}

	public int update(long id, String name, String description, String updatedAt) {
		return jdbcClient.sql("""
				UPDATE plan SET name = :name, description = :description, updated_at = :updatedAt WHERE id = :id
				""")
				.param("id", id)
				.param("name", name)
				.param("description", description)
				.param("updatedAt", updatedAt)
				.update();
	}

	public boolean archive(long id, String updatedAt) {
		int rows = jdbcClient.sql("""
				UPDATE plan SET status = 'ARCHIVED', updated_at = :updatedAt WHERE id = :id AND status = 'ACTIVE'
				""")
				.param("id", id)
				.param("updatedAt", updatedAt)
				.update();
		return rows == 1;
	}

	public Optional<PlanRow> findDefault() {
		return jdbcClient.sql("SELECT * FROM plan WHERE is_default_for_new_accounts = 1")
				.query(ROW_MAPPER)
				.optional();
	}

	public void clearDefault(String updatedAt) {
		jdbcClient.sql("""
				UPDATE plan SET is_default_for_new_accounts = 0, updated_at = :updatedAt
				WHERE is_default_for_new_accounts = 1
				""")
				.param("updatedAt", updatedAt)
				.update();
	}

	public boolean setDefault(long id, String updatedAt) {
		int rows = jdbcClient.sql("""
				UPDATE plan SET is_default_for_new_accounts = 1, updated_at = :updatedAt
				WHERE id = :id AND status = 'ACTIVE'
				""")
				.param("id", id)
				.param("updatedAt", updatedAt)
				.update();
		return rows == 1;
	}

	public long countAccounts(long planId) {
		return jdbcClient.sql("SELECT COUNT(*) FROM account WHERE plan_id = :planId")
				.param("planId", planId)
				.query(Long.class)
				.single();
	}
}
```

`archive` relies on the schema's own `CHECK (status = 'ACTIVE' OR is_default_for_new_accounts = 0)` to reject archiving the current default — SQLite raises a constraint violation, which `JdbcClient` surfaces as `DataIntegrityViolationException`, so the "archive fails on the default plan" test above asserts against that exception type rather than a boolean return, unlike the plain 0-rows-affected case for an already-archived plan.

- [ ] **Step 5: Run to verify all tests pass**

Run: `./mvnw -pl entitlement-service -am test -Dtest=PlanRepositoryTest`
Expected: PASS (6 tests).

- [ ] **Step 6: Commit**

```bash
git add management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/store/PlanRow.java \
        management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/store/PlanRepository.java \
        management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/store/PlanRepositoryTest.java
git commit -m "feat(db): plan repository"
```

---

## Task 4: Plan entitlement repository

**Files:**
- Create: `.../store/PlanEntitlementRow.java`
- Create: `.../store/PlanEntitlementRepository.java`
- Test: `.../test/.../store/PlanEntitlementRepositoryTest.java`

**Interfaces:**
- Consumes: `entitlementWriteJdbcClient`; test fixtures use `CapabilityRepository` and `PlanRepository` from Tasks 2–3 to create parent rows.
- Produces: `PlanEntitlementRow`; `PlanEntitlementRepository` with `void upsert(PlanEntitlementRow)`, `int delete(long planId, long capabilityId)`, `List<PlanEntitlementRow> findByPlan(long planId)`, `Optional<PlanEntitlementRow> find(long planId, long capabilityId)`, `List<Long> findPlanIdsUsingCapability(long capabilityId)`.

- [ ] **Step 1: Write the row record**

```java
package com.solovis.entitlement.service.store;

public record PlanEntitlementRow(
		long planId,
		long capabilityId,
		Boolean boolValue,
		Long qtyValue,
		boolean qtyUnlimited,
		String tierValue,
		String updatedAt) {
}
```

- [ ] **Step 2: Write the failing tests**

```java
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
```

- [ ] **Step 3: Run to verify it fails**

Run: `./mvnw -pl entitlement-service -am test -Dtest=PlanEntitlementRepositoryTest`
Expected: FAIL — `PlanEntitlementRepository` does not exist.

- [ ] **Step 4: Implement `PlanEntitlementRepository`**

```java
package com.solovis.entitlement.service.store;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class PlanEntitlementRepository {

	private static final RowMapper<PlanEntitlementRow> ROW_MAPPER = (rs, rowNum) -> new PlanEntitlementRow(
			rs.getLong("plan_id"),
			rs.getLong("capability_id"),
			rs.getObject("bool_value") == null ? null : rs.getBoolean("bool_value"),
			rs.getObject("qty_value") == null ? null : rs.getLong("qty_value"),
			rs.getBoolean("qty_unlimited"),
			rs.getString("tier_value"),
			rs.getString("updated_at"));

	private final JdbcClient jdbcClient;

	public PlanEntitlementRepository(@Qualifier("entitlementWriteJdbcClient") JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public void upsert(PlanEntitlementRow row) {
		jdbcClient.sql("""
				INSERT INTO plan_entitlement (plan_id, capability_id, bool_value, qty_value, qty_unlimited, tier_value, updated_at)
				VALUES (:planId, :capabilityId, :boolValue, :qtyValue, :qtyUnlimited, :tierValue, :updatedAt)
				ON CONFLICT (plan_id, capability_id) DO UPDATE SET
				    bool_value = excluded.bool_value,
				    qty_value = excluded.qty_value,
				    qty_unlimited = excluded.qty_unlimited,
				    tier_value = excluded.tier_value,
				    updated_at = excluded.updated_at
				""")
				.param("planId", row.planId())
				.param("capabilityId", row.capabilityId())
				.param("boolValue", row.boolValue())
				.param("qtyValue", row.qtyValue())
				.param("qtyUnlimited", row.qtyUnlimited() ? 1 : 0)
				.param("tierValue", row.tierValue())
				.param("updatedAt", row.updatedAt())
				.update();
	}

	public int delete(long planId, long capabilityId) {
		return jdbcClient.sql("DELETE FROM plan_entitlement WHERE plan_id = :planId AND capability_id = :capabilityId")
				.param("planId", planId)
				.param("capabilityId", capabilityId)
				.update();
	}

	public List<PlanEntitlementRow> findByPlan(long planId) {
		return jdbcClient.sql("SELECT * FROM plan_entitlement WHERE plan_id = :planId ORDER BY capability_id")
				.param("planId", planId)
				.query(ROW_MAPPER)
				.list();
	}

	public Optional<PlanEntitlementRow> find(long planId, long capabilityId) {
		return jdbcClient.sql("""
				SELECT * FROM plan_entitlement WHERE plan_id = :planId AND capability_id = :capabilityId
				""")
				.param("planId", planId)
				.param("capabilityId", capabilityId)
				.query(ROW_MAPPER)
				.optional();
	}

	public List<Long> findPlanIdsUsingCapability(long capabilityId) {
		return jdbcClient.sql("""
				SELECT DISTINCT plan_id FROM plan_entitlement WHERE capability_id = :capabilityId
				""")
				.param("capabilityId", capabilityId)
				.query(Long.class)
				.list();
	}
}
```

- [ ] **Step 5: Run to verify all tests pass**

Run: `./mvnw -pl entitlement-service -am test -Dtest=PlanEntitlementRepositoryTest`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/store/PlanEntitlementRow.java \
        management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/store/PlanEntitlementRepository.java \
        management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/store/PlanEntitlementRepositoryTest.java
git commit -m "feat(db): plan entitlement repository"
```

---

## Task 5: Account repository

**Files:**
- Create: `.../store/AccountRow.java`
- Create: `.../store/AccountRepository.java`
- Test: `.../test/.../store/AccountRepositoryTest.java`

**Interfaces:**
- Consumes: `entitlementWriteJdbcClient`; test fixtures use `PlanRepository`.
- Produces: `AccountRow`; `AccountRepository` with `long insert(AccountRow)`, `Optional<AccountRow> findByExternalId(String)`, `Optional<AccountRow> findById(long)`, `boolean existsByExternalId(String)`, `List<AccountRow> search(String q, Long planId, long afterId, int limit)`, `int updatePlanAssignment(long accountId, long planId, String assignedAt, String source, String actor, String updatedAt)`.

- [ ] **Step 1: Write the row record**

```java
package com.solovis.entitlement.service.store;

public record AccountRow(
		Long id,
		String externalId,
		String name,
		long planId,
		String planAssignedAt,
		String planAssignmentSource,
		String planAssignmentActor,
		String status,
		String createdAt,
		String updatedAt) {
}
```

- [ ] **Step 2: Write the failing tests**

```java
package com.solovis.entitlement.service.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class AccountRepositoryTest {

	@Autowired
	AccountRepository repository;

	@Autowired
	PlanRepository planRepository;

	long planId;
	long otherPlanId;

	@BeforeEach
	void seedPlans() {
		planId = planRepository.insert(new PlanRow(null, "pro", "Pro", null, "ACTIVE", false,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
		otherPlanId = planRepository.insert(new PlanRow(null, "enterprise", "Enterprise", null, "ACTIVE", false,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
	}

	private AccountRow account(String externalId, long planId) {
		return new AccountRow(null, externalId, "Northwind Capital", planId,
				"2026-08-09T00:00:00.000Z", "SYSTEM", "billing-sync", "ACTIVE",
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z");
	}

	@Test
	void insertAndFindByExternalIdRoundTrip() {
		long id = repository.insert(account("acct_9931", planId));
		AccountRow saved = repository.findByExternalId("acct_9931").orElseThrow();
		assertThat(saved.id()).isEqualTo(id);
		assertThat(saved.planId()).isEqualTo(planId);
	}

	@Test
	void existsByExternalIdReflectsInsert() {
		assertThat(repository.existsByExternalId("acct_1")).isFalse();
		repository.insert(account("acct_1", planId));
		assertThat(repository.existsByExternalId("acct_1")).isTrue();
	}

	@Test
	void searchFiltersByPlanAndQueryAndPagesByCursor() {
		repository.insert(account("acct_a", planId));
		repository.insert(account("acct_b", planId));
		repository.insert(account("acct_c", otherPlanId));

		assertThat(repository.search(null, planId, 0, 10)).extracting(AccountRow::externalId)
				.containsExactly("acct_a", "acct_b");
		assertThat(repository.search("acct_c", null, 0, 10)).extracting(AccountRow::externalId)
				.containsExactly("acct_c");

		var firstPage = repository.search(null, null, 0, 2);
		assertThat(firstPage).hasSize(2);
		long cursor = firstPage.get(firstPage.size() - 1).id();
		var secondPage = repository.search(null, null, cursor, 2);
		assertThat(secondPage).hasSize(1);
	}

	@Test
	void updatePlanAssignmentMovesTheAccountAndRecordsTheSource() {
		long id = repository.insert(account("acct_9931", planId));

		int rows = repository.updatePlanAssignment(id, otherPlanId, "2026-08-09T05:00:00.000Z",
				"PERSON", "a.reyes", "2026-08-09T05:00:00.000Z");

		assertThat(rows).isEqualTo(1);
		AccountRow saved = repository.findById(id).orElseThrow();
		assertThat(saved.planId()).isEqualTo(otherPlanId);
		assertThat(saved.planAssignmentSource()).isEqualTo("PERSON");
		assertThat(saved.planAssignmentActor()).isEqualTo("a.reyes");
	}
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./mvnw -pl entitlement-service -am test -Dtest=AccountRepositoryTest`
Expected: FAIL — `AccountRepository` does not exist.

- [ ] **Step 4: Implement `AccountRepository`**

```java
package com.solovis.entitlement.service.store;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class AccountRepository {

	private static final RowMapper<AccountRow> ROW_MAPPER = (rs, rowNum) -> new AccountRow(
			rs.getLong("id"),
			rs.getString("external_id"),
			rs.getString("name"),
			rs.getLong("plan_id"),
			rs.getString("plan_assigned_at"),
			rs.getString("plan_assignment_source"),
			rs.getString("plan_assignment_actor"),
			rs.getString("status"),
			rs.getString("created_at"),
			rs.getString("updated_at"));

	private final JdbcClient jdbcClient;

	public AccountRepository(@Qualifier("entitlementWriteJdbcClient") JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public long insert(AccountRow row) {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcClient.sql("""
				INSERT INTO account (
				    external_id, name, plan_id, plan_assigned_at,
				    plan_assignment_source, plan_assignment_actor, status, created_at, updated_at
				) VALUES (
				    :externalId, :name, :planId, :planAssignedAt,
				    :planAssignmentSource, :planAssignmentActor, :status, :createdAt, :updatedAt
				)
				""")
				.param("externalId", row.externalId())
				.param("name", row.name())
				.param("planId", row.planId())
				.param("planAssignedAt", row.planAssignedAt())
				.param("planAssignmentSource", row.planAssignmentSource())
				.param("planAssignmentActor", row.planAssignmentActor())
				.param("status", row.status())
				.param("createdAt", row.createdAt())
				.param("updatedAt", row.updatedAt())
				.update(keyHolder, "id");
		return keyHolder.getKey().longValue();
	}

	public Optional<AccountRow> findByExternalId(String externalId) {
		return jdbcClient.sql("SELECT * FROM account WHERE external_id = :externalId")
				.param("externalId", externalId)
				.query(ROW_MAPPER)
				.optional();
	}

	public Optional<AccountRow> findById(long id) {
		return jdbcClient.sql("SELECT * FROM account WHERE id = :id")
				.param("id", id)
				.query(ROW_MAPPER)
				.optional();
	}

	public boolean existsByExternalId(String externalId) {
		return jdbcClient.sql("SELECT COUNT(*) FROM account WHERE external_id = :externalId")
				.param("externalId", externalId)
				.query(Integer.class)
				.single() > 0;
	}

	public List<AccountRow> search(String q, Long planId, long afterId, int limit) {
		StringBuilder sql = new StringBuilder("SELECT * FROM account WHERE id > :afterId");
		if (planId != null) {
			sql.append(" AND plan_id = :planId");
		}
		if (q != null) {
			sql.append(" AND (external_id LIKE :query OR name LIKE :query)");
		}
		sql.append(" ORDER BY id LIMIT :limit");

		var spec = jdbcClient.sql(sql.toString())
				.param("afterId", afterId)
				.param("limit", limit);
		if (planId != null) {
			spec = spec.param("planId", planId);
		}
		if (q != null) {
			spec = spec.param("query", "%" + q + "%");
		}
		return spec.query(ROW_MAPPER).list();
	}

	public int updatePlanAssignment(long accountId, long planId, String assignedAt, String source, String actor,
			String updatedAt) {
		return jdbcClient.sql("""
				UPDATE account SET
				    plan_id = :planId,
				    plan_assigned_at = :assignedAt,
				    plan_assignment_source = :source,
				    plan_assignment_actor = :actor,
				    updated_at = :updatedAt
				WHERE id = :accountId
				""")
				.param("accountId", accountId)
				.param("planId", planId)
				.param("assignedAt", assignedAt)
				.param("source", source)
				.param("actor", actor)
				.param("updatedAt", updatedAt)
				.update();
	}
}
```

- [ ] **Step 5: Run to verify all tests pass**

Run: `./mvnw -pl entitlement-service -am test -Dtest=AccountRepositoryTest`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/store/AccountRow.java \
        management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/store/AccountRepository.java \
        management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/store/AccountRepositoryTest.java
git commit -m "feat(db): account repository"
```

---

## Task 6: Account override repository

**Files:**
- Create: `.../store/AccountOverrideRow.java`
- Create: `.../store/AccountOverrideRepository.java`
- Test: `.../test/.../store/AccountOverrideRepositoryTest.java`

**Interfaces:**
- Consumes: `entitlementWriteJdbcClient`; test fixtures use `AccountRepository`, `PlanRepository`, `CapabilityRepository`.
- Produces: `AccountOverrideRow`; `AccountOverrideRepository` with `long insert(AccountOverrideRow)`, `Optional<AccountOverrideRow> findById(long)`, `List<AccountOverrideRow> findLive(long accountId, long capabilityId)`, `List<AccountOverrideRow> findLiveForAccount(long accountId)`, `List<AccountOverrideRow> findLiveForCapability(long capabilityId)`, `long countLiveForCapability(long capabilityId)`, `boolean remove(long id, String removedAt, String removedBy, String removedReason)`.

- [ ] **Step 1: Write the row record**

```java
package com.solovis.entitlement.service.store;

public record AccountOverrideRow(
		Long id,
		long accountId,
		long capabilityId,
		String kind,
		Boolean boolValue,
		Long qtyValue,
		boolean qtyUnlimited,
		String tierValue,
		String reason,
		String createdAt,
		String createdBy,
		String createdSource,
		String removedAt,
		String removedBy,
		String removedReason) {
}
```

- [ ] **Step 2: Write the failing tests**

```java
package com.solovis.entitlement.service.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class AccountOverrideRepositoryTest {

	@Autowired
	AccountOverrideRepository repository;

	@Autowired
	AccountRepository accountRepository;

	@Autowired
	PlanRepository planRepository;

	@Autowired
	CapabilityRepository capabilityRepository;

	long accountId;
	long capabilityId;

	@BeforeEach
	void seedParents() {
		long planId = planRepository.insert(new PlanRow(null, "pro", "Pro", null, "ACTIVE", false,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
		accountId = accountRepository.insert(new AccountRow(null, "acct_9931", "Northwind Capital", planId,
				"2026-08-09T00:00:00.000Z", "SYSTEM", "billing-sync", "ACTIVE",
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
		capabilityId = capabilityRepository.insert(new CapabilityRow(null, "reports.monthly", null, "Monthly reports",
				null, "QUANTITY", null, 0L, false, null,
				true, 0L, null, "ACTIVE", null,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
	}

	private AccountOverrideRow grant(long amount, String reason) {
		return new AccountOverrideRow(null, accountId, capabilityId, "GRANT",
				null, amount, false, null, reason,
				"2026-08-09T00:00:00.000Z", "j.okafor", "PERSON",
				null, null, null);
	}

	@Test
	void insertRejectsABlankReason() {
		AccountOverrideRow blank = new AccountOverrideRow(null, accountId, capabilityId, "GRANT",
				null, 200L, false, null, "   ",
				"2026-08-09T00:00:00.000Z", "j.okafor", "PERSON", null, null, null);

		assertThatThrownBy(() -> repository.insert(blank))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
	}

	@Test
	void liveOverridesAreVisibleUntilRemoved() {
		long id = repository.insert(grant(200L, "Renewal concession"));

		assertThat(repository.findLive(accountId, capabilityId)).extracting(AccountOverrideRow::id)
				.containsExactly(id);
		assertThat(repository.findLiveForAccount(accountId)).hasSize(1);
		assertThat(repository.findLiveForCapability(capabilityId)).hasSize(1);
		assertThat(repository.countLiveForCapability(capabilityId)).isEqualTo(1L);

		boolean removed = repository.remove(id, "2026-08-09T06:00:00.000Z", "j.okafor", "Investigation closed");

		assertThat(removed).isTrue();
		assertThat(repository.findLive(accountId, capabilityId)).isEmpty();
		assertThat(repository.findById(id).orElseThrow().removedAt()).isEqualTo("2026-08-09T06:00:00.000Z");
	}

	@Test
	void removingAnAlreadyRemovedOverrideIsANoOp() {
		long id = repository.insert(grant(200L, "Renewal concession"));
		assertThat(repository.remove(id, "2026-08-09T06:00:00.000Z", "j.okafor", "First removal")).isTrue();
		assertThat(repository.remove(id, "2026-08-09T07:00:00.000Z", "j.okafor", "Second attempt")).isFalse();
	}

	@Test
	void anAccountMayHoldMultipleOverridesOnTheSameCapability() {
		repository.insert(grant(200L, "Renewal concession"));
		repository.insert(grant(120L, "Migration goodwill"));

		assertThat(repository.findLive(accountId, capabilityId)).hasSize(2);
	}
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./mvnw -pl entitlement-service -am test -Dtest=AccountOverrideRepositoryTest`
Expected: FAIL — `AccountOverrideRepository` does not exist.

- [ ] **Step 4: Implement `AccountOverrideRepository`**

```java
package com.solovis.entitlement.service.store;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class AccountOverrideRepository {

	private static final RowMapper<AccountOverrideRow> ROW_MAPPER = (rs, rowNum) -> new AccountOverrideRow(
			rs.getLong("id"),
			rs.getLong("account_id"),
			rs.getLong("capability_id"),
			rs.getString("kind"),
			rs.getObject("bool_value") == null ? null : rs.getBoolean("bool_value"),
			rs.getObject("qty_value") == null ? null : rs.getLong("qty_value"),
			rs.getBoolean("qty_unlimited"),
			rs.getString("tier_value"),
			rs.getString("reason"),
			rs.getString("created_at"),
			rs.getString("created_by"),
			rs.getString("created_source"),
			rs.getString("removed_at"),
			rs.getString("removed_by"),
			rs.getString("removed_reason"));

	private final JdbcClient jdbcClient;

	public AccountOverrideRepository(@Qualifier("entitlementWriteJdbcClient") JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public long insert(AccountOverrideRow row) {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcClient.sql("""
				INSERT INTO account_override (
				    account_id, capability_id, kind, bool_value, qty_value, qty_unlimited, tier_value,
				    reason, created_at, created_by, created_source
				) VALUES (
				    :accountId, :capabilityId, :kind, :boolValue, :qtyValue, :qtyUnlimited, :tierValue,
				    :reason, :createdAt, :createdBy, :createdSource
				)
				""")
				.param("accountId", row.accountId())
				.param("capabilityId", row.capabilityId())
				.param("kind", row.kind())
				.param("boolValue", row.boolValue())
				.param("qtyValue", row.qtyValue())
				.param("qtyUnlimited", row.qtyUnlimited() ? 1 : 0)
				.param("tierValue", row.tierValue())
				.param("reason", row.reason())
				.param("createdAt", row.createdAt())
				.param("createdBy", row.createdBy())
				.param("createdSource", row.createdSource())
				.update(keyHolder, "id");
		return keyHolder.getKey().longValue();
	}

	public Optional<AccountOverrideRow> findById(long id) {
		return jdbcClient.sql("SELECT * FROM account_override WHERE id = :id")
				.param("id", id)
				.query(ROW_MAPPER)
				.optional();
	}

	public List<AccountOverrideRow> findLive(long accountId, long capabilityId) {
		return jdbcClient.sql("""
				SELECT * FROM account_override
				WHERE account_id = :accountId AND capability_id = :capabilityId AND removed_at IS NULL
				ORDER BY id
				""")
				.param("accountId", accountId)
				.param("capabilityId", capabilityId)
				.query(ROW_MAPPER)
				.list();
	}

	public List<AccountOverrideRow> findLiveForAccount(long accountId) {
		return jdbcClient.sql("""
				SELECT * FROM account_override WHERE account_id = :accountId AND removed_at IS NULL
				ORDER BY capability_id, id
				""")
				.param("accountId", accountId)
				.query(ROW_MAPPER)
				.list();
	}

	public List<AccountOverrideRow> findLiveForCapability(long capabilityId) {
		return jdbcClient.sql("""
				SELECT * FROM account_override WHERE capability_id = :capabilityId AND removed_at IS NULL
				ORDER BY account_id, id
				""")
				.param("capabilityId", capabilityId)
				.query(ROW_MAPPER)
				.list();
	}

	public long countLiveForCapability(long capabilityId) {
		return jdbcClient.sql("""
				SELECT COUNT(*) FROM account_override WHERE capability_id = :capabilityId AND removed_at IS NULL
				""")
				.param("capabilityId", capabilityId)
				.query(Long.class)
				.single();
	}

	public boolean remove(long id, String removedAt, String removedBy, String removedReason) {
		int rows = jdbcClient.sql("""
				UPDATE account_override SET removed_at = :removedAt, removed_by = :removedBy, removed_reason = :removedReason
				WHERE id = :id AND removed_at IS NULL
				""")
				.param("id", id)
				.param("removedAt", removedAt)
				.param("removedBy", removedBy)
				.param("removedReason", removedReason)
				.update();
		return rows == 1;
	}
}
```

- [ ] **Step 5: Run to verify all tests pass**

Run: `./mvnw -pl entitlement-service -am test -Dtest=AccountOverrideRepositoryTest`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/store/AccountOverrideRow.java \
        management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/store/AccountOverrideRepository.java \
        management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/store/AccountOverrideRepositoryTest.java
git commit -m "feat(db): account override repository"
```

---

## Task 7: Audit event repository

**Files:**
- Create: `.../store/AuditEventRow.java`
- Create: `.../store/AuditEventFilter.java`
- Create: `.../store/AuditEventRepository.java`
- Test: `.../test/.../store/AuditEventRepositoryTest.java`

**Interfaces:**
- Consumes: `entitlementWriteJdbcClient`.
- Produces: `AuditEventRow`; `AuditEventFilter(Long accountId, Long planId, String actorId, String entityType, String occurredFrom, String occurredTo, Long beforeSeq, int limit)`; `AuditEventRepository` with `long insert(AuditEventRow)`, `Optional<AuditEventRow> findBySeq(long)`, `List<AuditEventRow> find(AuditEventFilter)`.

- [ ] **Step 1: Write the row record and filter record**

```java
package com.solovis.entitlement.service.store;

public record AuditEventRow(
		Long seq,
		String occurredAt,
		String actorKind,
		String actorId,
		String source,
		String entityType,
		String entityId,
		String action,
		Long accountId,
		Long planId,
		Long capabilityId,
		String beforeJson,
		String afterJson,
		String reason,
		Long affectedAccountCount) {
}
```

```java
package com.solovis.entitlement.service.store;

/**
 * Every field except {@code limit} is an optional filter; {@code null} means "no constraint on this field".
 * {@code beforeSeq} is the descending-order pagination cursor: only rows with {@code seq < beforeSeq} are returned.
 */
public record AuditEventFilter(
		Long accountId,
		Long planId,
		String actorId,
		String entityType,
		String occurredFrom,
		String occurredTo,
		Long beforeSeq,
		int limit) {
}
```

- [ ] **Step 2: Write the failing tests**

```java
package com.solovis.entitlement.service.store;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class AuditEventRepositoryTest {

	@Autowired
	AuditEventRepository repository;

	private AuditEventRow event(String actorId, Long accountId, String occurredAt) {
		return new AuditEventRow(null, occurredAt, "PERSON", actorId, "UI",
				"PLAN_ENTITLEMENT", "pro", "UPDATE",
				accountId, null, null, null, null, null, null);
	}

	@Test
	void insertReturnsAMonotonicSeqAndFindBySeqRoundTrips() {
		long first = repository.insert(event("a.reyes", null, "2026-08-09T00:00:00.000Z"));
		long second = repository.insert(event("a.reyes", null, "2026-08-09T00:01:00.000Z"));

		assertThat(second).isGreaterThan(first);
		assertThat(repository.findBySeq(first).orElseThrow().actorId()).isEqualTo("a.reyes");
	}

	@Test
	void findFiltersByActorAndPagesDescendingBySeq() {
		repository.insert(event("a.reyes", null, "2026-08-09T00:00:00.000Z"));
		long e2 = repository.insert(event("a.reyes", null, "2026-08-09T00:01:00.000Z"));
		repository.insert(event("s.patel", null, "2026-08-09T00:02:00.000Z"));
		long e4 = repository.insert(event("a.reyes", null, "2026-08-09T00:03:00.000Z"));

		var byActor = repository.find(new AuditEventFilter(null, null, "a.reyes", null, null, null, null, 10));
		assertThat(byActor).extracting(AuditEventRow::seq).containsExactly(e4, e2);

		var firstPage = repository.find(new AuditEventFilter(null, null, "a.reyes", null, null, null, null, 1));
		assertThat(firstPage).extracting(AuditEventRow::seq).containsExactly(e4);

		var secondPage = repository.find(
				new AuditEventFilter(null, null, "a.reyes", null, null, null, firstPage.get(0).seq(), 10));
		assertThat(secondPage).extracting(AuditEventRow::seq).containsExactly(e2);
	}

	@Test
	void findFiltersByAccountAndEntityType() {
		long acct = 42L;
		repository.insert(event("billing-bot", acct, "2026-08-09T00:00:00.000Z"));
		repository.insert(event("billing-bot", null, "2026-08-09T00:01:00.000Z"));

		var byAccount = repository.find(new AuditEventFilter(acct, null, null, null, null, null, null, 10));
		assertThat(byAccount).hasSize(1);

		var byEntityType = repository.find(
				new AuditEventFilter(null, null, null, "PLAN_ENTITLEMENT", null, null, null, 10));
		assertThat(byEntityType).hasSize(2);
	}
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./mvnw -pl entitlement-service -am test -Dtest=AuditEventRepositoryTest`
Expected: FAIL — `AuditEventRepository` does not exist.

- [ ] **Step 4: Implement `AuditEventRepository`**

```java
package com.solovis.entitlement.service.store;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class AuditEventRepository {

	private static final RowMapper<AuditEventRow> ROW_MAPPER = (rs, rowNum) -> new AuditEventRow(
			rs.getLong("seq"),
			rs.getString("occurred_at"),
			rs.getString("actor_kind"),
			rs.getString("actor_id"),
			rs.getString("source"),
			rs.getString("entity_type"),
			rs.getString("entity_id"),
			rs.getString("action"),
			rs.getObject("account_id") == null ? null : rs.getLong("account_id"),
			rs.getObject("plan_id") == null ? null : rs.getLong("plan_id"),
			rs.getObject("capability_id") == null ? null : rs.getLong("capability_id"),
			rs.getString("before_json"),
			rs.getString("after_json"),
			rs.getString("reason"),
			rs.getObject("affected_account_count") == null ? null : rs.getLong("affected_account_count"));

	private final JdbcClient jdbcClient;

	public AuditEventRepository(@Qualifier("entitlementWriteJdbcClient") JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public long insert(AuditEventRow row) {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcClient.sql("""
				INSERT INTO audit_event (
				    occurred_at, actor_kind, actor_id, source, entity_type, entity_id, action,
				    account_id, plan_id, capability_id, before_json, after_json, reason, affected_account_count
				) VALUES (
				    :occurredAt, :actorKind, :actorId, :source, :entityType, :entityId, :action,
				    :accountId, :planId, :capabilityId, :beforeJson, :afterJson, :reason, :affectedAccountCount
				)
				""")
				.param("occurredAt", row.occurredAt())
				.param("actorKind", row.actorKind())
				.param("actorId", row.actorId())
				.param("source", row.source())
				.param("entityType", row.entityType())
				.param("entityId", row.entityId())
				.param("action", row.action())
				.param("accountId", row.accountId())
				.param("planId", row.planId())
				.param("capabilityId", row.capabilityId())
				.param("beforeJson", row.beforeJson())
				.param("afterJson", row.afterJson())
				.param("reason", row.reason())
				.param("affectedAccountCount", row.affectedAccountCount())
				.update(keyHolder, "seq");
		return keyHolder.getKey().longValue();
	}

	public Optional<AuditEventRow> findBySeq(long seq) {
		return jdbcClient.sql("SELECT * FROM audit_event WHERE seq = :seq")
				.param("seq", seq)
				.query(ROW_MAPPER)
				.optional();
	}

	public List<AuditEventRow> find(AuditEventFilter filter) {
		StringBuilder sql = new StringBuilder("SELECT * FROM audit_event WHERE 1 = 1");
		if (filter.accountId() != null) {
			sql.append(" AND account_id = :accountId");
		}
		if (filter.planId() != null) {
			sql.append(" AND plan_id = :planId");
		}
		if (filter.actorId() != null) {
			sql.append(" AND actor_id = :actorId");
		}
		if (filter.entityType() != null) {
			sql.append(" AND entity_type = :entityType");
		}
		if (filter.occurredFrom() != null) {
			sql.append(" AND occurred_at >= :occurredFrom");
		}
		if (filter.occurredTo() != null) {
			sql.append(" AND occurred_at < :occurredTo");
		}
		if (filter.beforeSeq() != null) {
			sql.append(" AND seq < :beforeSeq");
		}
		sql.append(" ORDER BY seq DESC LIMIT :limit");

		var spec = jdbcClient.sql(sql.toString()).param("limit", filter.limit());
		if (filter.accountId() != null) {
			spec = spec.param("accountId", filter.accountId());
		}
		if (filter.planId() != null) {
			spec = spec.param("planId", filter.planId());
		}
		if (filter.actorId() != null) {
			spec = spec.param("actorId", filter.actorId());
		}
		if (filter.entityType() != null) {
			spec = spec.param("entityType", filter.entityType());
		}
		if (filter.occurredFrom() != null) {
			spec = spec.param("occurredFrom", filter.occurredFrom());
		}
		if (filter.occurredTo() != null) {
			spec = spec.param("occurredTo", filter.occurredTo());
		}
		if (filter.beforeSeq() != null) {
			spec = spec.param("beforeSeq", filter.beforeSeq());
		}
		return spec.query(ROW_MAPPER).list();
	}
}
```

- [ ] **Step 5: Run to verify all tests pass**

Run: `./mvnw -pl entitlement-service -am test -Dtest=AuditEventRepositoryTest`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/store/AuditEventRow.java \
        management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/store/AuditEventFilter.java \
        management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/store/AuditEventRepository.java \
        management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/store/AuditEventRepositoryTest.java
git commit -m "feat(db): audit event repository"
```

---

## Task 8: Snapshot version repository

**Files:**
- Create: `.../store/SnapshotVersionRow.java`
- Create: `.../store/SnapshotVersionRepository.java`
- Test: `.../test/.../store/SnapshotVersionRepositoryTest.java`

**Interfaces:**
- Consumes: `entitlementWriteJdbcClient`; test fixtures use `AuditEventRepository` (an audit `seq` must exist before a `snapshot_version` row can reference it via FK).
- Produces: `SnapshotVersionRow`; `SnapshotVersionRepository` with `long insert(SnapshotVersionRow)`, `Optional<SnapshotVersionRow> findLatest()`, `List<SnapshotVersionRow> findSince(long version, int limit)`, `Optional<SnapshotVersionRow> findByVersion(long version)`.

- [ ] **Step 1: Write the row record**

```java
package com.solovis.entitlement.service.store;

public record SnapshotVersionRow(
		Long version,
		String publishedAt,
		long lastAuditSeq,
		String deltaJson) {
}
```

- [ ] **Step 2: Write the failing tests**

```java
package com.solovis.entitlement.service.store;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class SnapshotVersionRepositoryTest {

	@Autowired
	SnapshotVersionRepository repository;

	@Autowired
	AuditEventRepository auditEventRepository;

	private long newAuditSeq() {
		return auditEventRepository.insert(new AuditEventRow(null, "2026-08-09T00:00:00.000Z", "PERSON", "a.reyes",
				"UI", "PLAN_ENTITLEMENT", "pro", "UPDATE", null, null, null, null, null, null, null));
	}

	@Test
	void insertReturnsAMonotonicVersionAndFindByVersionRoundTrips() {
		long v1 = repository.insert(new SnapshotVersionRow(null, "2026-08-09T00:00:00.000Z", newAuditSeq(),
				"{\"changed\":[]}"));
		long v2 = repository.insert(new SnapshotVersionRow(null, "2026-08-09T00:01:00.000Z", newAuditSeq(),
				"{\"changed\":[\"pro\"]}"));

		assertThat(v2).isGreaterThan(v1);
		assertThat(repository.findByVersion(v1).orElseThrow().deltaJson()).isEqualTo("{\"changed\":[]}");
	}

	@Test
	void findLatestReturnsTheHighestVersion() {
		repository.insert(new SnapshotVersionRow(null, "2026-08-09T00:00:00.000Z", newAuditSeq(), "{}"));
		long latest = repository.insert(new SnapshotVersionRow(null, "2026-08-09T00:01:00.000Z", newAuditSeq(), "{}"));

		assertThat(repository.findLatest().orElseThrow().version()).isEqualTo(latest);
	}

	@Test
	void findSinceReturnsOnlyLaterVersionsAscending() {
		long v1 = repository.insert(new SnapshotVersionRow(null, "2026-08-09T00:00:00.000Z", newAuditSeq(), "{}"));
		long v2 = repository.insert(new SnapshotVersionRow(null, "2026-08-09T00:01:00.000Z", newAuditSeq(), "{}"));
		long v3 = repository.insert(new SnapshotVersionRow(null, "2026-08-09T00:02:00.000Z", newAuditSeq(), "{}"));

		assertThat(repository.findSince(v1, 10)).extracting(SnapshotVersionRow::version)
				.containsExactly(v2, v3);
		assertThat(repository.findSince(v1, 1)).extracting(SnapshotVersionRow::version)
				.containsExactly(v2);
	}
}
```

- [ ] **Step 3: Run to verify it fails**

Run: `./mvnw -pl entitlement-service -am test -Dtest=SnapshotVersionRepositoryTest`
Expected: FAIL — `SnapshotVersionRepository` does not exist.

- [ ] **Step 4: Implement `SnapshotVersionRepository`**

```java
package com.solovis.entitlement.service.store;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class SnapshotVersionRepository {

	private static final RowMapper<SnapshotVersionRow> ROW_MAPPER = (rs, rowNum) -> new SnapshotVersionRow(
			rs.getLong("version"),
			rs.getString("published_at"),
			rs.getLong("last_audit_seq"),
			rs.getString("delta_json"));

	private final JdbcClient jdbcClient;

	public SnapshotVersionRepository(@Qualifier("entitlementWriteJdbcClient") JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public long insert(SnapshotVersionRow row) {
		KeyHolder keyHolder = new GeneratedKeyHolder();
		jdbcClient.sql("""
				INSERT INTO snapshot_version (published_at, last_audit_seq, delta_json)
				VALUES (:publishedAt, :lastAuditSeq, :deltaJson)
				""")
				.param("publishedAt", row.publishedAt())
				.param("lastAuditSeq", row.lastAuditSeq())
				.param("deltaJson", row.deltaJson())
				.update(keyHolder, "version");
		return keyHolder.getKey().longValue();
	}

	public Optional<SnapshotVersionRow> findLatest() {
		return jdbcClient.sql("SELECT * FROM snapshot_version ORDER BY version DESC LIMIT 1")
				.query(ROW_MAPPER)
				.optional();
	}

	public List<SnapshotVersionRow> findSince(long version, int limit) {
		return jdbcClient.sql("""
				SELECT * FROM snapshot_version WHERE version > :version ORDER BY version ASC LIMIT :limit
				""")
				.param("version", version)
				.param("limit", limit)
				.query(ROW_MAPPER)
				.list();
	}

	public Optional<SnapshotVersionRow> findByVersion(long version) {
		return jdbcClient.sql("SELECT * FROM snapshot_version WHERE version = :version")
				.param("version", version)
				.query(ROW_MAPPER)
				.optional();
	}
}
```

- [ ] **Step 5: Run to verify all tests pass**

Run: `./mvnw -pl entitlement-service -am test -Dtest=SnapshotVersionRepositoryTest`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/store/SnapshotVersionRow.java \
        management/backend/entitlement-service/src/main/java/com/solovis/entitlement/service/store/SnapshotVersionRepository.java \
        management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/store/SnapshotVersionRepositoryTest.java
git commit -m "feat(db): snapshot version repository"
```

---

## Task 9: Schema-invariant integration tests

Every rule in this task is already enforced by `V1__baseline.sql` (already committed, unmodified by this plan). These tests exist so a future migration that accidentally weakens a constraint fails the build immediately, and so the invariants the rest of the service is designed around (append-only audit, single default plan, tier referential integrity) are proven rather than assumed.

**Files:**
- Test: `.../test/.../store/SchemaInvariantsTest.java`

**Interfaces:**
- Consumes: all repositories from Tasks 2–8, plus raw `entitlementWriteJdbcClient` for statements no repository exposes (e.g. a direct `UPDATE`/`DELETE` against `audit_event`, which no repository method allows by design).

- [ ] **Step 1: Write the failing tests**

```java
package com.solovis.entitlement.service.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class SchemaInvariantsTest {

	@Autowired
	@Qualifier("entitlementWriteJdbcClient")
	JdbcClient jdbcClient;

	@Autowired
	PlanRepository planRepository;

	@Autowired
	CapabilityRepository capabilityRepository;

	@Autowired
	AuditEventRepository auditEventRepository;

	@Autowired
	SnapshotVersionRepository snapshotVersionRepository;

	@Test
	void auditEventRejectsUpdateAndDelete() {
		long seq = auditEventRepository.insert(new AuditEventRow(null, "2026-08-09T00:00:00.000Z", "PERSON",
				"a.reyes", "UI", "PLAN_ENTITLEMENT", "pro", "UPDATE", null, null, null, null, null, null, null));

		assertThatThrownBy(() -> jdbcClient.sql("UPDATE audit_event SET reason = 'tampered' WHERE seq = :seq")
				.param("seq", seq)
				.update())
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThatThrownBy(() -> jdbcClient.sql("DELETE FROM audit_event WHERE seq = :seq")
				.param("seq", seq)
				.update())
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(auditEventRepository.findBySeq(seq)).isPresent();
	}

	@Test
	void onlyOnePlanCanBeTheDefaultAtTheSchemaLevel() {
		long free = planRepository.insert(new PlanRow(null, "free", "Free", null, "ACTIVE", true,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));

		assertThatThrownBy(() -> jdbcClient.sql("""
				INSERT INTO plan (key, name, status, is_default_for_new_accounts, created_at, updated_at)
				VALUES ('pro', 'Pro', 'ACTIVE', 1, '2026-08-09T00:00:00.000Z', '2026-08-09T00:00:00.000Z')
				""").update())
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(planRepository.findById(free).orElseThrow().defaultForNewAccounts()).isTrue();
	}

	@Test
	void planEntitlementTierValueMustBeADeclaredTierOfTheSameCapability() {
		long planId = planRepository.insert(new PlanRow(null, "pro", "Pro", null, "ACTIVE", false,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
		long capabilityId = capabilityRepository.insert(new CapabilityRow(null, "support.level", null,
				"Support level", null, "TIER", null, null, false, "community",
				false, null, null, "ACTIVE", null,
				"2026-08-09T00:00:00.000Z", "2026-08-09T00:00:00.000Z"));
		capabilityRepository.insertTier(new CapabilityTierRow(capabilityId, "community", 0, "Community"));

		assertThatThrownBy(() -> jdbcClient.sql("""
				INSERT INTO plan_entitlement (plan_id, capability_id, tier_value, updated_at)
				VALUES (:planId, :capabilityId, 'platinum', '2026-08-09T00:00:00.000Z')
				""")
				.param("planId", planId)
				.param("capabilityId", capabilityId)
				.update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void accountOverrideReasonMustBeNonBlankAfterTrimming() {
		assertThatThrownBy(() -> jdbcClient.sql("""
				INSERT INTO account_override (account_id, capability_id, kind, bool_value, reason, created_at, created_by, created_source)
				VALUES (1, 1, 'GRANT', 1, '   ', '2026-08-09T00:00:00.000Z', 'j.okafor', 'PERSON')
				""").update())
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void snapshotVersionMustReferenceARealAuditSeq() {
		assertThatThrownBy(() -> snapshotVersionRepository.insert(
				new SnapshotVersionRow(null, "2026-08-09T00:00:00.000Z", 999_999L, "{}")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}
}
```

- [ ] **Step 2: Run to verify it fails or passes for the wrong reason**

Run: `./mvnw -pl entitlement-service -am test -Dtest=SchemaInvariantsTest`
Expected: some tests may already pass by construction (the schema is already correct), but confirm each one fails closed if you temporarily comment out the corresponding `CHECK`/trigger/`FOREIGN KEY` clause in a scratch copy of the migration — this task's job is to lock in behavior, not add it. Do not edit `V1__baseline.sql`.

Since the schema already satisfies every assertion here, this task's "red" step is a manual, temporary sabotage-and-revert of a local scratch copy (never the committed migration) to confirm each test actually exercises the constraint it claims to, rather than passing vacuously. Record that this was done; do not leave the sabotaged copy in the tree.

- [ ] **Step 3: Run the full suite to confirm everything passes together**

Run: `./mvnw -pl entitlement-service -am test`
Expected: PASS — every repository test class plus `SchemaInvariantsTest` plus `SqliteConfigTest` plus the pre-existing `EntitlementServiceApplicationTests`.

- [ ] **Step 4: Commit**

```bash
git add management/backend/entitlement-service/src/test/java/com/solovis/entitlement/service/store/SchemaInvariantsTest.java
git commit -m "test(db): lock in schema-enforced invariants (audit append-only, single default plan, tier FK, override reason, snapshot FK)"
```

---

## Out of scope, deliberately

Recorded so a future worker doesn't assume these were forgotten:

- **`entitlement-core` domain model / resolver** — a different module and a different engineer's scope; this plan's row records are intentionally storage-shaped (raw nullable columns), not the sealed `EntitlementValue` domain type.
- **Admin/decision controllers and DTOs** — consume these repositories; not built here.
- **Transaction demarcation across a write + its audit event + the snapshot swap** — `entitlementTransactionManager` is provided so a service layer can wrap that in one `@Transactional` method; this plan does not itself compose multi-repository writes into one transaction, because there is no calling service yet to decide the boundaries.
- **`DemoDataSeeder`** (100k accounts for the load demo) — needs the domain model and business rules from `entitlement-core` to generate valid data; pure bulk-insert performance can be revisited once that exists.
- **Routing specific read endpoints to `entitlementReadJdbcClient`** — the bean exists and is tested; wiring it into individual repository methods is deferred to whoever builds the service layer and can reason about which endpoints are safe to serve from it (see the scope decision at the top of this plan).
