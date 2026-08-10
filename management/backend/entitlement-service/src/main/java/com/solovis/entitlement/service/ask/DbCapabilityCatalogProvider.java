package com.solovis.entitlement.service.ask;

import com.solovis.entitlement.service.store.CapabilityRepository;
import com.solovis.entitlement.service.store.CapabilityRow;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Repository-backed provider. TODO(003): once the snapshot layer merges, build the catalogue from
 * the in-memory snapshot instead of querying per ask — same data, no per-question DB reads.
 */
@Component
public class DbCapabilityCatalogProvider implements CapabilityCatalogProvider {

	private final CapabilityRepository capabilities;

	public DbCapabilityCatalogProvider(CapabilityRepository capabilities) {
		this.capabilities = capabilities;
	}

	@Override
	public CapabilityCatalog current() {
		return CapabilityCatalog.from(capabilities.findAll(null, "ACTIVE", null));
	}

	@Override
	public Optional<String> retiredMatch(List<String> mentions) {
		if (mentions.isEmpty()) {
			return Optional.empty();
		}
		List<CapabilityRow> retired = capabilities.findAll(null, "RETIRED", null);
		for (String mention : mentions) {
			if (mention == null || mention.isBlank()) {
				continue;
			}
			for (CapabilityRow row : retired) {
				if (mention.equalsIgnoreCase(row.key())
						|| (row.displayName() != null && mention.equalsIgnoreCase(row.displayName()))) {
					return Optional.of(row.key());
				}
			}
		}
		return Optional.empty();
	}
}
