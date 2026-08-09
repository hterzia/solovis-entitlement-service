package com.solovis.entitlement.service;

import com.solovis.entitlement.service.admin.service.PlanAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class EntitlementServiceApplicationTests {

	@Autowired PlanAdminService planAdminService;

	@Test
	void contextLoads() {
	}

	@Test
	void demoDataSeederDoesNotRunUnderTestConfig() {
		// entitlement.seed.enabled: false in src/test/resources/application.yaml. Spring caches this
		// context across @SpringBootTest classes, so other tests' own fixture plans are legitimately
		// present here — but DemoDataSeeder's exact unsuffixed keys ("free", "pro") must never appear,
		// or every other test class's own "free"/"pro"-prefixed plan would have collided on creation.
		assertThat(planAdminService.list().stream().map(p -> p.key())).doesNotContain("free", "pro");
	}

}
