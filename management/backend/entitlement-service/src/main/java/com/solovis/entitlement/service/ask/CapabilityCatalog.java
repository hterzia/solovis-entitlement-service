package com.solovis.entitlement.service.ask;

import com.solovis.entitlement.service.store.CapabilityRow;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The capabilities as the interpreter sees them: keys, display names, area grouping and whether
 * each is retired — nothing else. This record and the question text are the only content that
 * ever leaves the service (spec §7).
 *
 * <p>Sorted by key in {@link #from}: {@code allCapabilities} has no {@code ORDER BY}, and a
 * prompt that varies run to run would make interpretation irreproducible.
 */
public record CapabilityCatalog(List<Entry> entries) {

	public record Entry(String key, String area, String displayName, boolean retired) {
	}

	public CapabilityCatalog {
		entries = List.copyOf(entries);
	}

	public static CapabilityCatalog from(List<CapabilityRow> rows) {
		return new CapabilityCatalog(rows.stream()
				.sorted(Comparator.comparing(CapabilityRow::key))
				.map(row -> new Entry(row.key(), row.area(), row.displayName(), "RETIRED".equals(row.status())))
				.toList());
	}

	public boolean containsKey(String key) {
		return entries.stream().anyMatch(entry -> entry.key().equals(key));
	}

	public Optional<Entry> find(String key) {
		return entries.stream().filter(entry -> entry.key().equals(key)).findFirst();
	}

	/** One area per block, one capability per line — the prompt-facing rendering. */
	public String render() {
		Map<String, List<Entry>> byArea = entries.stream()
				.collect(Collectors.groupingBy(Entry::area, TreeMap::new, Collectors.toList()));
		StringBuilder out = new StringBuilder();
		byArea.forEach((area, grouped) -> {
			out.append(area).append(":\n");
			grouped.forEach(entry -> {
				out.append("  ").append(entry.key()).append(" — ").append(entry.displayName());
				if (entry.retired()) {
					out.append(" (retired)");
				}
				out.append('\n');
			});
		});
		return out.toString();
	}
}
