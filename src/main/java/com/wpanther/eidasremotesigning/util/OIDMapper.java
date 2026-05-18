package com.wpanther.eidasremotesigning.util;

import com.wpanther.eidasremotesigning.exception.SigningException;

import java.util.HashMap;
import java.util.Map;

public final class OIDMapper {

    private OIDMapper() {}

    private static final Map<String, String> HASH_OID_TO_JCA = new HashMap<>();
    private static final Map<String, String> HASH_JCA_TO_OID = new HashMap<>();
    private static final Map<String, String> KEY_OID_TO_JCA = new HashMap<>();
    private static final Map<String, String> KEY_JCA_TO_OID = new HashMap<>();
    private static final Map<String, String> SIG_OID_TO_JCA = new HashMap<>();
    private static final Map<String, String> SIG_JCA_TO_OID = new HashMap<>();
    private static final Map<String, String[]> KEY_TO_SIG_OIDS = new HashMap<>();

    static {
        HASH_OID_TO_JCA.put("2.16.840.1.101.3.4.2.1", "SHA-256");
        HASH_OID_TO_JCA.put("2.16.840.1.101.3.4.2.2", "SHA-384");
        HASH_OID_TO_JCA.put("2.16.840.1.101.3.4.2.3", "SHA-512");
        HASH_OID_TO_JCA.forEach((k, v) -> HASH_JCA_TO_OID.put(v, k));

        KEY_OID_TO_JCA.put("1.2.840.113549.1.1.1", "RSA");
        KEY_OID_TO_JCA.put("1.2.840.10045.2.1", "EC");
        KEY_OID_TO_JCA.forEach((k, v) -> KEY_JCA_TO_OID.put(v, k));

        SIG_OID_TO_JCA.put("1.2.840.113549.1.1.11", "SHA256withRSA");
        SIG_OID_TO_JCA.put("1.2.840.113549.1.1.12", "SHA384withRSA");
        SIG_OID_TO_JCA.put("1.2.840.113549.1.1.13", "SHA512withRSA");
        SIG_OID_TO_JCA.put("1.2.840.10045.4.3.2",   "SHA256withECDSA");
        SIG_OID_TO_JCA.put("1.2.840.10045.4.3.3",   "SHA384withECDSA");
        SIG_OID_TO_JCA.put("1.2.840.10045.4.3.4",   "SHA512withECDSA");
        SIG_OID_TO_JCA.forEach((k, v) -> SIG_JCA_TO_OID.put(v, k));

        KEY_TO_SIG_OIDS.put("RSA", new String[]{
                "1.2.840.113549.1.1.11",
                "1.2.840.113549.1.1.12",
                "1.2.840.113549.1.1.13"
        });
        KEY_TO_SIG_OIDS.put("EC", new String[]{
                "1.2.840.10045.4.3.2",
                "1.2.840.10045.4.3.3",
                "1.2.840.10045.4.3.4"
        });
    }

    public static String toJcaHashAlgo(String oid) {
        String result = HASH_OID_TO_JCA.get(oid);
        if (result == null) throw new SigningException("Unsupported hash algorithm OID: " + oid);
        return result;
    }

    public static String toOidHashAlgo(String jcaName) {
        String result = HASH_JCA_TO_OID.get(jcaName);
        if (result == null) throw new SigningException("Unsupported hash algorithm JCA name: " + jcaName);
        return result;
    }

    public static String toJcaKeyAlgo(String oid) {
        String result = KEY_OID_TO_JCA.get(oid);
        if (result == null) throw new SigningException("Unsupported key algorithm OID: " + oid);
        return result;
    }

    public static String toOidKeyAlgo(String jcaName) {
        String result = KEY_JCA_TO_OID.get(jcaName);
        if (result == null) throw new SigningException("Unsupported key algorithm JCA name: " + jcaName);
        return result;
    }

    public static String toJcaSigAlgo(String oid) {
        String result = SIG_OID_TO_JCA.get(oid);
        if (result == null) throw new SigningException("Unsupported signature algorithm OID: " + oid);
        return result;
    }

    public static String toOidSigAlgo(String jcaName) {
        String result = SIG_JCA_TO_OID.get(jcaName);
        if (result == null) throw new SigningException("Unsupported signature algorithm JCA name: " + jcaName);
        return result;
    }

    public static String[] supportedSigOidsForKeyAlgo(String jcaKeyAlgo) {
        String[] result = KEY_TO_SIG_OIDS.get(jcaKeyAlgo);
        if (result == null) throw new SigningException("No supported signature algorithms for key type: " + jcaKeyAlgo);
        return result;
    }
}
