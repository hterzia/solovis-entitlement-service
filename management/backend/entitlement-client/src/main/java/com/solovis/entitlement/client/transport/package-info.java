/**
 * Everything that touches the network, and nothing that understands the domain. This package hands
 * back parsed replicas and DTOs; it never resolves a decision, and {@code replica} never opens a
 * socket. That seam is what makes the outage posture testable.
 */
package com.solovis.entitlement.client.transport;
