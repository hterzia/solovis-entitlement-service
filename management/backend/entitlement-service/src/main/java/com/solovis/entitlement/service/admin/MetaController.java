package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.MetaResponseDto;
import com.solovis.entitlement.service.store.CapabilityRow;
import com.solovis.entitlement.service.store.DecisionReadDao;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetaController {

    private final DecisionReadDao decisionReadDao;

    public MetaController(DecisionReadDao decisionReadDao) { this.decisionReadDao = decisionReadDao; }

    @GetMapping("/admin/v1/meta")
    public MetaResponseDto meta() {
        var areas = decisionReadDao.allCapabilities(null, null, null).stream()
            .map(CapabilityRow::area).distinct().sorted().toList();
        return new MetaResponseDto(60, 10, decisionReadDao.latestVersion(), areas);
    }
}
