package com.solovis.entitlement.service.snapshot;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Assembles the initial Snapshot before the app accepts traffic — every read route requires SnapshotHolder to be populated. */
@Component
@Order(0)
public class SnapshotStartup implements ApplicationRunner {

    private final SnapshotAssembler assembler;
    private final SnapshotHolder holder;

    public SnapshotStartup(SnapshotAssembler assembler, SnapshotHolder holder) {
        this.assembler = assembler;
        this.holder = holder;
    }

    @Override
    public void run(ApplicationArguments args) {
        holder.set(assembler.assembleFull());
    }
}
