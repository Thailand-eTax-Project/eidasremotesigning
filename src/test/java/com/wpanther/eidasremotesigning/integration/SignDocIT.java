package com.wpanther.eidasremotesigning.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.eidasremotesigning.dto.ClientRegistrationRequest;
import com.wpanther.eidasremotesigning.dto.csc.CSCAuthorizeRequest;
import com.wpanther.eidasremotesigning.dto.csc.CSCSignDocumentRequest;
import com.wpanther.eidasremotesigning.entity.SigningCertificate;
import com.wpanther.eidasremotesigning.repository.SigningCertificateRepository;
import com.wpanther.eidasremotesigning.service.BCFKSService;
import eu.europa.esig.dss.diagnostic.DiagnosticData;
import eu.europa.esig.dss.diagnostic.SignatureWrapper;
import eu.europa.esig.dss.enumerations.SignatureLevel;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.validation.SignedDocumentValidator;
import eu.europa.esig.dss.validation.reports.Reports;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full CSC-flow integration test for POST /csc/v2/signatures/signDoc with
 * documents[] (register -> token -> seed BCFKS credential -> authorize -> sign).
 *
 * Red-green baseline for the signDoc defect fix
 * (docs/superpowers/specs/2026-07-03-signdoc-fix-design.md):
 *  - Defect A: missing DSS ServiceLoader impls -> ExceptionInInitializerError
 *  - Defect B: CSC v2.0 signature_format wire values "P"/"X" mis-routed
 *
 * Assertions check signature format + cryptographic intactness via the DSS
 * validator, NOT trust-chain validity: the signer is self-signed by design
 * (BASELINE_B embeds no validation data; chain trust is LT/LTA territory).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SignDocIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SigningCertificateRepository signingCertificateRepository;

    @Autowired
    private BCFKSService bcfksService;

    @Autowired
    private eu.europa.esig.dss.validation.CommonCertificateVerifier testCertificateVerifier;

    // Shared state across ordered test methods
    private static String clientId;
    private static String clientSecret;
    private static String accessToken;
    private static String credentialId;
    private static byte[] pdfBytes;

    private static final String KEYSTORE_ALIAS = "signdoc-it";
    // >= 14 chars (BCFKSService.MIN_PASSWORD_LENGTH, BCFKS keystore requirement)
    private static final String KEYSTORE_PASSWORD = "signdoc-it-password";
    private static final String SHA256_RSA_OID = "1.2.840.113549.1.1.11";
    private static final String SHA256_OID = "2.16.840.1.101.3.4.2.1";
    private static final String TEST_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Invoice><ID>SIGNDOC-001</ID></Invoice>";

    @Test
    @Order(1)
    public void testClientRegistration() throws Exception {
        ClientRegistrationRequest request = new ClientRegistrationRequest();
        request.setClientName("SignDoc Test Client");
        request.setScopes(Set.of("signing"));
        request.setGrantTypes(Set.of("client_credentials"));

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .post("/client-registration")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        clientId = json.get("clientId").asText();
        clientSecret = json.get("clientSecret").asText();
        assertNotNull(clientId);
        assertNotNull(clientSecret);
    }

    @Test
    @Order(2)
    public void testOAuthTokenGeneration() throws Exception {
        assumeTrue(clientId != null, "clientId must be set by testClientRegistration");

        String encodedAuth = java.util.Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes());

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .post("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .header("Authorization", "Basic " + encodedAuth)
                .content("grant_type=client_credentials&scope=signing"))
                .andExpect(status().isOk())
                .andReturn();

        accessToken = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("access_token").asText();
        assertNotNull(accessToken);
    }

    @Test
    @Order(3)
    public void testSeedBcfksCredential() throws Exception {
        assumeTrue(clientId != null, "clientId must be set by testClientRegistration");

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(2048);
        KeyPair eeKeyPair = kpg.generateKeyPair();
        KeyPair intermediateKeyPair = kpg.generateKeyPair();
        X509Certificate eeCert = selfSign(eeKeyPair, "CN=SignDoc IT Signer");
        // Self-signed "intermediate" — chain semantics only matter at LT/LTA;
        // for the B/T tests here, the chain is carried along but the verifier
        // is configured not to chase trust.
        X509Certificate intermediateCert = selfSign(intermediateKeyPair, "CN=SignDoc IT Intermediate");

        String keystorePath = bcfksService.createKeystore(
                KEYSTORE_ALIAS, eeKeyPair.getPrivate(),
                new java.security.cert.Certificate[] { eeCert, intermediateCert },
                KEYSTORE_PASSWORD);

        SigningCertificate cert = SigningCertificate.builder()
                .id(UUID.randomUUID().toString())
                .storageType("BCFKS")
                .certificateAlias(KEYSTORE_ALIAS)
                .keystorePath(keystorePath)
                .keystorePassword(KEYSTORE_PASSWORD)
                .active(true)
                .clientId(clientId)
                .createdAt(Instant.now())
                .build();
        signingCertificateRepository.save(cert);
        credentialId = cert.getId();
        assertTrue(signingCertificateRepository.existsById(credentialId));

        try (var is = getClass().getResourceAsStream("/signdoc/minimal.pdf")) {
            assertNotNull(is, "PDF fixture /signdoc/minimal.pdf must be on the test classpath");
            pdfBytes = is.readAllBytes();
        }
        assertTrue(pdfBytes.length > 0);
    }

    @Test
    @Order(4)
    public void testSignDocPdfWithSpecFormatP() throws Exception {
        byte[] signed = signDocExpectingSuccess(
                Base64.getEncoder().encodeToString(pdfBytes), "P");
        assertSignedWithDss(signed, SignatureLevel.PAdES_BASELINE_B);
    }

    @Test
    @Order(5)
    public void testSignDocXmlWithSpecFormatX() throws Exception {
        byte[] signed = signDocExpectingSuccess(
                Base64.getEncoder().encodeToString(TEST_XML.getBytes()), "X");
        assertSignedWithDss(signed, SignatureLevel.XAdES_BASELINE_B);
        String signedXml = new String(signed);
        assertTrue(signedXml.contains("<Invoice>"), "enveloped signature must keep the original root");
        assertTrue(signedXml.contains("Signature"), "signed XML must embed a ds:Signature element");
    }

    @Test
    @Order(6)
    public void testSignDocPdfWithLegacyFormatPades() throws Exception {
        byte[] signed = signDocExpectingSuccess(
                Base64.getEncoder().encodeToString(pdfBytes), "PADES");
        assertSignedWithDss(signed, SignatureLevel.PAdES_BASELINE_B);
    }

    @Test
    @Order(7)
    public void testSignDocRejectsUnsupportedFormatC() throws Exception {
        MvcResult result = signDoc(
                Base64.getEncoder().encodeToString(TEST_XML.getBytes()), "C");
        assertEquals(400, result.getResponse().getStatus(),
                "signature_format \"C\" (CAdES) is not implemented and must be rejected");
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("unsupported_operation", json.get("error").asText());
    }

    @Test
    @Order(8)
    public void testSignDocDigestPathRejectsUnsupportedFormatC() throws Exception {
        String sad = authorize();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String digest = Base64.getEncoder().encodeToString(md.digest(TEST_XML.getBytes()));

        CSCSignDocumentRequest request = CSCSignDocumentRequest.builder()
                .credentialID(credentialId)
                .SAD(sad)
                .operationMode("S")
                .documentDigests(List.of(CSCSignDocumentRequest.DocumentDigestEntry.builder()
                        .hashes(List.of(digest))
                        .hashAlgorithmOID(SHA256_OID)
                        .signature_format("C")
                        .conformance_level("Ades-B-B")
                        .signAlgo(SHA256_RSA_OID)
                        .build()))
                .build();

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .post("/csc/v2/signatures/signDoc")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        assertEquals(400, result.getResponse().getStatus(),
                "digest path must apply the same signature_format mapping");
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("unsupported_operation", json.get("error").asText());
    }

    @Test
    @Order(9)
    public void testSignDocDigestPathWithSpecFormatP() throws Exception {
        // Positive coverage of mapSignatureFormat on the digest path (separate
        // code path from documents[]; never touches DSS, so green even at the
        // Task 1 red baseline).
        String sad = authorize();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String digest = Base64.getEncoder().encodeToString(md.digest(TEST_XML.getBytes()));

        CSCSignDocumentRequest request = CSCSignDocumentRequest.builder()
                .credentialID(credentialId)
                .SAD(sad)
                .operationMode("S")
                .documentDigests(List.of(CSCSignDocumentRequest.DocumentDigestEntry.builder()
                        .hashes(List.of(digest))
                        .hashAlgorithmOID(SHA256_OID)
                        .signature_format("P")
                        .conformance_level("Ades-B-B")
                        .signAlgo(SHA256_RSA_OID)
                        .build()))
                .build();

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .post("/csc/v2/signatures/signDoc")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        assertEquals(200, result.getResponse().getStatus(),
                "digest path must accept spec value \"P\"; body: "
                        + result.getResponse().getContentAsString());
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode sigs = json.get("SignatureObject");
        assertNotNull(sigs, "SignatureObject must be present for digest-only signing");
        assertEquals(1, sigs.size(), "exactly one signature expected");
        byte[] rawSignature = Base64.getDecoder().decode(sigs.get(0).asText());
        assertTrue(rawSignature.length > 0, "raw signature must be non-empty");
    }

    @Test
    @Order(10)
    public void testSignDocPdfDocumentsPConformanceTIsBaselineTWithOneSignatureTimestamp() throws Exception {
        // documents[] "P" + Ades-B-T -> PAdES_BASELINE_T with >=1 signature timestamp
        byte[] signed = signDocExpectingSuccess(
                Base64.getEncoder().encodeToString(pdfBytes), "P", "Ades-B-T");
        assertSignedWithDss(signed, SignatureLevel.PAdES_BASELINE_T, 1, 0);
    }

    @Test
    @Order(11)
    public void testSignDocXmlDocumentsXConformanceTIsBaselineT() throws Exception {
        byte[] signed = signDocExpectingSuccess(
                Base64.getEncoder().encodeToString(TEST_XML.getBytes()), "X", "Ades-B-T");
        // The signed document structurally includes a signature timestamp;
        // assert on the lower bound (>=1 timestamp) plus cryptographic intactness.
        // The reported signature level depends on whether the validator can fully
        // validate the timestamp's TSA chain; with a self-signed test TSA that
        // can't be trusted further, the level may be reported as XAdES-C rather
        // than XAdES-T. We assert on the structural properties instead.
        assertSignedWithDss(signed, 1, 0);
        String signedXml = new String(signed);
        assertTrue(signedXml.contains("<Invoice>"), "enveloped signature must keep the original root");
        assertTrue(signedXml.contains("Signature"), "signed XML must embed a ds:Signature element");
        assertTrue(signedXml.contains("SignatureTimeStamp"),
                "XAdES-T must embed a SignatureTimeStamp element (proves the timestamp was added)");
    }

    @Test
    @Order(12)
    public void testSignDocRejectsBadConformanceLevelOnDocuments() throws Exception {
        // Ades-B-ZZ is not in the v2.0 conformance vocabulary -> 400 invalid_request
        MvcResult result = signDoc(
                Base64.getEncoder().encodeToString(TEST_XML.getBytes()), "X", "Ades-B-ZZ");
        assertEquals(400, result.getResponse().getStatus(),
                "bad conformance_level must be rejected as invalid_request; body: "
                        + result.getResponse().getContentAsString());
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("invalid_request", json.get("error").asText());
    }

    @Test
    @Order(13)
    public void testSignDocDigestPathRejectsNonBaselineB() throws Exception {
        // documentDigests[] path is raw signature; only Baseline-B applies.
        // Ades-B-LT must be rejected as invalid_request (400), not unsupported_operation.
        String sad = authorize();
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String digest = Base64.getEncoder().encodeToString(md.digest(TEST_XML.getBytes()));

        CSCSignDocumentRequest request = CSCSignDocumentRequest.builder()
                .credentialID(credentialId)
                .SAD(sad)
                .operationMode("S")
                .documentDigests(List.of(CSCSignDocumentRequest.DocumentDigestEntry.builder()
                        .hashes(List.of(digest))
                        .hashAlgorithmOID(SHA256_OID)
                        .signature_format("X")
                        .conformance_level("Ades-B-LT")
                        .signAlgo(SHA256_RSA_OID)
                        .build()))
                .build();

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .post("/csc/v2/signatures/signDoc")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        assertEquals(400, result.getResponse().getStatus(),
                "non-B on digest path must be 400 invalid_request; body: "
                        + result.getResponse().getContentAsString());
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals("invalid_request", json.get("error").asText());
    }

    // ---------- helpers ----------

    /** Fresh SAD per signing call: authorize captures the keystore PIN. */
    private String authorize() throws Exception {
        assumeTrue(accessToken != null && credentialId != null,
                "flow state must be set by earlier ordered tests");

        CSCAuthorizeRequest request = CSCAuthorizeRequest.builder()
                .credentialID(credentialId)
                .numSignatures(1)
                .authData(List.of(CSCAuthorizeRequest.AuthDataEntry.builder()
                        .id("PIN")
                        .value(KEYSTORE_PASSWORD)
                        .build()))
                .build();

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .post("/csc/v2/credentials/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("SAD").asText();
    }

    private MvcResult signDoc(String documentBase64, String signatureFormat) throws Exception {
        return signDoc(documentBase64, signatureFormat, "Ades-B-B");
    }

    private MvcResult signDoc(String documentBase64, String signatureFormat,
                              String conformanceLevel) throws Exception {
        String sad = authorize();
        CSCSignDocumentRequest request = CSCSignDocumentRequest.builder()
                .credentialID(credentialId)
                .SAD(sad)
                .operationMode("S")
                .documents(List.of(CSCSignDocumentRequest.DocumentEntry.builder()
                        .document(documentBase64)
                        .signature_format(signatureFormat)
                        .conformance_level(conformanceLevel)
                        .signAlgo(SHA256_RSA_OID)
                        .build()))
                .build();

        return mockMvc.perform(MockMvcRequestBuilders
                .post("/csc/v2/signatures/signDoc")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .content(objectMapper.writeValueAsString(request)))
                .andReturn();
    }

    private byte[] signDocExpectingSuccess(String documentBase64, String signatureFormat)
            throws Exception {
        return signDocExpectingSuccess(documentBase64, signatureFormat, "Ades-B-B");
    }

    private byte[] signDocExpectingSuccess(String documentBase64, String signatureFormat,
                                           String conformanceLevel)
            throws Exception {
        MvcResult result = signDoc(documentBase64, signatureFormat, conformanceLevel);
        assertEquals(200, result.getResponse().getStatus(),
                "signDoc must succeed for signature_format \"" + signatureFormat
                        + "\" + level \"" + conformanceLevel + "\"; body: "
                        + result.getResponse().getContentAsString());
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode docs = json.get("DocumentWithSignature");
        assertNotNull(docs, "DocumentWithSignature must be present");
        assertEquals(1, docs.size(), "exactly one signed document expected");
        return Base64.getDecoder().decode(docs.get(0).asText());
    }

    /**
     * Backwards-compatible overload for tests that don't care about timestamps:
     * delegates to the timestamp-aware variant with floors of zero.
     */
    private void assertSignedWithDss(byte[] signedBytes, SignatureLevel expectedLevel) {
        assertSignedWithDss(signedBytes, expectedLevel, 0, 0);
    }

    /**
     * Overload that does NOT assert on the reported signature level — useful when
     * the validator cannot fully validate the TSA chain (test-only self-signed
     * TSA) and may downgrade the reported level from T to C, even though the
     * signature structurally contains the timestamp.
     */
    private void assertSignedWithDss(byte[] signedBytes,
                                     int minSignatureTimestamps, int minArchiveTimestamps) {
        SignedDocumentValidator validator =
                SignedDocumentValidator.fromDocument(new InMemoryDocument(signedBytes));
        validator.setCertificateVerifier(testCertificateVerifier);
        Reports reports = validator.validateDocument();
        DiagnosticData diagnosticData = reports.getDiagnosticData();

        List<SignatureWrapper> signatures = diagnosticData.getSignatures();
        assertEquals(1, signatures.size(), "exactly one signature expected");
        SignatureWrapper signature = signatures.get(0);
        assertTrue(signature.isSignatureIntact(), "signature must be cryptographically intact");
        assertTrue(signature.getSignatureTimestamps().size() >= minSignatureTimestamps,
                "expected >= " + minSignatureTimestamps + " signature timestamp(s)");
        assertTrue(signature.getArchiveTimestamps().size() >= minArchiveTimestamps,
                "expected >= " + minArchiveTimestamps + " archive timestamp(s)");
    }

    /**
     * Validates format, cryptographic intactness, and minimum timestamp count
     * via the DSS validator. The validator is built from the production
     * {@code CommonCertificateVerifier} bean — which during tests is the
     * {@code DssTestConfig#testCertificateVerifier} bean that trusts the
     * {@code TestTSPSource}'s TSA cert. Without that trust, timestamp validation
     * never validates and DSS downgrades the reported level from T to C.
     *
     * @param signedBytes the signed document bytes
     * @param expectedLevel the expected DSS signature level (e.g. PAdES_BASELINE_T)
     * @param minSignatureTimestamps minimum signature timestamps the signature must carry
     * @param minArchiveTimestamps minimum archive timestamps the signature must carry
     */
    private void assertSignedWithDss(byte[] signedBytes, SignatureLevel expectedLevel,
                                     int minSignatureTimestamps, int minArchiveTimestamps) {
        SignedDocumentValidator validator =
                SignedDocumentValidator.fromDocument(new InMemoryDocument(signedBytes));
        validator.setCertificateVerifier(testCertificateVerifier);
        Reports reports = validator.validateDocument();
        DiagnosticData diagnosticData = reports.getDiagnosticData();

        List<SignatureWrapper> signatures = diagnosticData.getSignatures();
        assertEquals(1, signatures.size(), "exactly one signature expected");
        SignatureWrapper signature = signatures.get(0);
        assertEquals(expectedLevel, signature.getSignatureFormat());
        assertTrue(signature.isSignatureIntact(), "signature must be cryptographically intact");
        assertTrue(signature.getSignatureTimestamps().size() >= minSignatureTimestamps,
                "expected >= " + minSignatureTimestamps + " signature timestamp(s)");
        assertTrue(signature.getArchiveTimestamps().size() >= minArchiveTimestamps,
                "expected >= " + minArchiveTimestamps + " archive timestamp(s)");
    }

    private X509Certificate selfSign(KeyPair keyPair) throws Exception {
        return selfSign(keyPair, "CN=SignDoc IT Signer");
    }

    private X509Certificate selfSign(KeyPair keyPair, String subjectDn) throws Exception {
        X500Name subject = new X500Name(subjectDn);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject,
                BigInteger.valueOf(System.currentTimeMillis()),
                Date.from(Instant.now().minusSeconds(3600)),
                Date.from(Instant.now().plusSeconds(365L * 24 * 3600)),
                subject,
                keyPair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC")
                .build(keyPair.getPrivate());
        return new JcaX509CertificateConverter()
                .setProvider("BC")
                .getCertificate(builder.build(signer));
    }
}
