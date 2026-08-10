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

	/**
	 * Drops delta rows published before {@code cutoff}, never touching {@code keepVersion}.
	 *
	 * <p>Unlike {@code audit_event}, which is append-only and protected by triggers, versions are a
	 * transport concern: once no replica can still be asking for one, the row is dead weight
	 * (data-model.md, {@code snapshot_version}). The {@code keepVersion} guard exists because the
	 * feed reads {@code published_at} for the <em>current</em> version on every poll — pruning that
	 * row on a quiet estate would make an unchanged version report a new publish time each time it
	 * was asked for.
	 *
	 * @return how many rows were removed
	 */
	public int deleteOlderThan(String cutoff, long keepVersion) {
		return jdbcClient.sql("""
				DELETE FROM snapshot_version WHERE published_at < :cutoff AND version <> :keepVersion
				""")
				.param("cutoff", cutoff)
				.param("keepVersion", keepVersion)
				.update();
	}
}
