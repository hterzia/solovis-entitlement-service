package com.solovis.entitlement.service.ask;

import com.solovis.entitlement.service.admin.CheckerController;

import org.springframework.stereotype.Component;

@Component
class CheckerControllerPort implements CheckerPort {

	private final CheckerController checker;

	CheckerControllerPort(CheckerController checker) {
		this.checker = checker;
	}

	@Override
	public Object explain(String accountExternalId, String capabilityKey, String asAt) {
		return checker.check(accountExternalId, capabilityKey, null, asAt).getBody();
	}
}
