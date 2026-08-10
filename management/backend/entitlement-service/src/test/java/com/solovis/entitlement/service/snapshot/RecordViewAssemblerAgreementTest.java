package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.engine.Resolver;
import com.solovis.entitlement.core.error.RetiredCapabilityException;
import com.solovis.entitlement.core.error.UnknownAccountException;
import com.solovis.entitlement.core.error.UnknownCapabilityException;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.view.EntitlementView;
import com.solovis.entitlement.service.store.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The keystone of the read-path move: the view the service answers from and the snapshot the feed
 * ships must be indistinguishable to the resolver. For every (account, capability) pair in a model
 * covering all three value types, competing GRANTs and HOLDs, a retired capability and a CLOSED
 * account, {@code resolve} and {@code explain} must return the identical answer — or throw the
 * identical §6.3 error — through the point view, the account slice and a full {@code Snapshot}.
 *
 * <p>Not {@code @Transactional}: {@code pointView}/{@code accountView} read through the separate
 * read pool, which only sees committed data (c.f. {@code DecisionReadDaoTest}). Rows seeded here
 * therefore commit, which is also why this class takes its own database file — several repository
 * tests assert over a whole table and would see these rows if they landed in the shared one.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = "entitlement.database.path=${java.io.tmpdir}/entitlement-record-view-test-${random.uuid}.db")
class RecordViewAssemblerAgreementTest {

	private static final Instant EVALUATED_AT = Instant.parse("2026-08-10T12:00:00Z");
	private static final String TS = "2026-08-10T00:00:00.000Z";

	@Autowired RecordViewAssembler assembler;
	@Autowired SnapshotAssembler snapshotAssembler;
	@Autowired CapabilityRepository capabilityRepository;
	@Autowired PlanRepository planRepository;
	@Autowired PlanEntitlementRepository planEntitlementRepository;
	@Autowired AccountRepository accountRepository;
	@Autowired AccountOverrideRepository accountOverrideRepository;
	@Autowired SnapshotVersionRepository snapshotVersionRepository;
	@Autowired AuditEventRepository auditEventRepository;

	private String suffix;
	private String activeAccount;
	private String plainAccount;
	private String closedAccount;
	private String switchCap;
	private String quantityCap;
	private String tierCap;
	private String retiredCap;
	private String unknownCap;
	private long holdOverrideId;

	@BeforeEach
	void seed() {
		suffix = UUID.randomUUID().toString().substring(0, 8);
		activeAccount = "acct_active_" + suffix;
		plainAccount = "acct_plain_" + suffix;
		closedAccount = "acct_closed_" + suffix;
		switchCap = "sw.flag_" + suffix;
		quantityCap = "qty.seats_" + suffix;
		tierCap = "tier.support_" + suffix;
		retiredCap = "ret.old_" + suffix;
		unknownCap = "nope.absent_" + suffix;

		long planId = planRepository.insert(new PlanRow(null, "plan_" + suffix, "Plan", null, "ACTIVE", false, TS, TS));

		// SWITCH, default false, raised to true by the plan.
		long switchId = insertCapability(switchCap, "SWITCH", false, null, false, null, false, null, null);
		planEntitlementRepository.upsert(new PlanEntitlementRow(planId, switchId, true, null, false, null, TS));

		// QUANTITY with an off-value of 0, default 5, no plan entitlement (baseline is the capability default).
		long quantityId = insertCapability(quantityCap, "QUANTITY", null, 5L, false, null, true, 0L, null);

		// TIER over three tiers, default bronze, raised to silver by the plan.
		long tierId = insertCapability(tierCap, "TIER", null, null, false, "bronze", false, null, null);
		capabilityRepository.insertTier(new CapabilityTierRow(tierId, "bronze", 0, "Bronze"));
		capabilityRepository.insertTier(new CapabilityTierRow(tierId, "silver", 1, "Silver"));
		capabilityRepository.insertTier(new CapabilityTierRow(tierId, "gold", 2, "Gold"));
		planEntitlementRepository.upsert(new PlanEntitlementRow(planId, tierId, null, null, false, "silver", TS));

		long retiredId = insertCapability(retiredCap, "SWITCH", false, null, false, null, false, null, null);
		capabilityRepository.retire(retiredId, "2026-08-10T01:00:00.000Z", "2026-08-10T01:00:00.000Z");

		long activeId = accountRepository.insert(new AccountRow(null, activeAccount, "Active", planId, TS,
				"PERSON", "operator", "ACTIVE", TS, TS));
		accountRepository.insert(new AccountRow(null, plainAccount, "Plain", planId, TS,
				"PERSON", "operator", "ACTIVE", TS, TS));
		long closedId = accountRepository.insert(new AccountRow(null, closedAccount, "Closed", planId, TS,
				"PERSON", "operator", "CLOSED", TS, TS));

		// Competing overrides on the quantity capability: a GRANT of 200 raised over the default of 5,
		// then capped by a HOLD of 50 — a restriction always defeats a concession (§4).
		accountOverrideRepository.insert(AccountOverrideRow.openEnded(null, activeId, quantityId, "GRANT",
				null, 200L, false, null, "capacity purchased", TS, "operator", "PERSON", null, null, null));
		holdOverrideId = accountOverrideRepository.insert(AccountOverrideRow.openEnded(null, activeId, quantityId, "HOLD",
				null, 50L, false, null, "suspended pending investigation", TS, "operator", "PERSON", null, null, null));
		// A second GRANT that loses to the first, so the trace carries a non-winning entry too.
		accountOverrideRepository.insert(AccountOverrideRow.openEnded(null, activeId, quantityId, "GRANT",
				null, 20L, false, null, "trial bump", TS, "operator", "PERSON", null, null, null));
		// A GRANT on the tier capability, lifting silver to gold.
		accountOverrideRepository.insert(AccountOverrideRow.openEnded(null, activeId, tierId, "GRANT",
				null, null, false, "gold", "escalated support", TS, "operator", "PERSON", null, null, null));
		// A live override belonging to the CLOSED account — it must be invisible to every view.
		accountOverrideRepository.insert(AccountOverrideRow.openEnded(null, closedId, quantityId, "GRANT",
				null, 999L, false, null, "should never be seen", TS, "operator", "PERSON", null, null, null));

		snapshotVersionRepository.insert(new SnapshotVersionRow(null, TS, seedAuditEvent(), "{}"));
	}

	@Test
	void pointViewAccountSliceAndFullSnapshotAgreeOnEveryPair() {
		var snapshot = snapshotAssembler.assembleFull();
		var accountViews = List.of(activeAccount, plainAccount, closedAccount).stream()
				.collect(java.util.stream.Collectors.toMap(a -> a, a -> assembler.accountView(a)));

		for (String account : List.of(activeAccount, plainAccount, closedAccount)) {
			for (String capability : List.of(switchCap, quantityCap, tierCap, retiredCap, unknownCap)) {
				var key = new CapabilityKey(capability);
				EntitlementView point = assembler.pointView(account, capability);
				EntitlementView slice = accountViews.get(account);
				EntitlementView writeTxnPoint = assembler.pointViewInWriteTxn(account, capability);

				String pair = account + " / " + capability;
				assertThat(outcome(() -> Resolver.resolve(point, account, key, EVALUATED_AT)))
						.describedAs("resolve via point view vs account slice for " + pair)
						.isEqualTo(outcome(() -> Resolver.resolve(slice, account, key, EVALUATED_AT)))
						.describedAs("resolve via point view vs full snapshot for " + pair)
						.isEqualTo(outcome(() -> Resolver.resolve(snapshot, account, key, EVALUATED_AT)))
						.describedAs("resolve via point view vs write-transaction point view for " + pair)
						.isEqualTo(outcome(() -> Resolver.resolve(writeTxnPoint, account, key, EVALUATED_AT)));

				assertThat(outcome(() -> Resolver.explain(point, account, key, EVALUATED_AT)))
						.describedAs("explain via point view vs account slice for " + pair)
						.isEqualTo(outcome(() -> Resolver.explain(slice, account, key, EVALUATED_AT)))
						.describedAs("explain via point view vs full snapshot for " + pair)
						.isEqualTo(outcome(() -> Resolver.explain(snapshot, account, key, EVALUATED_AT)))
						.describedAs("explain via point view vs write-transaction point view for " + pair)
						.isEqualTo(outcome(() -> Resolver.explain(writeTxnPoint, account, key, EVALUATED_AT)));
			}
		}
	}

	// Agreement alone would be satisfied by three views that are identically wrong, so pin the
	// answer each edge case is supposed to produce as well.

	@Test
	void everyViewRaisesUnknownAccountForAClosedAccount() {
		for (var view : viewsFor(closedAccount, switchCap)) {
			assertThatThrownBy(() -> Resolver.resolve(view, closedAccount, new CapabilityKey(switchCap), EVALUATED_AT))
					.isInstanceOf(UnknownAccountException.class)
					.extracting(e -> ((UnknownAccountException) e).accountExternalId())
					.isEqualTo(closedAccount);
		}
	}

	@Test
	void everyViewRaisesRetiredCapabilityForARetiredCapability() {
		for (var view : viewsFor(activeAccount, retiredCap)) {
			assertThatThrownBy(() -> Resolver.resolve(view, activeAccount, new CapabilityKey(retiredCap), EVALUATED_AT))
					.isInstanceOf(RetiredCapabilityException.class)
					.extracting(e -> ((RetiredCapabilityException) e).capabilityKey())
					.isEqualTo(retiredCap);
		}
	}

	@Test
	void everyViewRaisesUnknownCapabilityForAnUndeclaredKey() {
		for (var view : viewsFor(activeAccount, unknownCap)) {
			assertThatThrownBy(() -> Resolver.resolve(view, activeAccount, new CapabilityKey(unknownCap), EVALUATED_AT))
					.isInstanceOf(UnknownCapabilityException.class)
					.extracting(e -> ((UnknownCapabilityException) e).capabilityKey())
					.isEqualTo(unknownCap);
		}
	}

	@Test
	void everyViewCapsTheGrantWithTheHold() {
		for (var view : viewsFor(activeAccount, quantityCap)) {
			var decision = Resolver.resolve(view, activeAccount, new CapabilityKey(quantityCap), EVALUATED_AT);
			assertThat(decision.value()).isEqualTo(EntitlementValue.Quantity.of(50));
			assertThat(decision.allowed()).isTrue();
		}
	}

	@Test
	void withoutOverrideDropsOnlyThatOverrideAndUncapsTheGrant() {
		var key = new CapabilityKey(quantityCap);
		for (var view : List.of(assembler.pointView(activeAccount, quantityCap), assembler.accountView(activeAccount))) {
			assertThat(view.liveOverrides(activeAccount, key)).hasSize(3);

			var withoutHold = view.withoutOverride(holdOverrideId);
			assertThat(withoutHold.liveOverrides(activeAccount, key))
					.hasSize(2)
					.noneMatch(o -> o.id().equals(java.util.OptionalLong.of(holdOverrideId)));
			assertThat(Resolver.resolve(withoutHold, activeAccount, key, EVALUATED_AT).value())
					.isEqualTo(EntitlementValue.Quantity.of(200));

			// The original is untouched — the copy shares, it does not mutate.
			assertThat(Resolver.resolve(view, activeAccount, key, EVALUATED_AT).value())
					.isEqualTo(EntitlementValue.Quantity.of(50));
		}
	}

	@Test
	void feedOnlyMethodsThrowNamingThemselves() {
		var point = assembler.pointView(activeAccount, quantityCap);
		var slice = assembler.accountView(activeAccount);

		for (var view : List.of(point, slice)) {
			assertUnsupported(view::capabilities, "capabilities");
			assertUnsupported(view::accountAssignments, "accountAssignments");
			assertUnsupported(view::allLiveOverrides, "allLiveOverrides");
			assertUnsupported(() -> view.plan("plan_" + suffix), "plan");
			assertUnsupported(view::plans, "plans");
		}
		// activeCapabilities() is real on the account slice and unsupported on a point view.
		assertUnsupported(point::activeCapabilities, "activeCapabilities");
		assertThat(slice.activeCapabilities()).extracting(c -> c.key().value())
				.contains(switchCap, quantityCap, tierCap)
				.doesNotContain(retiredCap);
	}

	private static void assertUnsupported(Supplier<?> call, String method) {
		assertThatThrownBy(call::get)
				.isInstanceOf(UnsupportedOperationException.class)
				.hasMessageContaining(method);
	}

	private List<RecordBackedView> viewsFor(String account, String capability) {
		return List.of(assembler.pointView(account, capability), assembler.accountView(account));
	}

	/** The resolver's answer, or a stable description of the §6.3 error it raised instead. */
	private static Object outcome(Supplier<?> call) {
		try {
			return call.get();
		}
		catch (UnknownAccountException e) {
			return "entitlement/unknown-account:" + e.accountExternalId();
		}
		catch (UnknownCapabilityException e) {
			return "entitlement/unknown-capability:" + e.capabilityKey();
		}
		catch (RetiredCapabilityException e) {
			return "entitlement/retired-capability:" + e.capabilityKey();
		}
	}

	private long insertCapability(String key, String valueType, Boolean defaultBool, Long defaultQty,
			boolean defaultQtyUnlimited, String defaultTier, boolean hasOffValue, Long offQty, String offTier) {
		return capabilityRepository.insert(new CapabilityRow(null, key, key.substring(0, key.indexOf('.')),
				"Display " + key, null, valueType, defaultBool, defaultQty, defaultQtyUnlimited, defaultTier,
				hasOffValue, offQty, offTier, "ACTIVE", null, TS, TS));
	}

	private long seedAuditEvent() {
		return auditEventRepository.insert(new AuditEventRow(null, TS, "SYSTEM", "record-view-test", "API",
				"CAPABILITY", "record-view-agreement-test", "CREATE", null, null, null, null, null, null, null));
	}
}
