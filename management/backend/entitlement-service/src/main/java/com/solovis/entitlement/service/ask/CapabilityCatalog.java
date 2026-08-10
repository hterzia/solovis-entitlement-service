package com.solovis.entitlement.service.ask;

import com.solovis.entitlement.service.store.CapabilityRow;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The active capabilities as the interpreter sees them: keys, display names and area grouping —
 * nothing else. This record and the question text are the only content that ever leaves the
 * service (spec §7).
 */
public record CapabilityCatalog(List<Entry> entries) {

	public record Entry(String key, String area, String displayName) {
	}

	public CapabilityCatalog {
		entries = List.copyOf(entries);
	}

	public static CapabilityCatalog from(List<CapabilityRow> activeRows) {
		return new CapabilityCatalog(activeRows.stream()
				.map(row -> new Entry(row.key(), row.area(), row.displayName()))
				.toList());
	}

	public boolean containsKey(String key) {
		return entries.stream().anyMatch(entry -> entry.key().equals(key));
	}

	/** One area per block, one capability per line — the prompt-facing rendering. */
	public String render() {
		Map<String, List<Entry>> byArea = entries.stream()
				.collect(Collectors.groupingBy(Entry::area, TreeMap::new, Collectors.toList()));
		StringBuilder out = new StringBuilder();
		byArea.forEach((area, grouped) -> {
			out.append(area).append(":\n");
			grouped.forEach(entry -> out.append("  ").append(entry.key())
					.append(" — ").append(entry.displayName()).append('\n'));
		});
		return out.toString();
	}
}
