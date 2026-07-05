package com.wpanther.eidasremotesigning.exception;

/**
 * Thrown when a CSC request carries an invalid or unsupported conformance_level
 * (e.g. an unrecognized string, a non-B level on the documentDigests[] path, or a
 * non-B level for an AWS KMS credential). Maps to HTTP 400 with error code
 * "invalid_request". Extends SigningException; the dedicated handler in
 * GlobalExceptionHandler returns the CSC error shape.
 */
public class CSCInvalidRequestException extends SigningException {

    public CSCInvalidRequestException(String message) {
        super(message);
    }
}
