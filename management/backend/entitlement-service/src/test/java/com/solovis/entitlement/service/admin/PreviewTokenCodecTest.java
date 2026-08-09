package com.solovis.entitlement.service.admin;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PreviewTokenCodecTest {

    @Test
    void sameInputsProduceTheSameToken() {
        var a = PreviewTokenCodec.compute("pro", Map.of("reports.monthly", "QUANTITY:75"), List.of("export.parquet"), 48211);
        var b = PreviewTokenCodec.compute("pro", Map.of("reports.monthly", "QUANTITY:75"), List.of("export.parquet"), 48211);
        assertThat(a).isEqualTo(b).startsWith("pv_");
    }

    @Test
    void aDifferentSnapshotVersionProducesADifferentToken() {
        var a = PreviewTokenCodec.compute("pro", Map.of("reports.monthly", "QUANTITY:75"), List.of(), 48211);
        var b = PreviewTokenCodec.compute("pro", Map.of("reports.monthly", "QUANTITY:75"), List.of(), 48212);
        assertThat(a).isNotEqualTo(b);
    }
}
