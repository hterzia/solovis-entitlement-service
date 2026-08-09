package com.solovis.entitlement.service.audit;

import org.springframework.stereotype.Component;

/** The source recorded on the next audit event this thread writes — "UI" unless temporarily overridden via {@link #runAs}. */
@Component
public class AuditSource {

    private final ThreadLocal<String> current = ThreadLocal.withInitial(() -> "UI");

    public String current() {
        return current.get();
    }

    public void runAs(String source, Runnable action) {
        String previous = current.get();
        current.set(source);
        try {
            action.run();
        } finally {
            current.set(previous);
        }
    }
}
