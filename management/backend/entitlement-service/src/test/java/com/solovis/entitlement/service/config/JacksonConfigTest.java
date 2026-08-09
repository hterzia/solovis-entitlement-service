package com.solovis.entitlement.service.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class JacksonConfigTest {

    @Autowired ObjectMapper objectMapper;

    @Test
    void serialisesInstantAsIso8601WithMillis() throws Exception {
        String json = objectMapper.writeValueAsString(Instant.parse("2026-08-09T14:03:11.482Z"));
        assertThat(json).isEqualTo("\"2026-08-09T14:03:11.482Z\"");
    }

    @Test
    void omitsNullFieldsByDefault() throws Exception {
        record Sample(String present, String absent) {}
        String json = objectMapper.writeValueAsString(new Sample("value", null));
        assertThat(json).doesNotContain("absent");
    }
}
