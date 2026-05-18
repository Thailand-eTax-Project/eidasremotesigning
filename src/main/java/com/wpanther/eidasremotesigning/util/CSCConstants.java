package com.wpanther.eidasremotesigning.util;

/**
 * CSC API v2.0 constants used across the application.
 */
public final class CSCConstants {

    private CSCConstants() {
        // Prevent instantiation
    }

    /** CSC API specification version implemented by this service. */
    public static final String SPECS_VERSION = "2.0.0.0";

    /** OAuth2 authorization code grant type identifier (CSC API standard auth method). */
    public static final String AUTH_TYPE_OAUTH2_CODE = "oauth2code";

    /** Credential status: enabled / active. */
    public static final String CREDENTIAL_STATUS_ENABLED = "enabled";

    /** Credential status: suspended / disabled. */
    public static final String CREDENTIAL_STATUS_SUSPENDED = "suspended";

    /** Key status: enabled. */
    public static final String KEY_STATUS_ENABLED = "enabled";

    /** Operation mode: synchronous. */
    public static final String OPERATION_MODE_SYNC = "S";

    /** Operation mode: asynchronous. */
    public static final String OPERATION_MODE_ASYNC = "A";

    // CSC API v2.0 error codes (Section 8)

    public static final String ERROR_INVALID_REQUEST = "invalid_request";
    public static final String ERROR_UNAUTHORIZED_CLIENT = "unauthorized_client";
    public static final String ERROR_ACCESS_DENIED = "access_denied";
    public static final String ERROR_CREDENTIAL_NOT_FOUND = "credential_not_found";
    public static final String ERROR_SIGNING_ERROR = "signing_error";
    public static final String ERROR_UNSUPPORTED_OPERATION = "unsupported_operation";
}
