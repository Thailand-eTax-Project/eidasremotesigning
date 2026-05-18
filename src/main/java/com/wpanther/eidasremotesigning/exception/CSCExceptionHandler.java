package com.wpanther.eidasremotesigning.exception;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.context.request.WebRequest;

import com.wpanther.eidasremotesigning.dto.csc.CSCErrorResponse;
import com.wpanther.eidasremotesigning.util.CSCConstants;

import lombok.extern.slf4j.Slf4j;

/**
 * Exception handler for CSC API endpoints
 * Formats error responses according to the CSC API specification
 */
@ControllerAdvice(basePackages = "com.wpanther.eidasremotesigning.controller")
@Order(1) // Higher priority than the global exception handler
@Slf4j
public class CSCExceptionHandler {

    /**
     * Handle CSC API exceptions for certificate operations
     */
    @ExceptionHandler(CertificateException.class)
    public ResponseEntity<CSCErrorResponse> handleCertificateException(CertificateException ex, WebRequest request) {
        log.error("Certificate error in CSC API", ex);
        return createCSCErrorResponse(CSCConstants.ERROR_CREDENTIAL_NOT_FOUND, ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    /**
     * Handle CSC API exceptions for signing operations
     */
    @ExceptionHandler(SigningException.class)
    public ResponseEntity<CSCErrorResponse> handleSigningException(SigningException ex, WebRequest request) {
        log.error("Signing error in CSC API", ex);
        return createCSCErrorResponse(CSCConstants.ERROR_SIGNING_ERROR, ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    /**
     * Handle CSC API exceptions for client registration
     */
    @ExceptionHandler(ClientRegistrationException.class)
    public ResponseEntity<CSCErrorResponse> handleClientRegistrationException(
            ClientRegistrationException ex, WebRequest request) {
        log.error("Client registration error in CSC API", ex);
        return createCSCErrorResponse(CSCConstants.ERROR_INVALID_REQUEST, ex.getMessage(), HttpStatus.BAD_REQUEST);
    }

    /**
     * Handle unexpected errors in CSC API
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<CSCErrorResponse> handleGenericException(Exception ex, WebRequest request) {
        log.error("Unexpected error in CSC API", ex);
        return createCSCErrorResponse(
                CSCConstants.ERROR_UNSUPPORTED_OPERATION,
                "An unexpected error occurred: " + ex.getMessage(),
                HttpStatus.INTERNAL_SERVER_ERROR);
    }

    /**
     * Creates a CSC API error response
     */
    private ResponseEntity<CSCErrorResponse> createCSCErrorResponse(
            String error, String message, HttpStatus status) {

        CSCErrorResponse errorResponse = CSCErrorResponse.builder()
                .error(error)
                .errorDescription(message)
                .build();

        return new ResponseEntity<>(errorResponse, status);
    }
}