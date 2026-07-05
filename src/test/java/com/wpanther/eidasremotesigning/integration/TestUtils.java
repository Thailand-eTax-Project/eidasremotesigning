package com.wpanther.eidasremotesigning.integration;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Utility methods for integration testing.
 *
 * <p>The {@code generateSelfSignedCert} overloads live here so any test (BCFKS
 * service tests, custom {@code TestTSPSource}, etc.) can build a quick X.509
 * certificate under BC without duplicating the helper.
 */
public class TestUtils {

    /**
     * Calculates the SHA-256 digest of the input content
     * 
     * @param content The content to digest
     * @return Base64-encoded digest value
     */
    public static String calculateSHA256Digest(String content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] digestBytes = digest.digest(content.getBytes());
        return Base64.getEncoder().encodeToString(digestBytes);
    }

    /**
     * Generates a self-signed X.509 certificate (RSA / SHA256withRSA / BC provider)
     * with the given subject. Validity is 365 days starting now.
     *
     * @param keyPair the RSA key pair (must match the algorithm here)
     * @param subject the X.500 subject name (also used as issuer)
     * @return a self-signed X.509 certificate
     */
    public static X509Certificate generateSelfSignedCert(KeyPair keyPair, X500Name subject) throws Exception {
        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000);

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.ONE, notBefore, notAfter, subject, keyPair.getPublic());

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC")
                .build(keyPair.getPrivate());

        return new JcaX509CertificateConverter()
                .setProvider("BC")
                .getCertificate(builder.build(signer));
    }
    
    /**
     * Extracts the modulus length (key size) from an RSA certificate
     * 
     * @param certificateBase64 Base64-encoded X.509 certificate
     * @return Key size in bits
     */
    public static int getKeySize(String certificateBase64) throws Exception {
        byte[] certificateBytes = Base64.getDecoder().decode(certificateBase64);
        
        java.security.cert.CertificateFactory certFactory = 
                java.security.cert.CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) certFactory.generateCertificate(
                new java.io.ByteArrayInputStream(certificateBytes));
        
        if ("RSA".equals(cert.getPublicKey().getAlgorithm())) {
            java.security.interfaces.RSAPublicKey rsaKey = 
                    (java.security.interfaces.RSAPublicKey) cert.getPublicKey();
            return rsaKey.getModulus().bitLength();
        } else if ("EC".equals(cert.getPublicKey().getAlgorithm())) {
            java.security.interfaces.ECPublicKey ecKey = 
                    (java.security.interfaces.ECPublicKey) cert.getPublicKey();
            return ecKey.getParams().getCurve().getField().getFieldSize();
        }
        
        return 0;
    }
    
    /**
     * Verifies RSA signature
     * 
     * @param data The original data that was signed
     * @param signatureBase64 Base64-encoded signature
     * @param certificateBase64 Base64-encoded certificate
     * @param digestAlgorithm Digest algorithm used
     * @return true if signature is valid
     */
    public static boolean verifySignature(byte[] data, String signatureBase64, 
                                         String certificateBase64, String digestAlgorithm) throws Exception {
        // Decode the signature
        byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);
        
        // Get the certificate
        byte[] certificateBytes = Base64.getDecoder().decode(certificateBase64);
        java.security.cert.CertificateFactory certFactory = 
                java.security.cert.CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) certFactory.generateCertificate(
                new java.io.ByteArrayInputStream(certificateBytes));
        
        // Create the signature algorithm name based on digest algorithm
        String signatureAlgorithm;
        if ("RSA".equals(cert.getPublicKey().getAlgorithm())) {
            signatureAlgorithm = digestAlgorithm.replace("-", "") + "withRSA";
        } else if ("EC".equals(cert.getPublicKey().getAlgorithm())) {
            signatureAlgorithm = digestAlgorithm.replace("-", "") + "withECDSA";
        } else {
            throw new IllegalArgumentException("Unsupported key algorithm: " + cert.getPublicKey().getAlgorithm());
        }
        
        // Create a signature verifier
        java.security.Signature sig = java.security.Signature.getInstance(signatureAlgorithm);
        sig.initVerify(cert.getPublicKey());
        sig.update(data);
        
        // Verify the signature
        return sig.verify(signatureBytes);
    }
    
    /**
     * Verifies a digest signature
     * 
     * @param digestBase64 Base64-encoded digest that was signed
     * @param signatureBase64 Base64-encoded signature
     * @param certificateBase64 Base64-encoded certificate
     * @param signatureAlgorithm Signature algorithm used
     * @return true if signature is valid
     */
    public static boolean verifyDigestSignature(String digestBase64, String signatureBase64, 
                                              String certificateBase64, String signatureAlgorithm) throws Exception {
        // Decode the signature and digest
        byte[] signatureBytes = Base64.getDecoder().decode(signatureBase64);
        byte[] digestBytes = Base64.getDecoder().decode(digestBase64);
        
        // Get the certificate
        byte[] certificateBytes = Base64.getDecoder().decode(certificateBase64);
        java.security.cert.CertificateFactory certFactory = 
                java.security.cert.CertificateFactory.getInstance("X.509");
        X509Certificate cert = (X509Certificate) certFactory.generateCertificate(
                new java.io.ByteArrayInputStream(certificateBytes));
        
        // The signature is over a PRE-COMPUTED digest. Verifying with a full
        // "SHA256withRSA" verifier and update(digest) would hash the digest AGAIN,
        // i.e. check the signature against SHA256(digest) — which masks a signer that
        // double-hashes. Verify correctly: wrap the digest in a PKCS#1 DigestInfo and
        // use NONEwithRSA (raw RSA, no re-hash), the way a real CMS/XAdES verifier does.
        byte[] digestInfo = sha256DigestInfo(digestBytes);
        java.security.Signature sig = java.security.Signature.getInstance("NONEwithRSA");
        sig.initVerify(cert.getPublicKey());
        sig.update(digestInfo);

        // Verify the signature
        return sig.verify(signatureBytes);
    }

    /** Wrap a SHA-256 digest in its PKCS#1 v1.5 DigestInfo DER structure. */
    private static byte[] sha256DigestInfo(byte[] digest) {
        byte[] prefix = java.util.HexFormat.of()
                .parseHex("3031300d060960864801650304020105000420");
        byte[] out = new byte[prefix.length + digest.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(digest, 0, out, prefix.length, digest.length);
        return out;
    }
}
