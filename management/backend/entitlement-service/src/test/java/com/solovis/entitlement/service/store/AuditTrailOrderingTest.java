package com.solovis.entitlement.service.store;

import com.solovis.entitlement.service.admin.dto.AccountCreateRequest;
import com.solovis.entitlement.service.admin.dto.CapabilityCreateRequest;
import com.solovis.entitlement.service.admin.dto.PlanCreateRequest;
import com.solovis.entitlement.service.admin.service.AccountAdminService;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.admin.service.PlanAdminService;
import com.solovis.entitlement.service.dto.ValueDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The invariant every point-in-time answer silently depends on: <b>{@code seq} order and
 * {@code occurred_at} order must agree.</b>
 *
 * <p>{@code AsAtViewAssembler} resolves a date to {@code asAtSeq = MAX(seq) WHERE occurred_at <
 * boundary} and then bounds every lookup by {@code seq <= asAtSeq}. If a row carrying an early
 * {@code occurred_at} were ever given a high {@code seq} — which is exactly what appending a
 * backdated trail to an existing database would do — then asking about that early date would
 * resolve to a high sequence, and the four lookups would happily return changes made months later.
 * The failure is silent: a past question returns today's answer, confidently, which is the one
 * thing §6.5 forbids.
 *
 * <p>Nothing in the schema enforces this, because nothing can: {@code occurred_at} is supplied by
 * the application. It holds because every write stamps the injected clock and inserts in the same
 * transaction, and it is stated here so that a future seeder, importer or backfill that breaks it
 * fails loudly rather than corrupting reconstruction.
 *
 * <p>Scoped to the rows this test writes, and that scoping is the point rather than a convenience.
 * Several test classes insert {@code audit_event} rows directly with fixed past timestamps — a
 * fixture written at {@code 2026-08-09} lands after everything already in the shared database — so
 * a whole-table assertion would fail on other people's fixtures while saying nothing about the
 * write paths. What is checked here is that a run of ordinary admin writes comes out monotonic,
 * which is the property production depends on.
 */
@SpringBootTest
class AuditTrailOrderingTest {

	private static final AtomicInteger UNIQUE = new AtomicInteger();

	@Autowired AuditEventRepository auditEventRepository;
	@Autowired CapabilityAdminService capabilityService;
	@Autowired PlanAdminService planService;
	@Autowired AccountAdminService accountService;

	@Test
	void everyEntryIsAtLeastAsRecentAsTheOneBeforeIt() {
		// A handful of ordinary writes, so the assertion runs against a trail this suite produced
		// rather than only whatever happened to be there.
		int n = UNIQUE.incrementAndGet();
		planService.create(new PlanCreateRequest("ordering" + n, "Ordering " + n, null));
		capabilityService.create(new CapabilityCreateRequest("ordering" + n + ".probe", "Probe", null, "SWITCH",
				new ValueDto("SWITCH", false, null, null, null, null), null, null));
		planService.designateDefault("ordering" + n);
		accountService.create(new AccountCreateRequest("acct_ordering_" + n, "Ordering " + n));

		String prefix = "ordering" + n;
		var rows = auditEventRepository.find(new AuditEventFilter(null, null, null, null, null, null, null, null, 5000))
				.stream()
				.filter(row -> row.entityId().startsWith(prefix) || row.entityId().equals("acct_ordering_" + n))
				.toList();
		assertThat(rows).as("the writes above must be in the trail at all").hasSizeGreaterThan(3);

		// find() returns newest first, so walking it must see occurred_at descending.
		for (int i = 1; i < rows.size(); i++) {
			var newer = rows.get(i - 1);
			var older = rows.get(i);
			assertThat(newer.occurredAt())
					.as("seq %d occurred at %s but seq %d, which is older, occurred at %s — a trail whose "
							+ "sequence disagrees with its clock makes every point-in-time answer unsound",
							newer.seq(), newer.occurredAt(), older.seq(), older.occurredAt())
					.isGreaterThanOrEqualTo(older.occurredAt());
		}
	}
}
