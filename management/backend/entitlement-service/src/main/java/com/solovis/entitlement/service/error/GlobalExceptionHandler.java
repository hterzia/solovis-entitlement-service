package com.solovis.entitlement.service.error;

import com.solovis.entitlement.core.error.RetiredCapabilityException;
import com.solovis.entitlement.core.error.UnknownAccountException;
import com.solovis.entitlement.core.error.UnknownCapabilityException;
import com.solovis.entitlement.service.api.SnapshotVersionHeader;
import com.solovis.entitlement.service.snapshot.SnapshotHolder;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Extends {@link ResponseEntityExceptionHandler} so Spring's own MVC exceptions (404s from
 * unmatched routes, 400s from bad query params, 405s, 415s, etc.) keep their correct status
 * instead of being swallowed by the bare {@code Exception} fallback below. {@code
 * MethodArgumentNotValidException} and {@code HttpMessageNotReadableException} are handled by
 * overriding the corresponding protected hooks rather than adding new {@code @ExceptionHandler}
 * methods for them, since {@link ResponseEntityExceptionHandler#handleException} already claims
 * those two exception types — declaring them again would be an ambiguous mapping at startup.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final SnapshotHolder snapshotHolder;

    public GlobalExceptionHandler(SnapshotHolder snapshotHolder) {
        this.snapshotHolder = snapshotHolder;
    }

    @ExceptionHandler(EntitlementApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(EntitlementApiException ex, HttpServletRequest request) {
        return respond(problem(ex.errorCode(), ex.getMessage(), request, ex.extraProperties()), request);
    }

    @ExceptionHandler(UnknownAccountException.class)
    public ResponseEntity<ProblemDetail> handleUnknownAccount(UnknownAccountException ex, HttpServletRequest request) {
        return respond(problem(ErrorCode.UNKNOWN_ACCOUNT, ex.getMessage(), request,
            Map.of("account", ex.accountExternalId())), request);
    }

    @ExceptionHandler(UnknownCapabilityException.class)
    public ResponseEntity<ProblemDetail> handleUnknownCapability(UnknownCapabilityException ex, HttpServletRequest request) {
        return respond(problem(ErrorCode.UNKNOWN_CAPABILITY, ex.getMessage(), request,
            Map.of("capability", ex.capabilityKey())), request);
    }

    @ExceptionHandler(RetiredCapabilityException.class)
    public ResponseEntity<ProblemDetail> handleRetiredCapability(RetiredCapabilityException ex, HttpServletRequest request) {
        return respond(problem(ErrorCode.RETIRED_CAPABILITY, ex.getMessage(), request,
            Map.of("capability", ex.capabilityKey())), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> violations = ex.getBindingResult().getFieldErrors().stream()
            .map(FieldError::toString).toList();
        ProblemDetail problem = problem(ErrorCode.VALIDATION_FAILED, "Request failed validation.", request,
            Map.of("violations", violations));
        return handleExceptionInternal(ex, problem, withSnapshotVersion(headers, request),
            HttpStatusCode.valueOf(problem.getStatus()), request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(ErrorCode.VALIDATION_FAILED, "The request body is missing or malformed.",
            request, Map.of());
        return handleExceptionInternal(ex, problem, withSnapshotVersion(headers, request),
            HttpStatusCode.valueOf(problem.getStatus()), request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(ErrorCode.VALIDATION_FAILED, "Request failed validation.", request,
            Map.of("violations", List.of("'" + ex.getPropertyName() + "' has an invalid value.")));
        return handleExceptionInternal(ex, problem, withSnapshotVersion(headers, request),
            HttpStatusCode.valueOf(problem.getStatus()), request);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(ErrorCode.VALIDATION_FAILED, "Request failed validation.", request,
            Map.of("violations", List.of("'" + ex.getParameterName() + "' is required.")));
        return handleExceptionInternal(ex, problem, withSnapshotVersion(headers, request),
            HttpStatusCode.valueOf(problem.getStatus()), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrityViolation(DataIntegrityViolationException ex,
            HttpServletRequest request) {
        return respond(problem(ErrorCode.WRITE_CONFLICT, "A concurrent change conflicted with an existing record.",
            request, Map.of()), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception while processing {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(problem(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", request, Map.of()), request);
    }

    /**
     * Wraps a problem body, adding the {@code /v1} snapshot-version header where the contract calls
     * for it. contracts/README.md promises the header on every {@code /v1} response, and an error is
     * where a caller most needs it: without it, a 404 caused by a lagging read and a 404 caused by a
     * genuinely absent account are indistinguishable.
     */
    /**
     * The {@link ResponseEntityExceptionHandler} overrides above route through Spring's own
     * {@code handleExceptionInternal}, which takes headers as a parameter rather than letting the
     * handler build a {@link ResponseEntity}. Same contract, different seam.
     */
    private HttpHeaders withSnapshotVersion(HttpHeaders headers, WebRequest request) {
        String uri = request instanceof ServletWebRequest servletRequest
            ? servletRequest.getRequest().getRequestURI()
            : null;
        return snapshotVersionHeader(uri).map(version -> {
            HttpHeaders merged = new HttpHeaders();
            merged.addAll(headers);
            merged.set(SnapshotVersionHeader.NAME, version);
            return merged;
        }).orElse(headers);
    }

    private ResponseEntity<ProblemDetail> respond(ProblemDetail problem, HttpServletRequest request) {
        var response = ResponseEntity.status(problem.getStatus());
        snapshotVersionHeader(request.getRequestURI())
            .ifPresent(version -> response.header(SnapshotVersionHeader.NAME, version));
        return response.body(problem);
    }

    /**
     * The current version, but only for {@code /v1} paths. Errors carry no body resolved against a
     * snapshot, so "current at the moment of failure" is the only meaningful answer — and it is the
     * honest one, since the request never reached a resolution step. {@code /admin/v1} is excluded:
     * it backs the SPA and may change with it, so the header must not become a commitment there.
     */
    private Optional<String> snapshotVersionHeader(String requestUri) {
        if (requestUri == null || !requestUri.startsWith("/v1/")) {
            return Optional.empty();
        }
        try {
            return Optional.of(String.valueOf(snapshotHolder.current().snapshotVersion()));
        } catch (IllegalStateException noSnapshotYet) {
            // The holder is empty only before SnapshotStartup completes, and the web connector is
            // not meant to accept traffic that early. If it somehow does, an error response missing
            // one header is a far better outcome than the error handler itself throwing.
            return Optional.empty();
        }
    }

    private ProblemDetail problem(ErrorCode code, String detail, HttpServletRequest request, Map<String, Object> extra) {
        return problem(code, detail, request.getRequestURI(), extra);
    }

    private ProblemDetail problem(ErrorCode code, String detail, WebRequest request, Map<String, Object> extra) {
        String uri = request instanceof ServletWebRequest servletRequest
            ? servletRequest.getRequest().getRequestURI()
            : request.getDescription(false);
        return problem(code, detail, uri, extra);
    }

    private ProblemDetail problem(ErrorCode code, String detail, String requestUri, Map<String, Object> extra) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(code.type()));
        problem.setTitle(code.title());
        problem.setInstance(URI.create(requestUri));
        extra.forEach(problem::setProperty);
        return problem;
    }
}
