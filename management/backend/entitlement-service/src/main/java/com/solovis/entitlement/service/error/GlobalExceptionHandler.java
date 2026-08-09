package com.solovis.entitlement.service.error;

import com.solovis.entitlement.core.error.RetiredCapabilityException;
import com.solovis.entitlement.core.error.UnknownAccountException;
import com.solovis.entitlement.core.error.UnknownCapabilityException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.net.URI;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

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

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBeanValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<String> violations = ex.getBindingResult().getFieldErrors().stream()
            .map(FieldError::toString).toList();
        return problem(ErrorCode.VALIDATION_FAILED, "Request failed validation.", request,
            Map.of("violations", violations));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleMalformedBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return problem(ErrorCode.VALIDATION_FAILED, "The request body is missing or malformed.", request, Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception while processing {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(ErrorCode.INTERNAL_ERROR, "An unexpected error occurred.", request, Map.of());
    }

    private ProblemDetail problem(ErrorCode code, String detail, HttpServletRequest request, Map<String, Object> extra) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(code.type()));
        problem.setTitle(code.title());
        problem.setInstance(URI.create(request.getRequestURI()));
        extra.forEach(problem::setProperty);
        return problem;
    }
}
