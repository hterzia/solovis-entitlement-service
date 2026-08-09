package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.view.Snapshot;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicReference;

/** The one in-memory copy of the model every read route resolves against (research.md §8). */
@Component
public class SnapshotHolder {

    private final AtomicReference<Snapshot> current = new AtomicReference<>();

    public Snapshot current() {
        Snapshot snapshot = current.get();
        if (snapshot == null) {
            throw new IllegalStateException("SnapshotHolder has not been initialised yet (SnapshotStartup must run first).");
        }
        return snapshot;
    }

    public void set(Snapshot snapshot) {
        current.set(snapshot);
    }
}
