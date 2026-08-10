package com.solovis.entitlement.service.ask;

/** Interpretation is not possible right now — unconfigured, unreachable, or answering unusably. */
public class AskUnavailableException extends RuntimeException {

	public AskUnavailableException(String message) {
		super(message);
	}

	public AskUnavailableException(String message, Throwable cause) {
		super(message, cause);
	}
}
