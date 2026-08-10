package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.error.UnknownAccountException;
import com.solovis.entitlement.core.error.UnknownCapabilityException;
import com.solovis.entitlement.core.model.AccountAssignment;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.PlanEntitlement;
import com.solovis.entitlement.core.model.StandingOverride;
import com.solovis.entitlement.service.dto.CapabilityDescriptorDto;
import com.solovis.entitlement.service.dto.CapabilityDescriptorMapper;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.store.AccountOverrideRow;
import com.solovis.entitlement.service.store.HistoryReadDao;
import com.solovis.entitlement.service.time.Timestamps;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds an {@link com.solovis.entitlement.core.view.EntitlementView} for a past date and hands it
 * to the unchanged {@code Resolver.explain()} (002 §6).
 *
 * <p>It is a sibling of {@link RecordViewAssembler}, not a separate mechanism, and deliberately
 * produces the same {@link RecordBackedView}. The live path builds a view of <em>now</em> from
 * SQLite; this builds a view of <em>then</em>. Same shape, different {@code asOf}, same resolver —
 * which is what makes the history-replay property a comparison of two invocations of one
 * implementation rather than a reconstruction checked against an original.
 *
 * <h2>A date resolves to a sequence first</h2>
 *
 * Everything is bounded by one {@code asAtSeq}, so a change and its audit row can never be read
 * half-applied. That gives a past answer the one-coherent-moment property v1 c31 gives a live one.
 *
 * <h2>Refusals, never guesses</h2>
 *
 * A future date, a date before the account existed, and a date beyond the history each get their
 * own problem type and never a value (c26, c27, §6.5). The one needing care is <em>beyond the
 * history</em>, which must stay distinguishable from <em>the account existed and nothing was
 * set</em> — hence the check for an establishing entry rather than an inference from an empty
 * result.
 */
@Component
public class AsAtViewAssembler {

    private final HistoryReadDao historyReadDao;
    private final ObjectMapper json;
    private final Clock clock;

    public AsAtViewAssembler(HistoryReadDao historyReadDao, ObjectMapper json, Clock clock) {
        this.historyReadDao = historyReadDao;
        this.json = json;
        this.clock = clock;
    }

    /** What the answer was, and what the capability looked like, on {@code asOf}. */
    public record AsAtView(RecordBackedView view, Capability capability, Optional<Instant> capabilityRetiredSince) {}

    @Transactional(transactionManager = "entitlementReadTransactionManager", readOnly = true)
    public AsAtView assemble(String accountExternalId, String capabilityKey, LocalDate asOf) {
        LocalDate today = LocalDate.now(clock);
        if (asOf.isAfter(today)) {
            throw new EntitlementApiException(ErrorCode.FUTURE_DATE,
                "The service reports what was, not what will be; " + asOf + " is after " + today + ".");
        }

        // "As it stood at the end of that day", so the boundary is the start of the following day.
        String boundary = Timestamps.iso(asOf.plusDays(1).atStartOfDay(clock.getZone()).toInstant());

        var accountRow = historyReadDao.accountByExternalId(accountExternalId)
            .orElseThrow(() -> new UnknownAccountException(accountExternalId));
        var capabilityRow = historyReadDao.capabilityByKey(capabilityKey)
            .orElseThrow(() -> new UnknownCapabilityException(capabilityKey));

        long asAtSeq = historyReadDao.asAtSeq(boundary).orElseThrow(() -> beyondHistory(asOf));

        var accountEntry = historyReadDao.latestAccountEntry(accountRow.id(), asAtSeq)
            .orElseThrow(() -> {
                // No establishing entry at or before the date. Either the account came later, or the
                // trail itself does not reach back that far — different facts, different answers.
                String earliest = historyReadDao.earliestOccurredAt().orElse(null);
                if (earliest != null && boundary.compareTo(earliest) <= 0) {
                    return beyondHistory(asOf);
                }
                return new EntitlementApiException(ErrorCode.BEFORE_ACCOUNT_EXISTED,
                    "Account '" + accountExternalId + "' did not exist on " + asOf + ".");
            });

        String planKey = readString(accountEntry.afterJson(), "planKey");
        if (planKey == null) {
            throw beyondHistory(asOf);
        }
        var assignment = new AccountAssignment(accountExternalId, planKey);

        var capability = capabilityAsAt(capabilityRow.id(), capabilityKey, asAtSeq, asOf);
        var capabilities = Map.of(capability.key(), capability);

        Map<CapabilityKey, PlanEntitlement> entitlements = planEntitlementAsAt(
            planKey, capability, asAtSeq).map(pe -> Map.of(capability.key(), pe)).orElseGet(Map::of);

        var knownRows = historyReadDao.knownOverrides(accountRow.id(), capabilityRow.id(), boundary);
        var known = standing(knownRows, accountExternalId, capability, asOf);
        var inForce = known.stream().filter(StandingOverride::counts).map(StandingOverride::override).toList();

        var view = new RecordBackedView(RecordBackedView.Mode.POINT, 0L, accountExternalId, assignment,
            capabilities, entitlements,
            inForce.isEmpty() ? Map.of() : Map.of(capability.key(), inForce),
            known.isEmpty() ? Map.of() : Map.of(capability.key(), known));

        // Deliberately the capability's retirement as it stands *now*, not as at asAtSeq. c28 is
        // about a capability "retired since the date asked about": it was evaluable then, which is
        // why the answer resolves normally, and it is not evaluable now, which is what the operator
        // needs told. Bounding this by the sequence would report nothing in exactly the case the
        // criterion exists for.
        var retiredSince = Optional.ofNullable(capabilityRow.retiredAt()).map(Instant::parse);
        return new AsAtView(view, capability, retiredSince);
    }

    /**
     * The capability as it stood. Falls back to the descriptor recorded on any capability write at
     * or before the date; if there is none, the capability was declared later than the date, which
     * is the same shape of answer as an unknown one.
     */
    private Capability capabilityAsAt(long capabilityId, String capabilityKey, long asAtSeq, LocalDate asOf) {
        var entry = historyReadDao.latestCapabilityEntry(capabilityId, asAtSeq)
            .orElseThrow(() -> new EntitlementApiException(ErrorCode.BEYOND_HISTORY,
                "Capability '" + capabilityKey + "' had not been declared on " + asOf + "."));
        var descriptor = read(entry.afterJson(), CapabilityDescriptorDto.class);
        var retiredAt = historyReadDao.retiredAt(capabilityId, asAtSeq).map(Instant::parse).orElse(null);
        return CapabilityDescriptorMapper.fromDescriptor(descriptor, retiredAt);
    }

    /**
     * What the plan set. A recorded entry whose {@code after_json} is null is an <em>unset</em> —
     * the plan deliberately said nothing — which is not the same as no entry at all, and both mean
     * the capability default applies.
     */
    private Optional<PlanEntitlement> planEntitlementAsAt(String planKey, Capability capability, long asAtSeq) {
        var planId = historyReadDao.planIdByKey(planKey);
        if (planId.isEmpty()) {
            return Optional.empty();
        }
        return historyReadDao.latestPlanEntitlementEntry(planId.get(), capabilityIdOf(capability), asAtSeq)
            .filter(entry -> entry.afterJson() != null)
            .map(entry -> {
                var dto = read(entry.afterJson(), ValueDto.class);
                return new PlanEntitlement(planKey, capability.key(),
                    com.solovis.entitlement.service.dto.ValueMapper.fromDto(dto, capability));
            });
    }

    private long capabilityIdOf(Capability capability) {
        return historyReadDao.capabilityByKey(capability.key().value())
            .orElseThrow(() -> new UnknownCapabilityException(capability.key().value()))
            .id();
    }

    private List<StandingOverride> standing(
            List<AccountOverrideRow> rows, String accountExternalId, Capability capability, LocalDate asOf) {
        return rows.stream()
            .map(row -> StandingOverride.at(
                RowMappers.toOverride(row, accountExternalId, capability),
                Optional.ofNullable(row.removedAt())
                    .map(at -> Instant.parse(at).atZone(clock.getZone()).toLocalDate()),
                asOf))
            .toList();
    }

    private EntitlementApiException beyondHistory(LocalDate asOf) {
        return new EntitlementApiException(ErrorCode.BEYOND_HISTORY,
            "The change history does not reach back to " + asOf + ".");
    }

    private String readString(String jsonText, String field) {
        if (jsonText == null) {
            return null;
        }
        var node = json.readTree(jsonText).get(field);
        return node == null || node.isNull() ? null : node.asString();
    }

    private <T> T read(String jsonText, Class<T> type) {
        return json.readValue(jsonText, type);
    }
}
