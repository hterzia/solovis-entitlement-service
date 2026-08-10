package com.solovis.entitlement.service.store;

/** Builds a SQL LIKE '%...%' pattern where the caller's %, _, and \ are literal, not wildcards. */
public final class SqlLike {

    private SqlLike() {}

    public static String contains(String q) {
        String escaped = q
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
