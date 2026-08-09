package com.solovis.entitlement.core.error;

/** No account is declared with this external id (c19) — an error, never a denial. */
public final class UnknownAccountException extends RuntimeException {

    private final String accountExternalId;

    public UnknownAccountException(String accountExternalId) {
        super("No account is declared with external id '" + accountExternalId + "'.");
        this.accountExternalId = accountExternalId;
    }

    public String accountExternalId() {
        return accountExternalId;
    }
}
