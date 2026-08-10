package com.solovis.entitlement.service.ask;

import java.util.List;
import java.util.Optional;

public interface CapabilityCatalogProvider {

	/** The catalogue of ACTIVE capabilities the interpreter may match against. */
	CapabilityCatalog current();

	/**
	 * Whether any of the given mentions (operator words or model-echoed keys) names a RETIRED
	 * capability — checked only after no active key matched, so retirement is stated as a fact
	 * rather than collapsing into "no match" (spec criterion 7). Always local; never the model.
	 */
	Optional<String> retiredMatch(List<String> mentions);
}
