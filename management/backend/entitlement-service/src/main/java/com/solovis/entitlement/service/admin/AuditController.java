package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.AuditEventDto;
import com.solovis.entitlement.service.admin.dto.AuditListResponseDto;
import com.solovis.entitlement.service.error.RefId;
import com.solovis.entitlement.service.store.AccountRepository;
import com.solovis.entitlement.service.store.AuditEventFilter;
import com.solovis.entitlement.service.store.AuditEventRepository;
import com.solovis.entitlement.service.store.CapabilityRepository;
import com.solovis.entitlement.service.store.PlanRepository;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/admin/v1/audit")
public class AuditController {

    private static final int DEFAULT_LIMIT = 50;

    private final AuditEventRepository auditEventRepository;
    private final AccountRepository accountRepository;
    private final PlanRepository planRepository;
    private final CapabilityRepository capabilityRepository;
    private final ObjectMapper json;

    public AuditController(AuditEventRepository auditEventRepository, AccountRepository accountRepository,
            PlanRepository planRepository, CapabilityRepository capabilityRepository, ObjectMapper json) {
        this.auditEventRepository = auditEventRepository;
        this.accountRepository = accountRepository;
        this.planRepository = planRepository;
        this.capabilityRepository = capabilityRepository;
        this.json = json;
    }

    @GetMapping
    public AuditListResponseDto list(
        @RequestParam(required = false) String account, @RequestParam(required = false) String planKey,
        @RequestParam(required = false) String actor, @RequestParam(required = false) String entityType,
        @RequestParam(required = false) String from, @RequestParam(required = false) String to,
        @RequestParam(required = false) String cursor, @RequestParam(required = false, defaultValue = "50") int limit) {

        Long accountId = account == null ? null : accountRepository.findByExternalId(account).map(a -> a.id()).orElse(-1L);
        Long planId = planKey == null ? null : planRepository.findByKey(planKey).map(p -> p.id()).orElse(-1L);
        Long beforeSeq = cursor == null ? null : RefId.parse(cursor, "aud_");
        int pageSize = limit > 0 ? limit : DEFAULT_LIMIT;

        // Over-fetch by one to learn whether a further page exists, then drop the probe row. A
        // cursor emitted from a page that happens to be exactly full would send the History screen
        // to an empty page it had no way to predict — "there are more" and "this filled the page"
        // are different facts, and only the probe row distinguishes them.
        var rows = auditEventRepository.find(new AuditEventFilter(accountId, planId, actor, entityType, from, to,
            beforeSeq, pageSize + 1));
        boolean hasMore = rows.size() > pageSize;
        var page = hasMore ? rows.subList(0, pageSize) : rows;

        var events = page.stream().map(row -> new AuditEventDto(row.seq(), row.occurredAt(),
            new AuditEventDto.Actor(row.actorId(), row.actorKind()), row.source(), row.entityType(), row.entityId(), row.action(),
            row.planId() == null ? null : planRepository.findById(row.planId()).map(p -> p.key()).orElse(null),
            row.accountId() == null ? null : accountRepository.findById(row.accountId()).map(a -> a.externalId()).orElse(null),
            row.capabilityId() == null ? null : capabilityRepository.findById(row.capabilityId()).map(c -> c.key()).orElse(null),
            readTree(row.beforeJson()), readTree(row.afterJson()), row.reason(), row.affectedAccountCount()))
            .toList();

        String next = hasMore ? "aud_" + events.get(events.size() - 1).seq() : null;
        return new AuditListResponseDto(events, next);
    }

    private Object readTree(String value) {
        if (value == null) return null;
        try { return json.readTree(value); } catch (Exception e) { return value; }
    }
}
