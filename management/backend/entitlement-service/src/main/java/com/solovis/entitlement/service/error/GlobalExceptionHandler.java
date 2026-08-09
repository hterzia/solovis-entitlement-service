package com.solovis.entitlement.service.error;

import com.solovis.entitlement.core.error.RetiredCapabilityException;
import com.solovis.entitlement.core.error.UnknownAccountException;
import com.solovis.entitlement.core.error.UnknownCapabilityException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import java.net.URI;
import java.util.List;
import java.util.Map;

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

    @ExceptionHandler(EntitlementApiException.class)
    public ProblemDetail handleApiException(EntitlementApiException ex, HttpServletRequest request) {
        return problem(ex.errorCode(), ex.getMessage(), request, ex.extraProperties());
    }

    @ExceptionHandler(UnknownAccountException.class)
    public ProblemDetail handleUnknownAccount(UnknownAccountException ex, HttpServletRequest request) {
        return problem(ErrorCode.UNKNOWN_ACCOUNT, ex.getMessage(), request,
            Map.of("account", ex.accountExternalId()));
    }

    @ExceptionHandler(UnknownCapabilityException.class)
    public ProblemDetail handleUnknownCapability(UnknownCapabilityException ex, HttpServletRequest request) {
        return problem(ErrorCode.UNKNOWN_CAPABILITY, ex.getMessage(), request,
            Map.of("capability", ex.capabilityKey()));
    }

    @ExceptionHandler(RetiredCapabilityException.class)
    public ProblemDetail handleRetiredCapability(RetiredCapabilityException ex, HttpServletRequest request) {
        return problem(ErrorCode.RETIRED_CAPABILITY, ex.getMessage(), request,
            Map.of("capability", ex.capabilityKey()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<String> violations = ex.getBindingResult().getFieldErrors().stream()
            .map(FieldError::toString).toList();
        ProblemDetail problem = problem(ErrorCode.VALIDATION_FAILED, "Request failed validation.", request,
            Map.of("violations", violations));
        return handleExceptionInternal(ex, problem, headers, HttpStatusCode.valueOf(problem.getStatus()), request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(ErrorCode.VALIDATION_FAILED, "The request body is missing or malformed.",
            request, Map.of());
        return handleExceptionInternal(ex, problem, headers, HttpStatusCode.valueOf(problem.getStatus()), request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception while processing {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", request, Map.of());
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
