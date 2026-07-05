package com.wpanther.eidasremotesigning.exception;

/**
 * Thrown when a CSC request asks for an unsupported signature_format (e.g. "C"
 * for CAdES, "J" for JAdES, or an unknown/null value). Extends SigningException
 * so it is caught by the existing SigningException resolution path, but the
 * dedicated handler in GlobalExceptionHandler returns the CSC error shape with
 * error code "unsupported_operation" and HTTP 400.
 */
public class CSCUnsupportedOperationException extends SigningException {

    public CSCUnsupportedOperationException(String message) {
        super(message);
    }
}
