package com.solovis.entitlement.service.time;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code now()} in the service must come through the injected {@link java.time.Clock}.
 *
 * <p>This is enforced rather than asked for because 002's boundary criteria (c11–c13) are
 * demonstrated by driving the clock across a midnight. A single direct call to the wall clock makes
 * such a test <em>flaky</em> rather than failing — it would pass on most runs and fail near a
 * boundary, which is the hardest kind of defect to attribute.
 *
 * <p>Written as a source scan rather than an ArchUnit rule to keep the module's dependency list
 * unchanged; the check is crude but the failure it prevents is expensive.
 */
class NoDirectClockAccessTest {

	private static final Path MAIN_SOURCES = Path.of("src/main/java");

	/** The one place allowed to read the wall clock: it is what builds the Clock everything else injects. */
	private static final String BOOTSTRAP = "ClockConfig.java";

	private static final List<Pattern> BANNED = List.of(
			Pattern.compile("\\bInstant\\.now\\s*\\(\\s*\\)"),
			Pattern.compile("\\bLocalDate\\.now\\s*\\(\\s*\\)"),
			Pattern.compile("\\bLocalDateTime\\.now\\s*\\(\\s*\\)"),
			Pattern.compile("\\bLocalTime\\.now\\s*\\(\\s*\\)"),
			Pattern.compile("\\bZonedDateTime\\.now\\s*\\(\\s*\\)"),
			Pattern.compile("\\bOffsetDateTime\\.now\\s*\\(\\s*\\)"),
			Pattern.compile("\\bSystem\\.currentTimeMillis\\s*\\(\\s*\\)"),
			Pattern.compile("\\bClock\\.systemUTC\\s*\\(\\s*\\)"),
			Pattern.compile("\\bClock\\.systemDefaultZone\\s*\\(\\s*\\)"),
			Pattern.compile("\\bnew\\s+Date\\s*\\(\\s*\\)"));

	@Test
	void serviceCodeReadsTheClockOnlyThroughTheInjectedClockBean() throws IOException {
		List<String> offences = new ArrayList<>();
		int scanned = 0;

		try (Stream<Path> sources = Files.walk(MAIN_SOURCES)) {
			for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
				scanned++;
				if (source.getFileName().toString().equals(BOOTSTRAP)) {
					continue;
				}
				List<String> lines = Files.readAllLines(source);
				for (int i = 0; i < lines.size(); i++) {
					String line = lines.get(i);
					if (line.stripLeading().startsWith("*") || line.stripLeading().startsWith("//")) {
						continue;
					}
					for (Pattern banned : BANNED) {
						if (banned.matcher(line).find()) {
							offences.add("%s:%d  %s".formatted(source, i + 1, line.strip()));
						}
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
				.as("read the wall clock through the injected Clock bean — see ClockConfig for why")
				.isEmpty();
	}
}
