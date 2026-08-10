package com.solovis.entitlement.service.audit;

import com.solovis.entitlement.service.store.AuditEventRepository;
import com.solovis.entitlement.service.store.AuditEventRow;
import org.springframework.stereotype.Component;
import java.time.Clock;
import com.solovis.entitlement.service.time.Timestamps;

@Component
public class AuditRecorder {

    private final AuditEventRepository repository;
    private final Clock clock;

    public AuditRecorder(AuditEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Writes one audit_event row. Callers are responsible for doing this inside the same @Transactional method as the row-level change it records (c32). */
    public long record(AuditEntry entry) {
        var row = new AuditEventRow(
            null, Timestamps.iso(clock.instant()), entry.actor().kind().name(), entry.actor().id(),
            entry.source(), entry.entityType(), entry.entityId(), entry.action(),
            entry.accountId(), entry.planId(), entry.capabilityId(),
            entry.beforeJson(), entry.afterJson(), entry.reason(), entry.affectedAccountCount(),
            entry.windowTransition());
        return repository.insert(row);
    }
}
