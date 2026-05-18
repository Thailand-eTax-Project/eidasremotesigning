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

    /** Credential status: suspended. */
    public static final String CREDENTIAL_STATUS_SUSPENDED = "suspended";

    /** Credential status: disabled. */
    public static final String CREDENTIAL_STATUS_DISABLED = "disabled";

    /** Certificate status: valid. */
    public static final String CERT_STATUS_VALID = "valid";

    /** Certificate status: expired or not yet valid. */
    public static final String CERT_STATUS_EXPIRED = "expired";

    /** Certificate status: revoked. */
    public static final String CERT_STATUS_REVOKED = "revoked";

    /** Key status: enabled. */
    public static final String KEY_STATUS_ENABLED = "enabled";

    /** Key status: disabled. */
    public static final String KEY_STATUS_DISABLED = "disabled";

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
