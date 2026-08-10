package com.solovis.entitlement.service.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Registers {@link EntitlementSnapshotProperties} and turns on the scheduler that drives
 * {@code SnapshotVersionPruner}.
 *
 * <p>Scheduling is enabled here rather than on the application class so that the one background job
 * this service runs is visible next to the settings that govern it. Nothing on a decision path is
 * scheduled — reads resolve against the in-memory snapshot and touch neither this table nor this
 * thread.
 */
@Configuration
@EnableConfigurationProperties(EntitlementSnapshotProperties.class)
@EnableScheduling
public class SnapshotRetentionConfig {
}
