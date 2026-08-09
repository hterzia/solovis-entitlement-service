package com.solovis.entitlement.service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The one zone every override window is interpreted in (002 spec §3.1, c5). Configurable so tests
 * can drive other zones, but not intended to vary in deployment: a date means the same thing to
 * everyone who reads it, and changing this changes what every stored window means.
 */
@ConfigurationProperties(prefix = "entitlement.clock")
public record EntitlementClockProperties(String zone) {

	public EntitlementClockProperties {
		if (zone == null || zone.isBlank()) {
			throw new IllegalArgumentException("entitlement.clock.zone must be set");
		}
	}
}
