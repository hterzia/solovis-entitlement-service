/**
 * Jackson mirrors of the service's wire shapes, plus the one-way mapping into
 * {@code entitlement-core} domain objects.
 *
 * <p>These are mirrors, not shared types: the service's own DTOs live in {@code
 * entitlement-service} and drag in Spring's {@code HttpStatus}, so they cannot be reused here.
 * When the service's wire encoding changes, this package changes with it — {@code
 * ClientAgainstRealFeedTest} in the service module is what catches the two drifting apart.
 */
package com.solovis.entitlement.client.wire;
