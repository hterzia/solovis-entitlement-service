package com.solovis.entitlement.service.ask;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityCatalogTest {

	@Test
	void rendersGroupedByAreaOneCapabilityPerLine() {
		CapabilityCatalog catalog = new CapabilityCatalog(List.of(
				new CapabilityCatalog.Entry("export.parquet", "export", "Parquet export", false),
				new CapabilityCatalog.Entry("api.access", "api", "API access", false),
				new CapabilityCatalog.Entry("export.pdf", "export", "PDF export", false)));

		assertThat(catalog.render()).isEqualTo("""
				api:
				  api.access — API access
				export:
				  export.parquet — Parquet export
				  export.pdf — PDF export
				""");
	}

	@Test
	void marksARetiredEntryInTheRendering() {
		CapabilityCatalog catalog = new CapabilityCatalog(List.of(
				new CapabilityCatalog.Entry("export.csv", "export", "CSV export", true)));

		assertThat(catalog.render()).isEqualTo("""
				export:
				  export.csv — CSV export (retired)
				""");
	}

	@Test
	void containsKeyIsExact() {
		CapabilityCatalog catalog = new CapabilityCatalog(List.of(
				new CapabilityCatalog.Entry("export.parquet", "export", "Parquet export", false)));

		assertThat(catalog.containsKey("export.parquet")).isTrue();
		assertThat(catalog.containsKey("export.Parquet")).isFalse();
		assertThat(catalog.containsKey("export")).isFalse();
	}

	@Test
	void findLocatesAnEntryByExactKey() {
		CapabilityCatalog catalog = new CapabilityCatalog(List.of(
				new CapabilityCatalog.Entry("export.parquet", "export", "Parquet export", false),
				new CapabilityCatalog.Entry("export.csv", "export", "CSV export", true)));

		assertThat(catalog.find("export.csv")).hasValueSatisfying(entry -> assertThat(entry.retired()).isTrue());
		assertThat(catalog.find("export.parquet")).hasValueSatisfying(entry -> assertThat(entry.retired()).isFalse());
		assertThat(catalog.find("no.such.key")).isEmpty();
	}

	@Test
	void fromSortsEntriesByKeyRegardlessOfInputOrder() {
		CapabilityCatalog catalog = CapabilityCatalog.from(List.of(
				row("export.pdf", "export", "PDF export", "ACTIVE"),
				row("api.access", "api", "API access", "ACTIVE"),
				row("export.csv", "export", "CSV export", "RETIRED")));

		assertThat(catalog.entries()).extracting(CapabilityCatalog.Entry::key)
				.containsExactly("api.access", "export.csv", "export.pdf");
		assertThat(catalog.find("export.csv")).hasValueSatisfying(entry -> assertThat(entry.retired()).isTrue());
	}

	private static com.solovis.entitlement.service.store.CapabilityRow row(
			String key, String area, String displayName, String status) {
		return new com.solovis.entitlement.service.store.CapabilityRow(
				1L, key, area, displayName, null, "SWITCH", false, null, false, null, false, null, null,
				status, null, null, null);
	}
}
