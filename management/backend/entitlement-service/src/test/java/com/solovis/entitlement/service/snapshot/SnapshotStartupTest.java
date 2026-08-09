package com.solovis.entitlement.service.snapshot;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.ApplicationRunner;

import static org.assertj.core.api.Assertions.assertThat;

// Plain type-level assertion. The actual bug (a window where the embedded web connector accepts
// requests before ApplicationRunner beans fire) is a startup-ordering race between
// ApplicationContext.finishRefresh() and SpringApplication.run()'s ApplicationRunner invocation.
// The existing @SpringBootTest harness shares a single refreshed context across the test suite, so
// there is no seam here to observe "is the port open yet" pre-refresh — proving the fix requires
// trusting Spring's documented contract that InitializingBean.afterPropertiesSet() runs as part of
// bean initialisation during context refresh, strictly before WebServerStartStopLifecycle starts the
// connector. This test only pins the class shape so a future regression back to ApplicationRunner
// fails a test instead of silently reopening the window.
class SnapshotStartupTest {

    @Test
    void isAnInitializingBeanAndNotAnApplicationRunner() {
        assertThat(InitializingBean.class).isAssignableFrom(SnapshotStartup.class);
        assertThat(ApplicationRunner.class.isAssignableFrom(SnapshotStartup.class)).isFalse();
    }
}
