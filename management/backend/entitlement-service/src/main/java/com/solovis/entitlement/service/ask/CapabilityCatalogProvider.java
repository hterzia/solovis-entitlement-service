package com.solovis.entitlement.service.ask;

public interface CapabilityCatalogProvider {

	/**
	 * The catalogue of every capability the interpreter may match against, active and retired
	 * alike — retirement is a per-entry flag on {@link CapabilityCatalog}, not a separate lookup.
	 */
	CapabilityCatalog current();
}
