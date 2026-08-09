package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.AuditEventDto;
import com.solovis.entitlement.service.admin.dto.AuditListResponseDto;
import com.solovis.entitlement.service.store.AccountRepository;
import com.solovis.entitlement.service.store.AuditEventFilter;
import com.solovis.entitlement.service.store.AuditEventRepository;
import com.solovis.entitlement.service.store.CapabilityRepository;
import com.solovis.entitlement.service.store.PlanRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/v1/audit")
public class AuditController {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_LIMIT = 50;

    private final AuditEventRepository auditEventRepository;
    private final AccountRepository accountRepository;
    private final PlanRepository planRepository;
    private final CapabilityRepository capabilityRepository;

    public AuditController(AuditEventRepository auditEventRepository, AccountRepository accountRepository,
            PlanRepository planRepository, CapabilityRepository capabilityRepository) {
        this.auditEventRepository = auditEventRepository;
        this.accountRepository = accountRepository;
        this.planRepository = planRepository;
        this.capabilityRepository = capabilityRepository;
    }

    @GetMapping
    public AuditListResponseDto list(
        @RequestParam(required = false) String account, @RequestParam(required = false) String planKey,
        @RequestParam(required = false) String actor, @RequestParam(required = false) String entityType,
        @RequestParam(required = false) String from, @RequestParam(required = false) String to,
        @RequestParam(required = false) String cursor, @RequestParam(required = false, defaultValue = "50") int limit) {

        Long accountId = account == null ? null : accountRepository.findByExternalId(account).map(a -> a.id()).orElse(-1L);
        Long planId = planKey == null ? null : planRepository.findByKey(planKey).map(p -> p.id()).orElse(-1L);
        Long beforeSeq = cursor == null ? null : Long.valueOf(cursor.replace("aud_", ""));

        var rows = auditEventRepository.find(new AuditEventFilter(accountId, planId, actor, entityType, from, to,
            beforeSeq, limit > 0 ? limit : DEFAULT_LIMIT));

        var events = rows.stream().map(row -> new AuditEventDto(row.seq(), row.occurredAt(),
            new AuditEventDto.Actor(row.actorId(), row.actorKind()), row.source(), row.entityType(), row.entityId(), row.action(),
            row.planId() == null ? null : planRepository.findById(row.planId()).map(p -> p.key()).orElse(null),
            row.accountId() == null ? null : accountRepository.findById(row.accountId()).map(a -> a.externalId()).orElse(null),
            row.capabilityId() == null ? null : capabilityRepository.findById(row.capabilityId()).map(c -> c.key()).orElse(null),
            readTree(row.beforeJson()), readTree(row.afterJson()), row.reason(), row.affectedAccountCount()))
            .toList();

        String next = events.isEmpty() ? null : "aud_" + events.get(events.size() - 1).seq();
        return new AuditListResponseDto(events, next);
    }

    private static Object readTree(String json) {
        if (json == null) return null;
        try { return JSON.readTree(json); } catch (Exception e) { return json; }
    }
}
