package com.solovis.entitlement.service.error;

import java.util.regex.Pattern;

/** Parses caller-supplied {@code <prefix><id>} references (e.g. {@code ovr_42}, {@code aud_7}), or throws VALIDATION_FAILED. */
public final class RefId {

    /** Canonical numeric suffix only: {@code 0} or a digit sequence with no leading zero, no sign. */
    private static final Pattern CANONICAL_ID = Pattern.compile("0|[1-9][0-9]*");

    private RefId() {}

    public static long parse(String ref, String prefix) {
        if (ref != null && ref.startsWith(prefix)) {
            String suffix = ref.substring(prefix.length());
            if (CANONICAL_ID.matcher(suffix).matches()) {
                try {
                    return Long.parseLong(suffix);
                } catch (NumberFormatException ignored) {
                    // falls through to the exception below (e.g. all-digit suffix overflows a long)
                }
            }
        }
        throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED,
            "'" + ref + "' is not a valid '" + prefix + "<id>' reference.");
    }
}
