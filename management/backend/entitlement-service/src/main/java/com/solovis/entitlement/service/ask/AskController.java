package com.solovis.entitlement.service.ask;

import com.solovis.entitlement.service.ask.dto.AskRequest;
import com.solovis.entitlement.service.ask.dto.AskResponse;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sits beside the classic {@code GET /admin/v1/check}. Role (future): Viewer. Asking is
 * read-only — no audit writes, no snapshot involvement, nothing on any decision path.
 * Unavailability is a thrown {@link AskUnavailableException}, handled by
 * {@code GlobalExceptionHandler} — this controller has no error-shaping of its own.
 */
@RestController
public class AskController {

	private final AskService askService;

	public AskController(AskService askService) {
		this.askService = askService;
	}

	@PostMapping("/admin/v1/check/ask")
	public AskResponse ask(@Valid @RequestBody AskRequest request) {
		return askService.ask(request.question());
	}
}
