package com.solovis.entitlement.core.model;

import java.util.List;
import java.util.OptionalInt;

/**
 * The declared, ordered levels of a TIER capability (c3). Ordinals are contiguous from 0.
 * Tiers may only be appended above the current maximum — inserting would renumber existing
 * stored values and silently rewrite what they used to mean (data-model.md capability_tier).
 */
public record TierOrder(List<TierDefinition> tiers) {

    public static final TierOrder NONE = new TierOrder(List.of());

    public record TierDefinition(String tierKey, int ordinal, String displayName) {}

    public TierOrder {
        tiers = List.copyOf(tiers);
        var seenKeys = new java.util.HashSet<String>();
        var seenOrdinals = new java.util.HashSet<Integer>();
        for (var tier : tiers) {
            if (!seenKeys.add(tier.tierKey())) {
                throw new IllegalArgumentException("Duplicate tier key '" + tier.tierKey() + "'.");
            }
            if (!seenOrdinals.add(tier.ordinal())) {
                throw new IllegalArgumentException("Duplicate tier ordinal " + tier.ordinal() + ".");
            }
        }
        var sortedOrdinals = tiers.stream().map(TierDefinition::ordinal).sorted().toList();
        for (int i = 0; i < sortedOrdinals.size(); i++) {
            if (sortedOrdinals.get(i) != i) {
                throw new IllegalArgumentException("Tier ordinals must be contiguous from 0: " + sortedOrdinals);
            }
        }
    }

    public OptionalInt ordinalOf(String tierKey) {
        return tiers.stream()
            .filter(t -> t.tierKey().equals(tierKey))
            .mapToInt(TierDefinition::ordinal)
            .findFirst();
    }

    public boolean declares(String tierKey) {
        return ordinalOf(tierKey).isPresent();
    }

    public int maxOrdinal() {
        return tiers.stream().mapToInt(TierDefinition::ordinal).max().orElse(-1);
    }

    public TierOrder appending(String tierKey, String displayName) {
        var next = new java.util.ArrayList<>(tiers);
        next.add(new TierDefinition(tierKey, maxOrdinal() + 1, displayName));
        return new TierOrder(next);
    }
}
