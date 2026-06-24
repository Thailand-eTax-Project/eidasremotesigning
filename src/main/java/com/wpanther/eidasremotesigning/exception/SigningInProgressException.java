package com.wpanther.eidasremotesigning.exception;

public class SigningInProgressException extends RuntimeException {

    private final String requestID;

    public SigningInProgressException(String requestID) {
        super("Signing operation in progress: " + requestID);
        this.requestID = requestID;
    }

    public String getRequestID() {
        return requestID;
    }
}
