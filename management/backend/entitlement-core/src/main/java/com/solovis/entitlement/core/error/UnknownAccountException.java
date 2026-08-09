package com.solovis.entitlement.core.error;

/**
 * No account is declared with this external id (c19) — an error, never a denial.
 *
 * <p>Not {@code final}: {@code entitlement-client} subclasses this to attach replica freshness
 * evidence, so a consumer catching this one type handles both a service answer and an SDK answer.
 * Replica concerns stay in the subclass — they are not domain state.
 */
public class UnknownAccountException extends RuntimeException {

    private final String accountExternalId;

    public UnknownAccountException(String accountExternalId) {
        super("No account is declared with external id '" + accountExternalId + "'.");
        this.accountExternalId = accountExternalId;
    }

    public String accountExternalId() {
        return accountExternalId;
    }
}
