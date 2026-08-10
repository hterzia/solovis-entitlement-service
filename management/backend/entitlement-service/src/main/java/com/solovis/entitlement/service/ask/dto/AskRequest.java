package com.solovis.entitlement.service.ask.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The 500-character cap bounds both the per-call cost and what a runaway paste can send out. */
public record AskRequest(
		@NotBlank @Size(max = 500) String question) {
}
