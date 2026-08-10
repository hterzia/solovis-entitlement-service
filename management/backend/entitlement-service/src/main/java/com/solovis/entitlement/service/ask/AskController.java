package com.solovis.entitlement.service.ask;

import com.solovis.entitlement.service.ask.dto.AskRequest;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Sits beside the classic {@code GET /admin/v1/check}. Role (future): Viewer. Asking is
 * read-only — no audit writes, no snapshot involvement, nothing on any decision path.
 */
@RestController
public class AskController {

	private final AskService askService;

	public AskController(AskService askService) {
		this.askService = askService;
	}

	@PostMapping("/admin/v1/check/ask")
	public ResponseEntity<Object> ask(@Valid @RequestBody AskRequest request) {
		if (!askService.available()) {
			return unavailable();
		}
		try {
			return ResponseEntity.ok(askService.ask(request.question()));
		}
		catch (AskUnavailableException e) {
			return unavailable();
		}
	}

	private ResponseEntity<Object> unavailable() {
		ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);
		problem.setType(URI.create("entitlement/ask-unavailable"));
		problem.setTitle("Ask unavailable");
		problem.setDetail("The plain-English checker is not available right now; use the account and capability pickers.");
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
	}
}
