package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.MetaResponseDto;
import com.solovis.entitlement.service.snapshot.SnapshotHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetaController {

    private final SnapshotHolder snapshotHolder;

    public MetaController(SnapshotHolder snapshotHolder) { this.snapshotHolder = snapshotHolder; }

    @GetMapping("/admin/v1/meta")
    public MetaResponseDto meta() {
        var snapshot = snapshotHolder.current();
        var areas = snapshot.capabilities().stream().map(c -> c.area()).distinct().sorted().toList();
        return new MetaResponseDto(60, 10, snapshot.snapshotVersion(), areas);
    }
}
