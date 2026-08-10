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
		return auditEventRepository.insert(AuditEventRow.operatorAct(null, "2026-08-09T00:00:00.000Z", "PERSON", "a.reyes",
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
