package com.solovis.entitlement.service.time;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code Instant.toString()} silently drops its fractional part when the instant lands exactly on
 * a whole second, which breaks the contract's promise (contracts/README.md, "All bodies are...")
 * that every timestamp is ISO-8601 UTC with milliseconds — and, since audit time-range filtering
 * compares these strings lexicographically, corrupts ordering when it happens. Every producer must
 * go through {@link Timestamps#iso} instead.
 *
 * <p>Written as a source scan rather than an ArchUnit rule to keep the module's dependency list
 * unchanged; the check is crude but the failure it prevents is expensive.
 */
class MillisecondTimestampFormatTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    private static final String BANNED = "instant().toString()";

    @Test
    void noProducerCallsInstantToStringDirectly() throws IOException {
        List<String> offences = new ArrayList<>();
        int scanned = 0;

        try (Stream<Path> sources = Files.walk(MAIN_SOURCES)) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                List<String> lines = Files.readAllLines(source);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i);
                    if (line.stripLeading().startsWith("*") || line.stripLeading().startsWith("//")) {
                        continue;
                    }
                    if (line.contains(BANNED)) {
                        offences.add("%s:%d  %s".formatted(source, i + 1, line.strip()));
                    }
                }
            }
        }

        // Without this the test passes vacuously if the working directory ever moves, which would be
        // worse than not having it — a green check that stopped looking.
        assertThat(scanned)
            .as("source scan found nothing under %s; the check is not running", MAIN_SOURCES.toAbsolutePath())
            .isGreaterThan(50);

        assertThat(offences)
            .as("format instants through Timestamps.iso(...) — see Timestamps for why 'instant().toString()' loses the millisecond fraction on whole seconds")
            .isEmpty();
    }
}
