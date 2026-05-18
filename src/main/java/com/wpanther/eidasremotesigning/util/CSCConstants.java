package com.wpanther.eidasremotesigning.util;

/**
 * CSC API v2.0 constants used across the application.
 */
public final class CSCConstants {

    private CSCConstants() {
        // Prevent instantiation
    }

    /** CSC API specification version implemented by this service. */
    public static final String SPECS_VERSION = "2.0";

    /** OAuth2 authorization code grant type identifier (CSC API standard auth method). */
    public static final String AUTH_TYPE_OAUTH2_CODE = "oauth2code";

    /** Credential status: enabled / active. */
    public static final String CREDENTIAL_STATUS_ENABLED = "enabled";

    /** Credential status: suspended / disabled. */
    public static final String CREDENTIAL_STATUS_SUSPENDED = "suspended";

    /** Key status: enabled. */
    public static final String KEY_STATUS_ENABLED = "enabled";
}
