package com.solovis.entitlement.core.engine;

import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.OverrideKind;
import com.solovis.entitlement.core.model.OverrideStanding;
import com.solovis.entitlement.core.model.Plan;
import com.solovis.entitlement.core.model.PlanEntitlement;
import com.solovis.entitlement.core.model.StandingOverride;
import com.solovis.entitlement.core.model.TierOrder;
import com.solovis.entitlement.core.model.ValueType;
import com.solovis.entitlement.core.view.EntitlementView;
import com.solovis.entitlement.core.view.Snapshot;
import com.solovis.entitlement.core.view.SnapshotBuilder;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 002 c19–c21 — the explanation names overrides that existed but took no part, and doing so never
 * moves a value.
 */
class ResolverNotInForceTest {

	private static final CapabilityKey REPORTS = new CapabilityKey("reports.monthly");
	private static final Instant NOW = Instant.parse("2026-08-09T14:03:11.482Z");
	private static final LocalDate TODAY = LocalDate.of(2026, 8, 9);

	private static AccountOverride grant(long id, long amount, LocalDate startsOn, LocalDate expiresOn) {
		return new AccountOverride(OptionalLong.of(id), "acct_9931", REPORTS, OverrideKind.GRANT,
				EntitlementValue.Quantity.of(amount), Optional.of("Q3 pilot"), Optional.of("j.okafor"),
				Optional.of(NOW), Optional.ofNullable(startsOn), Optional.ofNullable(expiresOn));
	}

	private static Snapshot snapshotWith(AccountOverride... inForce) {
		var builder = new SnapshotBuilder()
				.capability(new Capability(REPORTS, "Monthly reports", null, ValueType.QUANTITY,
						EntitlementValue.Quantity.of(0), Optional.empty(), TierOrder.NONE,
						Capability.Status.ACTIVE, null))
				.plan(new Plan("pro", "Pro", Plan.Status.ACTIVE, false))
				.planEntitlement(new PlanEntitlement("pro", REPORTS, EntitlementValue.Quantity.of(50)))
				.account(new AccountAssignment("acct_9931", "pro"));
		for (var override : inForce) {
			builder.override(override);
		}
		return builder.build(48211);
	}

	/**
	 * A view that knows about overrides which are not counting — what a database-backed view
	 * produces, and what a replica's never will.
	 */
	private static EntitlementView withKnown(Snapshot base, List<StandingOverride> known) {
		return new KnowsMoreThanItCounts(base, known);
	}

	/**
	 * Delegates everything to a snapshot except {@code knownOverrides}, which reports overrides the
	 * snapshot does not hold. That asymmetry is the point: a snapshot carries only what counts, and
	 * a database-backed view will carry the rest.
	 */
	private record KnowsMoreThanItCounts(Snapshot base, List<StandingOverride> known) implements EntitlementView {

		public long snapshotVersion() {
			return base.snapshotVersion();
		}

		public Optional<Capability> capability(CapabilityKey key) {
			return base.capability(key);
		}

		public Collection<Capability> capabilities() {
			return base.capabilities();
		}

		public Collection<Capability> activeCapabilities() {
			return base.activeCapabilities();
		}

		public Optional<AccountAssignment> account(String externalId) {
			return base.account(externalId);
		}

		public Collection<AccountAssignment> accountAssignments() {
			return base.accountAssignments();
		}

		public Optional<PlanEntitlement> planEntitlement(String planKey, CapabilityKey key) {
			return base.planEntitlement(planKey, key);
		}

		public List<AccountOverride> liveOverrides(String externalId, CapabilityKey key) {
			return base.liveOverrides(externalId, key);
		}

		public Collection<AccountOverride> allLiveOverrides() {
			return base.allLiveOverrides();
		}

		public Optional<Plan> plan(String planKey) {
			return base.plan(planKey);
		}

		public Collection<Plan> plans() {
			return base.plans();
		}

		public List<StandingOverride> knownOverrides(String externalId, CapabilityKey key) {
			return known;
		}
	}

	/** The behaviour every view written before 002 must keep: nothing changes. */
	@Test
	void aViewThatKnowsOnlyLiveOverridesExplainsExactlyAsItDidBefore() {
		var snapshot = snapshotWith(grant(4471, 200, null, null));

		var explanation = Resolver.explain(snapshot, "acct_9931", REPORTS, NOW);

		assertThat(explanation.trace().grants()).hasSize(1);
		assertThat(explanation.trace().grants().get(0).outcome()).contains(Outcome.WON);
		assertThat(explanation.decision().value()).isEqualTo(EntitlementValue.Quantity.of(200));
	}

	/**
	 * The question this feature exists to answer: the customer sees 50, and the explanation says
	 * why it dropped rather than only that no grant is in force (c20).
	 */
	@Test
	void anEndedGrantIsNamedWithTheDateItStoppedCounting() {
		var ended = grant(4471, 200, null, LocalDate.of(2026, 6, 30));
		var view = withKnown(snapshotWith(), List.of(StandingOverride.at(ended, Optional.empty(), TODAY)));

		var explanation = Resolver.explain(view, "acct_9931", REPORTS, NOW);

		assertThat(explanation.decision().value())
				.as("an ended grant must not raise the value")
				.isEqualTo(EntitlementValue.Quantity.of(50));
		assertThat(explanation.trace().grants()).hasSize(1);
		var entry = explanation.trace().grants().get(0);
		assertThat(entry.outcome()).contains(Outcome.NOT_IN_FORCE_ENDED);
		assertThat(entry.value()).isEqualTo(EntitlementValue.Quantity.of(200));
		assertThat(entry.expiresOn()).contains(LocalDate.of(2026, 6, 30));
		assertThat(entry.notInForceSince()).contains(LocalDate.of(2026, 7, 1));
		assertThat(explanation.trace().grantWinner()).isEmpty();
	}

	@Test
	void aPendingGrantIsNamedWithTheDateItWillBegin() {
		var pending = grant(4471, 200, LocalDate.of(2026, 10, 1), null);
		var view = withKnown(snapshotWith(), List.of(StandingOverride.at(pending, Optional.empty(), TODAY)));

		var explanation = Resolver.explain(view, "acct_9931", REPORTS, NOW);

		assertThat(explanation.decision().value()).isEqualTo(EntitlementValue.Quantity.of(50));
		var entry = explanation.trace().grants().get(0);
		assertThat(entry.outcome()).contains(Outcome.NOT_IN_FORCE_PENDING);
		assertThat(entry.startsOn()).contains(LocalDate.of(2026, 10, 1));
		assertThat(entry.notInForceSince()).as("a pending override's date is its own start").isEmpty();
	}

	@Test
	void aRemovedGrantIsNamedWithTheDayItWasRemoved() {
		LocalDate removedOn = LocalDate.of(2026, 5, 12);
		var removed = grant(4471, 200, null, null);
		var view = withKnown(snapshotWith(), List.of(StandingOverride.at(removed, Optional.of(removedOn), TODAY)));

		var entry = Resolver.explain(view, "acct_9931", REPORTS, NOW).trace().grants().get(0);

		assertThat(entry.outcome()).contains(Outcome.NOT_IN_FORCE_REMOVED);
		assertThat(entry.notInForceSince()).contains(removedOn);
	}

	@Test
	void notInForceOverridesAreSortedAfterTheOnesThatCounted() {
		var counting = grant(9001, 120, null, null);
		var ended = grant(4471, 200, null, LocalDate.of(2026, 6, 30));
		var view = withKnown(snapshotWith(counting), List.of(
				StandingOverride.inForce(counting),
				StandingOverride.at(ended, Optional.empty(), TODAY)));

		var grants = Resolver.explain(view, "acct_9931", REPORTS, NOW).trace().grants();

		assertThat(grants).hasSize(2);
		assertThat(grants.get(0).outcome()).contains(Outcome.WON);
		assertThat(grants.get(1).outcome()).contains(Outcome.NOT_IN_FORCE_ENDED);
	}

	/**
	 * The load-bearing property. resolve() never sees the wider set, so if adding not-in-force
	 * entries could move a value, explain() and resolve() would disagree — the one thing v1
	 * criterion 24 forbids.
	 */
	@Test
	void resolveAndExplainAgreeEvenWhenTheExplanationNamesFarMoreThanTheArithmeticUsed() {
		var counting = grant(9001, 120, null, null);
		var snapshot = snapshotWith(counting);
		var view = withKnown(snapshot, List.of(
				StandingOverride.inForce(counting),
				StandingOverride.at(grant(1, 5000, null, LocalDate.of(2026, 6, 30)), Optional.empty(), TODAY),
				StandingOverride.at(grant(2, 9999, LocalDate.of(2027, 1, 1), null), Optional.empty(), TODAY),
				StandingOverride.at(grant(3, 7777, null, null), Optional.of(LocalDate.of(2026, 5, 12)), TODAY)));

		var resolved = Resolver.resolve(snapshot, "acct_9931", REPORTS, NOW);
		var explained = Resolver.explain(view, "acct_9931", REPORTS, NOW);

		assertThat(explained.decision().value()).isEqualTo(resolved.value());
		assertThat(explained.decision().allowed()).isEqualTo(resolved.allowed());
		assertThat(explained.decision().value()).isEqualTo(EntitlementValue.Quantity.of(120));
		assertThat(explained.trace().grants()).hasSize(4);
	}

	@Test
	void anEndedHoldNoLongerRestrainsButIsStillNamed() {
		var hold = new AccountOverride(OptionalLong.of(7788), "acct_9931", REPORTS, OverrideKind.HOLD,
				EntitlementValue.Quantity.of(0), Optional.of("Suspended"), Optional.of("billing-bot"),
				Optional.of(NOW), Optional.empty(), Optional.of(LocalDate.of(2026, 6, 30)));
		var view = withKnown(snapshotWith(), List.of(StandingOverride.at(hold, Optional.empty(), TODAY)));

		var explanation = Resolver.explain(view, "acct_9931", REPORTS, NOW);

		assertThat(explanation.decision().value())
				.as("the plan value resumes with nobody acting (c12)")
				.isEqualTo(EntitlementValue.Quantity.of(50));
		assertThat(explanation.trace().holds()).hasSize(1);
		assertThat(explanation.trace().holds().get(0).outcome()).contains(Outcome.NOT_IN_FORCE_ENDED);
		assertThat(explanation.trace().holdWinner()).isEmpty();
		assertThat(explanation.trace().grants()).as("a not-in-force HOLD is not filed under grants").isEmpty();
	}

	@Test
	void anInForceOverrideStillReportsTheWindowItWasGrantedUnder() {
		var windowed = grant(4471, 200, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 12, 31));
		var snapshot = snapshotWith(windowed);

		var entry = Resolver.explain(snapshot, "acct_9931", REPORTS, NOW).trace().grants().get(0);

		assertThat(entry.outcome()).contains(Outcome.WON);
		assertThat(entry.startsOn()).contains(LocalDate.of(2026, 8, 1));
		assertThat(entry.expiresOn()).contains(LocalDate.of(2026, 12, 31));
		assertThat(entry.notInForceSince()).isEmpty();
	}

	@Test
	void overrideStandingIsNotDecidedTwice() {
		var ended = grant(4471, 200, null, LocalDate.of(2026, 6, 30));

		assertThat(StandingOverride.at(ended, Optional.empty(), TODAY).standing())
				.isEqualTo(OverrideStanding.ENDED);
		assertThat(StandingOverride.inForce(ended).standing())
				.as("a caller that says in-force is believed; the resolver does not re-derive it")
				.isEqualTo(OverrideStanding.IN_FORCE);
	}
}
