package com.solovis.entitlement.service.ask;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityCatalogTest {

	@Test
	void rendersGroupedByAreaOneCapabilityPerLine() {
		CapabilityCatalog catalog = new CapabilityCatalog(List.of(
				new CapabilityCatalog.Entry("export.parquet", "export", "Parquet export"),
				new CapabilityCatalog.Entry("api.access", "api", "API access"),
				new CapabilityCatalog.Entry("export.pdf", "export", "PDF export")));

		assertThat(catalog.render()).isEqualTo("""
				api:
				  api.access — API access
				export:
				  export.parquet — Parquet export
				  export.pdf — PDF export
				""");
	}

	@Test
	void containsKeyIsExact() {
		CapabilityCatalog catalog = new CapabilityCatalog(List.of(
				new CapabilityCatalog.Entry("export.parquet", "export", "Parquet export")));

		assertThat(catalog.containsKey("export.parquet")).isTrue();
		assertThat(catalog.containsKey("export.Parquet")).isFalse();
		assertThat(catalog.containsKey("export")).isFalse();
	}
}
