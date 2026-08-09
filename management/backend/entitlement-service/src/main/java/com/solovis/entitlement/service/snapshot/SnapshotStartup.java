package com.solovis.entitlement.service.snapshot;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Assembles the initial Snapshot before the app accepts traffic — every read route requires
 * SnapshotHolder to be populated. Implemented as an {@link InitializingBean} rather than an
 * {@code ApplicationRunner}: Spring Boot starts the embedded web connector during
 * {@code ApplicationContext.finishRefresh()}, which happens before {@code SpringApplication.run()}
 * invokes any {@code ApplicationRunner}. An {@code afterPropertiesSet()} callback runs during bean
 * initialisation, strictly before the connector starts, so there is no window where the port is
 * accepting connections but the snapshot is not yet populated.
 */
@Component
public class SnapshotStartup implements InitializingBean {

    private final SnapshotAssembler assembler;
    private final SnapshotHolder holder;

    public SnapshotStartup(SnapshotAssembler assembler, SnapshotHolder holder) {
        this.assembler = assembler;
        this.holder = holder;
    }

    @Override
    public void afterPropertiesSet() {
        holder.set(assembler.assembleFull());
    }
}
