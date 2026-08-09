package com.solovis.entitlement.service.error;

/** Parses caller-supplied {@code <prefix><id>} references (e.g. {@code ovr_42}, {@code aud_7}), or throws VALIDATION_FAILED. */
public final class RefId {

    private RefId() {}

    public static long parse(String ref, String prefix) {
        if (ref != null && ref.startsWith(prefix)) {
            try {
                return Long.parseLong(ref.substring(prefix.length()));
            } catch (NumberFormatException ignored) {
                // falls through to the exception below
            }
        }
        throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED,
            "'" + ref + "' is not a valid '" + prefix + "<id>' reference.");
    }
}
