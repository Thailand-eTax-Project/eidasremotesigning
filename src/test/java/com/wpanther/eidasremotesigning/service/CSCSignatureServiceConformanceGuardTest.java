package com.wpanther.eidasremotesigning.service;

import com.wpanther.eidasremotesigning.dto.CertificateResponse;
import com.wpanther.eidasremotesigning.dto.csc.CSCSignDocumentRequest;
import com.wpanther.eidasremotesigning.entity.SigningCertificate;
import com.wpanther.eidasremotesigning.entity.TransactionAuthorization;
import com.wpanther.eidasremotesigning.exception.CSCInvalidRequestException;
import com.wpanther.eidasremotesigning.repository.AsyncOperationRepository;
import com.wpanther.eidasremotesigning.repository.SigningCertificateRepository;
import com.wpanther.eidasremotesigning.repository.SigningLogRepository;
import com.wpanther.eidasremotesigning.util.DocumentFormatUtil;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
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
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests that pin down the AWS KMS conformance-level guard introduced for
 * XAdES/PAdES Baseline-T/LT/LTA in the LT/LTA enablement plan.
 *
 * <p>Without the guard, an AWSKMS-backed credential with a non-B conformance
 * level would slip through to {@link CSCSignatureService#signDocumentWithPAdES}
 * or {@link CSCSignatureService#signDocumentWithXAdES}, which would attempt to
 * load the chain from a remote KMS service that doesn't hold the cert as a
 * chainable X.509 object — silently corrupting the resulting signature.
 *
 * <p>The {@code awskmsCredentialWithNonBaselineB_throwsInvalidRequest} test
 * forces the request through to the guard by mocking only the database and
 * authorization collaborators (mirroring the
 * {@link CSCSignatureServiceValidateSignatureTest} construction pattern) and
 * asserts that {@link CSCInvalidRequestException} fires with "AWS KMS" in the
 * message before any signing work occurs.
 */
@ExtendWith(MockitoExtension.class)
class CSCSignatureServiceConformanceGuardTest {

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
    @Mock AWSKMSService awskmsService;

    private CSCSignatureService service;
    private KeyPair signingKeyPair;
    private X509Certificate signingCert;

    private static final String SHA256_RSA_OID = "1.2.840.113549.1.1.11";
    private static final String CRED_ID = "cred-awskms-1";
    private static final String CLIENT_ID = "client-guard";
    private static final String SAD = "sad-guard-test";

    @BeforeAll
    static void setupProvider() {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        // Construction mirrors CSCSignatureServiceValidateSignatureTest:94 — pass mocks
        // for the two new constructor params and the optional AWSKMSService. The TSP lambda
        // never fires (this test stops at the conformance guard) and the verifier mock is
        // sufficient (PAdES/XAdES service constructors accept it).
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
                awskmsService,
                new eu.europa.esig.dss.validation.CommonCertificateVerifier(),
                (eu.europa.esig.dss.spi.x509.tsp.TSPSource) (digestAlgorithm, digest) -> {
                    throw new UnsupportedOperationException("TSP not configured for unit test");
                },
                30);

        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                CLIENT_ID, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(ctx);

        signingKeyPair = generateRsaKeyPair();
        signingCert = selfSignedCert(signingKeyPair);
    }

    @Test
    void awskmsCredentialWithNonBaselineB_throwsInvalidRequest() throws Exception {
        // Arrange: valid SAD -> cert lookup -> AWSKMS branch -> loop guard.
        TransactionAuthorization transaction = TransactionAuthorization.builder()
                .id("tx-1")
                .clientId(CLIENT_ID)
                .certificateId(CRED_ID)
                .sad(SAD)
                .numSignatures(1)
                .remainingSignatures(1)
                .status("AUTHORIZED")
                .createdAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(900))
                .storedPin(null)
                .build();
        when(cscAuthorizationService.validateTransactionForSigningBySad(CLIENT_ID, SAD))
                .thenReturn(transaction);

        SigningCertificate awsCertEntity = SigningCertificate.builder()
                .id(CRED_ID)
                .storageType("AWSKMS")
                .certificateAlias("kms-alias")
                .kmsKeyId("kms-key-1")
                .awsRegion("us-east-1")
                .certificateData(Base64.getEncoder().encodeToString(signingCert.getEncoded()))
                .active(true)
                .clientId(CLIENT_ID)
                .createdAt(Instant.now())
                .build();
        when(certificateRepository.findByIdAndClientId(CRED_ID, CLIENT_ID))
                .thenReturn(Optional.of(awsCertEntity));

        // AWSKMS branch: pin is null; certificate is loaded from stored certificateData.
        CertificateResponse certResponse = CertificateResponse.builder()
                .x509Certificate(signingCert)
                .build();
        when(certificateService.getCertificateWithX509(CRED_ID, null))
                .thenReturn(certResponse);
        when(awskmsService.getKeyAlgorithmType("kms-key-1")).thenReturn("RSA");

        // Build a signDoc request with documents[] "X" and non-B (LT) level.
        // The XML payload is tiny but well-formed; the guard must fire BEFORE the
        // EIDAS validation / chain-load / DSS signing path.
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Invoice><ID>GUARD</ID></Invoice>";
        CSCSignDocumentRequest request = CSCSignDocumentRequest.builder()
                .credentialID(CRED_ID)
                .SAD(SAD)
                .operationMode("S")
                .documents(List.of(CSCSignDocumentRequest.DocumentEntry.builder()
                        .document(Base64.getEncoder().encodeToString(xml.getBytes()))
                        .signature_format("X")
                        .conformance_level("Ades-B-LT")
                        .signAlgo(SHA256_RSA_OID)
                        .build()))
                .build();

        // Act + Assert: AWSKMS + non-B -> 400 invalid_request with "AWS KMS" in the message.
        // Without the guard, the call would slip through to the DSS signing path and throw
        // some downstream Mockito/UnsupportedOperationException (not what we want).
        assertThatThrownBy(() -> service.signDocument(request))
                .isInstanceOf(CSCInvalidRequestException.class)
                .hasMessageContaining("AWS KMS");

        // Verify signing/timestamp never ran:
        org.mockito.Mockito.verifyNoInteractions(signingLogService);
        org.mockito.Mockito.verifyNoInteractions(documentFormatUtil);
        org.mockito.Mockito.verifyNoInteractions(eidasComplianceService);
    }

    // ---- helpers ----

    private KeyPair generateRsaKeyPair() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }

    private X509Certificate selfSignedCert(KeyPair kp) throws Exception {
        X500Name name = new X500Name("CN=guard-test");
        Instant notBefore = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant notAfter = Instant.now().plus(365, ChronoUnit.DAYS);

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name,
                BigInteger.valueOf(System.currentTimeMillis()),
                Date.from(notBefore),
                Date.from(notAfter),
                name,
                kp.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC")
                .build(kp.getPrivate());
        return new JcaX509CertificateConverter()
                .setProvider("BC")
                .getCertificate(builder.build(signer));
    }
}
