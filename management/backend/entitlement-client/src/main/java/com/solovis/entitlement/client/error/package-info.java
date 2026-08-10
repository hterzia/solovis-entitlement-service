/**
 * The SDK's typed errors. The three domain distinctions ({@code UnknownAccountException},
 * {@code UnknownCapabilityException}, {@code RetiredCapabilityException}) come from
 * {@code entitlement-core} unchanged, because "we don't know" and "no" are different answers and
 * both surfaces must draw the line in the same place (c19).
 */
package com.solovis.entitlement.client.error;
