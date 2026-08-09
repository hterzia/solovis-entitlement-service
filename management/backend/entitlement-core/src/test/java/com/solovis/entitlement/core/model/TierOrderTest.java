package com.solovis.entitlement.core.model;

import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TierOrderTest {

    private final TierOrder support = new TierOrder(List.of(
        new TierOrder.TierDefinition("community", 0, "Community"),
        new TierOrder.TierDefinition("standard", 1, "Standard"),
        new TierOrder.TierDefinition("gold", 2, "Gold")
    ));

    @Test
    void looksUpOrdinalByKey() {
        assertThat(support.ordinalOf("gold")).isEqualTo(OptionalInt.of(2));
        assertThat(support.ordinalOf("unknown")).isEqualTo(OptionalInt.empty());
    }

    @Test
    void declaresReportsMembership() {
        assertThat(support.declares("community")).isTrue();
        assertThat(support.declares("platinum")).isFalse();
    }

    @Test
    void maxOrdinalIsTheHighestDeclared() {
        assertThat(support.maxOrdinal()).isEqualTo(2);
    }

    @Test
    void appendingAddsAboveTheCurrentMaximum() {
        var extended = support.appending("platinum", "Platinum");
        assertThat(extended.ordinalOf("platinum")).isEqualTo(OptionalInt.of(3));
        assertThat(support.declares("platinum")).isFalse(); // original is untouched
    }

    @Test
    void rejectsNonContiguousOrdinals() {
        assertThatThrownBy(() -> new TierOrder(List.of(
            new TierOrder.TierDefinition("a", 0, "A"),
            new TierOrder.TierDefinition("b", 2, "B")
        ))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDuplicateOrdinalsOrKeys() {
        assertThatThrownBy(() -> new TierOrder(List.of(
            new TierOrder.TierDefinition("a", 0, "A"),
            new TierOrder.TierDefinition("a", 1, "A again")
        ))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void noneIsEmptyAndRejectsAppending() {
        assertThat(TierOrder.NONE.declares("anything")).isFalse();
        assertThat(TierOrder.NONE.maxOrdinal()).isEqualTo(-1);
    }
}
