/**
 * The embeddable entitlement SDK: a local replica of the model plus the same {@code
 * entitlement-core} resolver the management service runs, so a decision is an in-process map
 * lookup that keeps working while the service does not.
 *
 * <p>Contract: {@code .specs/001-entitlement-service/contracts/java-client-sdk.md}.
 *
 * <p>The SDK answers; it does not explain. Reason text, authorship and timestamps deliberately
 * never reach a replica — {@code explain()} is a diagnostic network call, not a decision path.
 */
package com.solovis.entitlement.client;
