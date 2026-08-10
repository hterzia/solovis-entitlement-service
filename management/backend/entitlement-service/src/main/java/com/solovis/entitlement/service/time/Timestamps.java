package com.solovis.entitlement.service.time;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

/** Renders an Instant as ISO-8601 UTC with EXACTLY three fractional digits (contracts/README.md), never fewer. */
public final class Timestamps {

    private static final DateTimeFormatter FORMAT = new DateTimeFormatterBuilder()
        .appendInstant(3)
        .toFormatter();

    private Timestamps() {}

    public static String iso(Instant instant) {
        return FORMAT.format(instant);
    }
}
