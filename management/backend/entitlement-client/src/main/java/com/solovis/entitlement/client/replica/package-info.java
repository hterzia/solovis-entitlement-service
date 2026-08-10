/**
 * The local replica: reading it from the feed, advancing it by delta, gating it for conformance,
 * and caching it to disk. Nothing in this package touches HTTP — that seam is what lets outage
 * behaviour be tested without a network.
 */
package com.solovis.entitlement.client.replica;
