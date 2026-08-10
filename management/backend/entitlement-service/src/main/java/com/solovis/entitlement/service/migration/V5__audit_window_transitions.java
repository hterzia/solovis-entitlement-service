package com.solovis.entitlement.service.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Widens {@code audit_event}'s two CHECK constraints so the passage of time can be recorded as
 * itself: {@code source} admits {@code 'CLOCK'}, and {@code action} admits {@code 'BEGIN'} and
 * {@code 'END'} (002 c30). Adds nullable {@code window_transition}.
 *
 * <h2>Why this is Java rather than SQL</h2>
 *
 * SQLite cannot alter a CHECK in place, so this is the twelve-step table rebuild. Two facts force
 * it out of Flyway's transaction:
 *
 * <ul>
 *   <li>{@code snapshot_version.last_audit_seq} is a foreign key into {@code audit_event}, and the
 *       connection runs with {@code foreign_keys=on}, so dropping the old table with the pragma
 *       enabled is a constraint violation.
 *   <li>{@code PRAGMA foreign_keys} is <em>a no-op inside a transaction</em>. Flyway wraps SQL
 *       migrations in one, so the pragma would silently do nothing and the drop would fail.
 * </ul>
 *
 * Hence {@link #canExecuteInTransaction()} returning {@code false}, and hence the explicit
 * transaction below: the pragma is set outside it, the rebuild runs inside it, and a failed
 * verification rolls the whole rebuild back rather than leaving the table half-copied.
 *
 * <h2>Why it verifies</h2>
 *
 * 001's migration rules say {@code audit_event} may only ever gain columns, because its content is
 * a legal record. This is a recorded deviation (002 plan, "Accepted deviations"), and the price of
 * the deviation is that the copy proves itself: row count and a checksum over {@code seq} and
 * {@code occurred_at} are taken before and after, and any mismatch aborts. The undo is a Litestream
 * restore to a timestamp recorded before the revision ships — not a local file copy, because the
 * instance disk is ephemeral.
 */
@Component
public class V5__audit_window_transitions extends BaseJavaMigration {

    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        boolean autoCommitWas = connection.getAutoCommit();

        try (Statement statement = connection.createStatement()) {
            // Outside any transaction, or SQLite ignores it without saying so.
            connection.setAutoCommit(true);
            statement.execute("PRAGMA foreign_keys=OFF");

            Fingerprint before = fingerprint(statement);

            connection.setAutoCommit(false);
            try {
                rebuild(statement);

                Fingerprint after = fingerprint(statement);
                if (!before.equals(after)) {
                    throw new IllegalStateException(
                        "audit_event rebuild altered the record and has been rolled back. Before: "
                            + before + ". After: " + after + ".");
                }
                requireNoDanglingReferences(statement);

                connection.commit();
                System.out.println("V5: audit_event rebuilt and verified — " + after);
            } catch (Exception failure) {
                connection.rollback();
                throw failure;
            } finally {
                connection.setAutoCommit(true);
            }

            statement.execute("PRAGMA foreign_keys=ON");
        } finally {
            connection.setAutoCommit(autoCommitWas);
        }
    }

    private void rebuild(Statement statement) throws Exception {
        statement.execute("""
            CREATE TABLE audit_event_rebuilt (
                seq                    INTEGER PRIMARY KEY AUTOINCREMENT,
                occurred_at            TEXT    NOT NULL,
                actor_kind             TEXT    NOT NULL CHECK (actor_kind IN ('PERSON','SYSTEM')),
                actor_id               TEXT    NOT NULL,
                source                 TEXT    NOT NULL
                                         CHECK (source IN ('UI','BILLING','API','SEED','CLOCK')),
                entity_type            TEXT    NOT NULL
                                         CHECK (entity_type IN ('CAPABILITY','CAPABILITY_TIER','PLAN',
                                                                'PLAN_ENTITLEMENT','ACCOUNT','ACCOUNT_PLAN',
                                                                'DEFAULT_PLAN','OVERRIDE')),
                entity_id              TEXT    NOT NULL,
                action                 TEXT    NOT NULL
                                         CHECK (action IN ('CREATE','UPDATE','RETIRE','ARCHIVE',
                                                           'REMOVE','ASSIGN','DESIGNATE','BEGIN','END')),
                account_id             INTEGER REFERENCES account(id),
                plan_id                INTEGER REFERENCES plan(id),
                capability_id          INTEGER REFERENCES capability(id),
                before_json            TEXT,
                after_json             TEXT,
                reason                 TEXT,
                affected_account_count INTEGER,
                -- 'START' or 'EXPIRY': which edge of the window this row records. Null for every
                -- row a person caused, which is all of them before this migration.
                window_transition      TEXT
                                         CHECK (window_transition IS NULL
                                                OR window_transition IN ('START','EXPIRY')),
                -- A transition is the clock's doing and nobody else's; an operator act is never
                -- the clock's. Stated as a constraint so the two can never be confused in the
                -- record itself, which is what c30 asks for.
                CHECK ((action IN ('BEGIN','END')) = (source = 'CLOCK')),
                CHECK ((action IN ('BEGIN','END')) = (window_transition IS NOT NULL))
            )
            """);

        // Columns named explicitly: a SELECT * copy would depend on column order surviving, and
        // this runs once against a table nobody may rewrite afterwards.
        statement.execute("""
            INSERT INTO audit_event_rebuilt (
                seq, occurred_at, actor_kind, actor_id, source, entity_type, entity_id, action,
                account_id, plan_id, capability_id, before_json, after_json, reason, affected_account_count)
            SELECT
                seq, occurred_at, actor_kind, actor_id, source, entity_type, entity_id, action,
                account_id, plan_id, capability_id, before_json, after_json, reason, affected_account_count
            FROM audit_event
            """);

        statement.execute("DROP TABLE audit_event");
        statement.execute("ALTER TABLE audit_event_rebuilt RENAME TO audit_event");

        statement.execute("CREATE INDEX ix_audit_account    ON audit_event(account_id,    seq DESC)");
        statement.execute("CREATE INDEX ix_audit_plan       ON audit_event(plan_id,       seq DESC)");
        statement.execute("CREATE INDEX ix_audit_actor      ON audit_event(actor_id,      seq DESC)");
        statement.execute("CREATE INDEX ix_audit_time       ON audit_event(occurred_at)");
        statement.execute("CREATE INDEX ix_audit_capability ON audit_event(capability_id, seq DESC)");

        // Recreated, not optional: append-only is enforced by the engine rather than by convention,
        // and a rebuild that dropped them would quietly remove the guarantee (c32, c33).
        statement.execute("""
            CREATE TRIGGER trg_audit_no_update BEFORE UPDATE ON audit_event
            BEGIN SELECT RAISE(ABORT, 'audit_event is append-only'); END
            """);
        statement.execute("""
            CREATE TRIGGER trg_audit_no_delete BEFORE DELETE ON audit_event
            BEGIN SELECT RAISE(ABORT, 'audit_event is append-only'); END
            """);
    }

    /** Row count plus a checksum over the two columns that identify a row and when it happened. */
    private Fingerprint fingerprint(Statement statement) throws Exception {
        try (ResultSet rs = statement.executeQuery("""
                SELECT COUNT(*)                                  AS rows,
                       COALESCE(SUM(seq), 0)                     AS seq_sum,
                       COALESCE(MAX(seq), 0)                     AS seq_max,
                       COALESCE(SUM(LENGTH(occurred_at)), 0)     AS occurred_len,
                       COALESCE(COUNT(DISTINCT occurred_at), 0)  AS occurred_distinct
                FROM audit_event
                """)) {
            rs.next();
            return new Fingerprint(rs.getLong("rows"), rs.getLong("seq_sum"), rs.getLong("seq_max"),
                rs.getLong("occurred_len"), rs.getLong("occurred_distinct"));
        }
    }

    /**
     * The pragma was off for the rebuild, so nothing checked {@code snapshot_version.last_audit_seq}
     * on the way through. This asks explicitly, while the transaction can still be rolled back.
     */
    private void requireNoDanglingReferences(Statement statement) throws Exception {
        try (ResultSet rs = statement.executeQuery("""
                SELECT COUNT(*) AS dangling FROM snapshot_version sv
                WHERE NOT EXISTS (SELECT 1 FROM audit_event ae WHERE ae.seq = sv.last_audit_seq)
                """)) {
            rs.next();
            long dangling = rs.getLong("dangling");
            if (dangling > 0) {
                throw new IllegalStateException(
                    "audit_event rebuild left " + dangling + " snapshot_version rows pointing at a "
                        + "seq that no longer exists; rolled back.");
            }
        }
    }

    private record Fingerprint(long rows, long seqSum, long seqMax, long occurredLength, long occurredDistinct) {
        @Override
        public String toString() {
            return "rows=" + rows + " seqSum=" + seqSum + " seqMax=" + seqMax
                + " occurredLength=" + occurredLength + " occurredDistinct=" + occurredDistinct;
        }
    }
}
