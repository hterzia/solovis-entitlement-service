package com.solovis.entitlement.service.store;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

// c31's load-bearing fact: entitlementReadTransactionManager must give a real WAL read-snapshot,
// not just a separate connection pool. SQLite opens that snapshot at the transaction's first
// statement and holds it across every later statement in the same transaction, regardless of
// commits landing on the write connection meanwhile. This test proves that directly, with the
// read transaction still open while a concurrent write commits, before anything in this plan is
// allowed to build a DAO or view on top of the read transaction manager.
//
// Not @Transactional, same reasoning as SnapshotPublisherTest: the writer thread needs a real
// commit on entitlementTransactionManager for the read transaction (on a different connection
// pool) to have something to (not) see.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ReadTransactionIsolationTest {

	@Autowired
	SnapshotVersionRepository snapshotVersionRepository;

	@Autowired
	AuditEventRepository auditEventRepository;

	@Autowired
	PlatformTransactionManager entitlementTransactionManager;

	@Autowired
	@Qualifier("entitlementReadTransactionManager")
	PlatformTransactionManager entitlementReadTransactionManager;

	@Autowired
	@Qualifier("entitlementReadJdbcClient")
	JdbcClient entitlementReadJdbcClient;

	private long seedAuditEvent() {
		return auditEventRepository.insert(new AuditEventRow(null, "2026-08-09T00:00:00.000Z", "SYSTEM",
				"dev-operator", "API", "CAPABILITY", "isolation-test", "CREATE",
				null, null, null, null, null, null, null));
	}

	private long commitSnapshotVersion(String publishedAt) {
		return new TransactionTemplate(entitlementTransactionManager).execute(status ->
				snapshotVersionRepository.insert(new SnapshotVersionRow(null, publishedAt, seedAuditEvent(), "{}")));
	}

	private Long maxVersion() {
		return entitlementReadJdbcClient.sql("SELECT MAX(version) AS max_version FROM snapshot_version")
				.query((rs, rowNum) -> rs.getObject("max_version") == null ? null : rs.getLong("max_version"))
				.single();
	}

	@Test
	void readTransactionDoesNotSeeAWriteThatCommitsWhileItIsStillOpen() throws Exception {
		long initialVersion = commitSnapshotVersion("2026-08-09T00:00:00.000Z");

		CountDownLatch readTransactionOpened = new CountDownLatch(1);
		CountDownLatch writeCommitted = new CountDownLatch(1);
		ExecutorService writer = Executors.newSingleThreadExecutor();
		try {
			var writerResult = writer.submit(() -> {
				readTransactionOpened.await();
				commitSnapshotVersion("2026-08-09T00:01:00.000Z");
				writeCommitted.countDown();
				return null;
			});

			Long secondReadInsideOpenTransaction = new TransactionTemplate(entitlementReadTransactionManager)
					.execute(status -> {
						Long firstRead = maxVersion();
						assertThat(firstRead).isEqualTo(initialVersion);

						readTransactionOpened.countDown();
						awaitUninterruptibly(writeCommitted);

						return maxVersion();
					});

			// still the pre-write value: the read transaction opened its WAL snapshot before the
			// writer committed, and held it across the second SELECT inside the same transaction
			assertThat(secondReadInsideOpenTransaction).isEqualTo(initialVersion);

			writerResult.get(5, TimeUnit.SECONDS);
		} finally {
			writer.shutdown();
		}

		// a fresh read transaction now sees the committed write
		Long freshRead = new TransactionTemplate(entitlementReadTransactionManager)
				.execute(status -> maxVersion());
		assertThat(freshRead).isGreaterThan(initialVersion);
	}

	private static void awaitUninterruptibly(CountDownLatch latch) {
		try {
			assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new RuntimeException(e);
		}
	}
}
