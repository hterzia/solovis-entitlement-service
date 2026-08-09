package com.solovis.entitlement.client.wire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.core.model.OverrideKind;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class WireMapperTest {

    private static final Instant PUBLISHED = Instant.parse("2026-08-09T14:03:10.900Z");

    @Test
    void switchValuesRoundTripThroughTheWireEncoding() {
        var dto = ClientJson.MAPPER.readValue("{\"type\":\"SWITCH\",\"enabled\":true}", ValueDto.class);

        assertThat(WireMapper.toValue(dto)).isEqualTo(new EntitlementValue.Switch(true));
    }

    @Test
    void aBoundedQuantityCarriesItsAmount() {
        var dto = ClientJson.MAPPER.readValue("{\"type\":\"QUANTITY\",\"amount\":50}", ValueDto.class);

        assertThat(WireMapper.toValue(dto)).isEqualTo(EntitlementValue.Quantity.of(50));
    }

    @Test
    void unlimitedIsADistinctVariantAndNeverALargeNumber() {
        var dto = ClientJson.MAPPER.readValue("{\"type\":\"QUANTITY\",\"unlimited\":true}", ValueDto.class);

        var value = WireMapper.toValue(dto);

        assertThat(value).isEqualTo(EntitlementValue.Quantity.unbounded());
        assertThat(((EntitlementValue.Quantity) value).unlimited()).isTrue();
        assertThat(((EntitlementValue.Quantity) value).amount()).isZero();
    }

    @Test
    void tiersCarryTheirDeclaredOrdinal() {
        var dto = ClientJson.MAPPER.readValue(
            "{\"type\":\"TIER\",\"tier\":\"gold\",\"ordinal\":2}", ValueDto.class);

        assertThat(WireMapper.toValue(dto)).isEqualTo(new EntitlementValue.Tier("gold", 2));
    }

    @Test
    void aQuantityWithNeitherAmountNorUnlimitedIsAMalformedFeedAndIsRejectedOutright() {
        var dto = new ValueDto("QUANTITY", null, null, null, null, null);

        assertThatThrownBy(() -> WireMapper.toValue(dto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("QUANTITY");
    }

    @Test
    void aCapabilityLineWithoutOffValueOrTiersParsesBecauseTheServiceOmitsRatherThanNullsThem() {
        var json = """
            {"kind":"capability","key":"api.access","area":"api","valueType":"SWITCH",
             "default":{"type":"SWITCH","enabled":false},"status":"ACTIVE"}""";
        var line = ClientJson.MAPPER.readValue(json, FeedDtos.CapabilityLine.class);

        var capability = WireMapper.toCapability(line, PUBLISHED);

        assertThat(capability.key().value()).isEqualTo("api.access");
        assertThat(capability.status()).isEqualTo(Capability.Status.ACTIVE);
        assertThat(capability.tierOrder().tiers()).isEmpty();
    }

    @Test
    void aRetiredCapabilityBorrowsTheFeedsPublishedAtBecauseTheLineCarriesNoRetiredAt() {
        var json = """
            {"kind":"capability","key":"legacy.export","area":"legacy","valueType":"SWITCH",
             "default":{"type":"SWITCH","enabled":false},"status":"RETIRED"}""";
        var line = ClientJson.MAPPER.readValue(json, FeedDtos.CapabilityLine.class);

        var capability = WireMapper.toCapability(line, PUBLISHED);

        assertThat(capability.isRetired()).isTrue();
        assertThat(capability.retiredAt()).isEqualTo(PUBLISHED);
    }

    @Test
    void tierCapabilitiesRebuildTheirDeclaredTotalOrder() {
        var json = """
            {"kind":"capability","key":"support.level","area":"support","valueType":"TIER",
             "default":{"type":"TIER","tier":"community","ordinal":0},
             "tiers":[{"tier":"community","ordinal":0,"displayName":"Community"},
                      {"tier":"standard","ordinal":1,"displayName":"Standard"},
                      {"tier":"gold","ordinal":2,"displayName":"Gold"}],
             "status":"ACTIVE"}""";
        var line = ClientJson.MAPPER.readValue(json, FeedDtos.CapabilityLine.class);

        var capability = WireMapper.toCapability(line, PUBLISHED);

        assertThat(capability.tierOrder().ordinalOf("gold")).hasValue(2);
        assertThat(capability.tierOrder().maxOrdinal()).isEqualTo(2);
    }

    @Test
    void anOverrideLineCarriesItsKindOnOverrideKindNotKindWhichIsTheLineDiscriminator() {
        var json = """
            {"kind":"override","ref":"ovr_4471","account":"acct_9931","capability":"reports.monthly",
             "overrideKind":"GRANT","value":{"type":"QUANTITY","amount":200}}""";
        var line = ClientJson.MAPPER.readValue(json, FeedDtos.OverrideLine.class);

        var override = WireMapper.toOverride(line);

        assertThat(override.kind()).isEqualTo(OverrideKind.GRANT);
        assertThat(override.id()).hasValue(4471L);
        assertThat(override.accountExternalId()).isEqualTo("acct_9931");
    }

    @Test
    void aReplicaOverrideHoldsNoReasonNoAuthorAndNoTimestampBecauseTraceDataNeverReachesAReplica() {
        var line = new FeedDtos.OverrideLine(
            "override", "ovr_1", "acct_1", "seats.limit", "HOLD", new ValueDto("QUANTITY", null, 0L, null, null, null));

        var override = WireMapper.toOverride(line);

        assertThat(override.reason()).isEmpty();
        assertThat(override.createdBy()).isEmpty();
        assertThat(override.createdAt()).isEmpty();
    }

    @Test
    void refsParseToTheNumericIdSoIncrementalRemovalCanFindTheOverrideAgain() {
        assertThat(WireMapper.refToId("ovr_4471")).isEqualTo(4471L);
    }

    @Test
    void aRefInAnUnexpectedShapeIsAMalformedFeedRatherThanASilentZero() {
        assertThatThrownBy(() -> WireMapper.refToId("4471"))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
