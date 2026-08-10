package com.solovis.entitlement.service.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V5 rebuilds {@code audit_event}, which 001's migration rules otherwise forbid because its content
 * is a legal record (002 plan, "Accepted deviations"). The application's own Spring tests only ever
 * run it against an empty table, so the copy itself is never exercised there — which is the half
 * that matters. This drives Flyway directly: migrate to v4, write a populated and cross-referenced
 * audit trail, then migrate to v5 and prove nothing moved.
 */
class V5AuditRebuildTest {

	private String jdbcUrl(Path dir, String name) {
		// foreign_keys=on deliberately: it is what makes DROP TABLE audit_event fail without the
		// pragma dance, so a test with it off would pass while production failed.
		return "jdbc:sqlite:" + dir.resolve(name) + "?journal_mode=WAL&foreign_keys=on";
	}

	private Flyway flyway(String url, String target) {
		return Flyway.configure()
				.dataSource(url, null, null)
				.locations("classpath:db/migration")
				.javaMigrations(new V5__audit_window_transitions())
				.target(target)
				.load();
	}

	/** Rows spanning every pre-002 source and action, plus a snapshot_version pointing into them. */
	private void seedAuditTrail(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO audit_event (occurred_at, actor_kind, actor_id, source, entity_type,
					                         entity_id, action, reason)
					VALUES ('2026-01-01T00:00:00.000Z','PERSON','a.reyes','UI','CAPABILITY','cap_1','CREATE','first'),
					       ('2026-02-01T00:00:00.000Z','PERSON','a.reyes','API','PLAN','plan_1','UPDATE','second'),
					       ('2026-03-01T00:00:00.000Z','SYSTEM','billing','BILLING','ACCOUNT','acct_1','ASSIGN',NULL),
					       ('2026-04-01T00:00:00.000Z','SYSTEM','seed','SEED','OVERRIDE','ovr_1','REMOVE','tidy up')
					""");
			statement.execute("""
					INSERT INTO snapshot_version (published_at, last_audit_seq, delta_json)
					VALUES ('2026-04-01T00:00:00.000Z', (SELECT MAX(seq) FROM audit_event), '{}')
					""");
		}
	}

	private record Row(long seq, String occurredAt, String actorId, String source, String action, String reason) {}

	private List<Row> readAll(Connection connection) throws SQLException {
		var rows = new ArrayList<Row>();
		try (Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery(
						"SELECT seq, occurred_at, actor_id, source, action, reason FROM audit_event ORDER BY seq")) {
			while (rs.next()) {
				rows.add(new Row(rs.getLong("seq"), rs.getString("occurred_at"), rs.getString("actor_id"),
						rs.getString("source"), rs.getString("action"), rs.getString("reason")));
			}
		}
		return rows;
	}

	@Test
	void theRebuildPreservesEveryRowByteForByte(@TempDir Path dir) throws Exception {
		String url = jdbcUrl(dir, "rebuild.db");
		flyway(url, "4").migrate();

		List<Row> before;
		try (Connection connection = DriverManager.getConnection(url)) {
			seedAuditTrail(connection);
			before = readAll(connection);
		}
		assertThat(before).hasSize(4);

		flyway(url, "5").migrate();

		try (Connection connection = DriverManager.getConnection(url)) {
			assertThat(readAll(connection))
					.as("a legal record survives the rebuild unchanged, seq numbering included")
					.isEqualTo(before);
		}
	}

	@Test
	void theRebuildKeepsAutoincrementGoingRatherThanReusingSequences(@TempDir Path dir) throws Exception {
		String url = jdbcUrl(dir, "autoincrement.db");
		flyway(url, "4").migrate();
		try (Connection connection = DriverManager.getConnection(url)) {
			seedAuditTrail(connection);
		}

		flyway(url, "5").migrate();

		try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO audit_event (occurred_at, actor_kind, actor_id, source, entity_type,
					                         entity_id, action)
					VALUES ('2026-05-01T00:00:00.000Z','PERSON','a.reyes','UI','PLAN','plan_2','UPDATE')
					""");
			try (ResultSet rs = statement.executeQuery("SELECT MAX(seq) AS max FROM audit_event")) {
				rs.next();
				assertThat(rs.getLong("max"))
						.as("a rebuilt table must not hand out a seq the record already used")
						.isEqualTo(5);
			}
		}
	}

	@Test
	void theRebuiltTableStillRefusesUpdateAndDelete(@TempDir Path dir) throws Exception {
		String url = jdbcUrl(dir, "triggers.db");
		flyway(url, "4").migrate();
		try (Connection connection = DriverManager.getConnection(url)) {
			seedAuditTrail(connection);
		}

		flyway(url, "5").migrate();

		try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
			assertThatThrownBy(() -> statement.execute("UPDATE audit_event SET reason = 'edited' WHERE seq = 1"))
					.hasMessageContaining("append-only");
			assertThatThrownBy(() -> statement.execute("DELETE FROM audit_event WHERE seq = 1"))
					.as("c32/c33: retention is enforced by the engine, and the rebuild must not quietly drop that")
					.hasMessageContaining("append-only");
		}
	}

	@Test
	void theRebuiltTableAdmitsClockTransitionsAndPairsThemWithTheirEdge(@TempDir Path dir) throws Exception {
		String url = jdbcUrl(dir, "transitions.db");
		flyway(url, "5").migrate();

		try (Connection connection = DriverManager.getConnection(url); Statement statement = connection.createStatement()) {
			statement.execute("""
					INSERT INTO audit_event (occurred_at, actor_kind, actor_id, source, entity_type,
					                         entity_id, action, window_transition)
					VALUES ('2026-06-01T00:00:00.000Z','SYSTEM','clock','CLOCK','OVERRIDE','ovr_1','BEGIN','START'),
					       ('2026-07-01T00:00:00.000Z','SYSTEM','clock','CLOCK','OVERRIDE','ovr_1','END','EXPIRY')
					""");

			// An operator act can never claim to be the clock's...
			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO audit_event (occurred_at, actor_kind, actor_id, source, entity_type,
					                         entity_id, action, window_transition)
					VALUES ('2026-06-01T00:00:00.000Z','PERSON','a.reyes','UI','OVERRIDE','ovr_2','BEGIN','START')
					"""))
					.as("a beginning is made by the passage of time, never by a person (c30)")
					.hasMessageContaining("CHECK constraint failed");

			// ...nor the clock claim an operator's.
			assertThatThrownBy(() -> statement.execute("""
					INSERT INTO audit_event (occurred_at, actor_kind, actor_id, source, entity_type,
					                         entity_id, action)
					VALUES ('2026-06-01T00:00:00.000Z','SYSTEM','clock','CLOCK','OVERRIDE','ovr_2','REMOVE')
					"""))
					.as("'the clock removed this override' is exactly the confusion c30 forbids")
					.hasMessageContaining("CHECK constraint failed");
		}
	}
}
