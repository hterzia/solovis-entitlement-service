package com.solovis.entitlement.service.ask;

import com.solovis.entitlement.service.store.DecisionReadDao;

import org.springframework.stereotype.Component;

/**
 * {@link DecisionReadDao}-backed provider, all statuses — retired capabilities are included by
 * name (spec §4) so a question about a past date can still match one; {@link CapabilityCatalog}
 * carries the {@code retired} flag the service uses to decide what that match means.
 */
@Component
public class DaoCapabilityCatalogProvider implements CapabilityCatalogProvider {

	private final DecisionReadDao dao;

	public DaoCapabilityCatalogProvider(DecisionReadDao dao) {
		this.dao = dao;
	}

	@Override
	public CapabilityCatalog current() {
		return CapabilityCatalog.from(dao.allCapabilities(null, null, null));
	}
}
