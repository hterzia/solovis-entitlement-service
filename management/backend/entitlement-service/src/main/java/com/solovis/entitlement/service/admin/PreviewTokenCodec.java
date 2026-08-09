package com.solovis.entitlement.service.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import java.util.List;
import java.util.TreeMap;

/** A stateless digest binding an applied plan edit to the exact preview that was shown for it (c34). */
public final class PreviewTokenCodec {

    private PreviewTokenCodec() {}

    /** {@code set} and {@code unset} must already be canonicalised to plain strings by the caller (e.g. "QUANTITY:75", "SWITCH:true", capability keys sorted). */
    public static String compute(String planKey, Map<String, String> set, List<String> unset, long snapshotVersion) {
        var canonicalSet = new TreeMap<>(set);
        var canonicalUnset = unset.stream().sorted().toList();
        String material = planKey + "|" + canonicalSet + "|" + canonicalUnset + "|" + snapshotVersion;
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return "pv_" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
