package com.wpanther.eidasremotesigning.service;

import com.wpanther.eidasremotesigning.dto.csc.CSCVerifyRequest;
import com.wpanther.eidasremotesigning.dto.csc.CSCVerifyResponse;
import com.wpanther.eidasremotesigning.repository.AsyncOperationRepository;
import com.wpanther.eidasremotesigning.repository.SigningCertificateRepository;
import com.wpanther.eidasremotesigning.repository.SigningLogRepository;
import com.wpanther.eidasremotesigning.util.DocumentFormatUtil;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Security;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CSCSignatureService#validateSignature(CSCVerifyRequest)}.
 *
 * <p>Locks in the symmetry between the CSC {@code signHash} signer (fixed in commit
 * b26d64a to sign a pre-computed digest raw, without re-hashing) and the
 * {@code /signatures/validate} verifier. Before the matching verifier fix, the
 * verifier fed the digest to a hash-and-verify algorithm (e.g. SHA256withRSA via
 * {@code Signature.update(digest)}), hashing it a second time and rejecting every
 * legitimate signature produced by {@code signHash}.
 *
 * <p>Each test signs a pre-computed digest the correct raw way (NONEwithRSA over a
 * PKCS#1 v1.5 DigestInfo wrapping), then delegates verification to the production
 * {@code validateSignature} path. A passing assertion proves the verifier no longer
 * double-hashes.
 */
@ExtendWith(MockitoExtension.class)
class CSCSignatureServiceValidateSignatureTest {

    @Mock SigningCertificateRepository certificateRepository;
    @Mock SigningCertificateService certificateService;
    @Mock SigningLogService signingLogService;
    @Mock SigningLogRepository signingLogRepository;
    @Mock CSCAuthorizationService cscAuthorizationService;
    @Mock EIDASComplianceService eidasComplianceService;
    @Mock AsyncOperationRepository asyncOperationRepository;
    @Mock AsyncOperationService asyncOperationService;
    @Mock DocumentFormatUtil documentFormatUtil;
    @Mock Executor asyncExecutor;

    private CSCSignatureService service;
    private KeyPair signingKeyPair;
    private X509Certificate signingCert;

    private static final String SHA256_OID = "2.16.840.1.101.3.4.2.1";
    private static final String SHA384_OID = "2.16.840.1.101.3.4.2.2";
    private static final String SHA512_OID = "2.16.840.1.101.3.4.2.3";
    private static final String SHA256WITHRSA_OID = "1.2.840.113549.1.1.11";
    private static final String SHA384WITHRSA_OID = "1.2.840.113549.1.1.12";
    private static final String SHA512WITHRSA_OID = "1.2.840.113549.1.1.13";

    @BeforeAll
    static void setupProvider() {
        if (Security.getProvider("BCFIPS") == null) {
            Security.insertProviderAt(new BouncyCastleFipsProvider(), 2);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        // CSCSignatureService only uses documentFormatUtil/awskmsService/etc. for the
        // signing paths; validateSignature() needs just the instance. Pass null for
        // the optional AWSKMSService (6th-to-last arg) — matches @Autowired(required=false).
        service = new CSCSignatureService(
                certificateRepository,
                certificateService,
                signingLogService,
                signingLogRepository,
                cscAuthorizationService,
                eidasComplianceService,
                asyncOperationRepository,
                asyncOperationService,
                documentFormatUtil,
                asyncExecutor,
                null,                       // AWSKMSService (optional)
                "http://tsa.belgium.be/connect",
                30);

        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                "client-1", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(ctx);

        signingKeyPair = generateRsaKeyPair();
        signingCert = selfSignedCert(signingKeyPair, Instant.now().plus(365, ChronoUnit.DAYS));
    }

    @Test
    void validateSignature_sha256_rsa_acceptsCorrectlyRawSignedDigest() throws Exception {
        byte[] digest = sha256("the quick brown fox");
        byte[] signature = rawRsaSignDigestInfo(digest, "SHA-256");

        CSCVerifyResponse response = service.validateSignature(CSCVerifyRequest.builder()
                .certificate(b64(signingCert.getEncoded()))
                .documentDigest(b64(digest))
                .signature(b64(signature))
                .hashAlgo(SHA256_OID)
                .signatureAlgorithm(SHA256WITHRSA_OID)
                .build());

        assertThat(response.isValid())
                .as("Verifier must accept a digest signed raw (NONEwithRSA over DigestInfo) — "
                        + "regression check for the symmetric double-hash bug fixed in b26d64a")
                .isTrue();
    }

    @Test
    void validateSignature_sha384_rsa_acceptsCorrectlyRawSignedDigest() throws Exception {
        byte[] digest = sha("SHA-384", "payload-384");
        byte[] signature = rawRsaSignDigestInfo(digest, "SHA-384");

        CSCVerifyResponse response = service.validateSignature(CSCVerifyRequest.builder()
                .certificate(b64(signingCert.getEncoded()))
                .documentDigest(b64(digest))
                .signature(b64(signature))
                .hashAlgo(SHA384_OID)
                .signatureAlgorithm(SHA384WITHRSA_OID)
                .build());

        assertThat(response.isValid()).isTrue();
    }

    @Test
    void validateSignature_sha512_rsa_acceptsCorrectlyRawSignedDigest() throws Exception {
        byte[] digest = sha("SHA-512", "payload-512");
        byte[] signature = rawRsaSignDigestInfo(digest, "SHA-512");

        CSCVerifyResponse response = service.validateSignature(CSCVerifyRequest.builder()
                .certificate(b64(signingCert.getEncoded()))
                .documentDigest(b64(digest))
                .signature(b64(signature))
                .hashAlgo(SHA512_OID)
                .signatureAlgorithm(SHA512WITHRSA_OID)
                .build());

        assertThat(response.isValid()).isTrue();
    }

    @Test
    void validateSignature_rejectsCorruptedSignature() throws Exception {
        byte[] digest = sha256("the quick brown fox");
        byte[] signature = rawRsaSignDigestInfo(digest, "SHA-256");
        signature[signature.length / 2] ^= 0x01; // flip a byte

        CSCVerifyResponse response = service.validateSignature(CSCVerifyRequest.builder()
                .certificate(b64(signingCert.getEncoded()))
                .documentDigest(b64(digest))
                .signature(b64(signature))
                .hashAlgo(SHA256_OID)
                .signatureAlgorithm(SHA256WITHRSA_OID)
                .build());

        assertThat(response.isValid())
                .as("Corrupted signature must be rejected (verifier is not trivially returning true)")
                .isFalse();
    }

    @Test
    void validateSignature_rejectsWhenHashAlgoInconsistentWithSigAlgo() throws Exception {
        byte[] digest = sha256("payload");
        byte[] signature = rawRsaSignDigestInfo(digest, "SHA-256");

        org.junit.jupiter.api.Assertions.assertThrows(
                com.wpanther.eidasremotesigning.exception.SigningException.class,
                () -> service.validateSignature(CSCVerifyRequest.builder()
                        .certificate(b64(signingCert.getEncoded()))
                        .documentDigest(b64(digest))
                        .signature(b64(signature))
                        .hashAlgo(SHA512_OID)                       // mismatched
                        .signatureAlgorithm(SHA256WITHRSA_OID)
                        .build()));
    }

    // ---- helpers ----

    private static byte[] sha256(String input) {
        return sha("SHA-256", input);
    }

    private static byte[] sha(String algo, String input) {
        try {
            return MessageDigest.getInstance(algo).digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private byte[] rawRsaSignDigestInfo(byte[] digest, String jcaHashAlgo) throws Exception {
        byte[] digestInfo = rsaDigestInfo(jcaHashAlgo, digest);
        Signature sig = Signature.getInstance("NONEwithRSA", "BCFIPS");
        sig.initSign(signingKeyPair().getPrivate());
        sig.update(digestInfo);
        return sig.sign();
    }

    /** PKCS#1 v1.5 DigestInfo prefix bytes per RFC 8017 §9.2. */
    private static byte[] rsaDigestInfo(String hashAlgo, byte[] digest) {
        String h = hashAlgo.toUpperCase().replace("-", "");
        String prefixHex = switch (h) {
            case "SHA256" -> "3031300d060960864801650304020105000420";
            case "SHA384" -> "3041300d060960864801650304020205000430";
            case "SHA512" -> "3051300d060960864801650304020305000440";
            default -> throw new IllegalArgumentException("Unsupported hash: " + hashAlgo);
        };
        byte[] prefix = java.util.HexFormat.of().parseHex(prefixHex);
        byte[] out = new byte[prefix.length + digest.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(digest, 0, out, prefix.length, digest.length);
        return out;
    }

    private KeyPair signingKeyPair() {
        return signingKeyPair;
    }

    private KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BCFIPS");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    private X509Certificate selfSignedCert(KeyPair kp, Instant notAfter) throws Exception {
        X500Name name = new X500Name("CN=test-signer");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Instant notBefore = Instant.now().minus(1, ChronoUnit.DAYS);

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, serial, Date.from(notBefore), Date.from(notAfter), name, kp.getPublic());

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BCFIPS")
                .build(kp.getPrivate());

        return new JcaX509CertificateConverter()
                .setProvider("BCFIPS")
                .getCertificate(builder.build(signer));
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
