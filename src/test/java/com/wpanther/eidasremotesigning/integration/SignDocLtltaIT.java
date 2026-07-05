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
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LT/LTA proof for {@code POST /csc/v2/signatures/signDoc}.
 *
 * <p>Requires the dev-PKI docker-compose stack to be running (OCSP responder on
 * port 8880, AIA HTTP at port 8880 too, root OCSP at 8881, issuing OCSP at 8882).
 * When the stack is down, every test here is skipped via {@code assumeTrue} so the
 * default {@code mvn verify} gate stays green.
 *
 * <p>To run gated:
 * <pre>
 *   docker compose -f dev-pki/docker-compose.yml up -d
 *   mvn verify -Dit.test=SignDocLtltaIT
 * </pre>
 *
 * <p>What this proves (the spec gap Task 3 deferred): with a real CA chain in the
 * keystore and live OCSP/AIA responders, DSS reports the requested signature level
 * cleanly for XAdES, not just PAdES. Each test seeds a fresh BCFKS credential with
 * the dev-PKI {@code chain.pem} (signer + issuing + root), registers a fresh client,
 * authorizes with PIN, signs a document at the requested conformance level, and
 * asserts on the DSS diagnostic data:
 * <ul>
 *   <li>{@code getSignatureFormat() == expectedLevel}</li>
 *   <li>{@code isSignatureIntact()}</li>
 *   <li>minimum signature timestamp count (LT/LTA carry at least one)</li>
 *   <li>minimum archive timestamp count (LTA carries at least one)</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SignDocLtltaIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SigningCertificateRepository signingCertificateRepository;

    @Autowired
    private BCFKSService bcfksService;

    @Autowired
    private CommonCertificateVerifier testCertificateVerifier;

    private static final String KEYSTORE_ALIAS = "signdoc-ltlta-it";
    // >= 14 chars (BCFKSService.MIN_PASSWORD_LENGTH)
    private static final String KEYSTORE_PASSWORD = "signdoc-ltlta-it-pw";
    private static final String SHA256_RSA_OID = "1.2.840.113549.1.1.11";
    private static final String TEST_XML =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Invoice><ID>LTLTA-001</ID></Invoice>";

    /**
     * Gate on the dev-PKI OCSP port. The brief checks 8880; if 8881/8882 are also
     * needed (root/issuing OCSP), the sign operation will surface a fetch failure
     * via {@code signing_error} and the test will fail loudly with that detail —
     * which is the right behavior, not a silent skip.
     */
    private static void assumeDevPkiUp() {
        boolean up = false;
        try {
            up = isReachable("localhost", 8880);
        } catch (Exception ignored) {
            // best-effort gate
        }
        Assumptions.assumeTrue(up,
                "dev-PKI OCSP (port 8880) not reachable — skipping LT/LTA");
    }

    private static boolean isReachable(String host, int port) throws Exception {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress(host, port), 500);
            return true;
        }
    }

    @Test
    void pdf_pades_lt() throws Exception {
        assumeDevPkiUp();
        byte[] pdfBytes = loadPdf();
        runAndAssert(Base64.getEncoder().encodeToString(pdfBytes), "P",
                "Ades-B-LT", SignatureLevel.PAdES_BASELINE_LT, 1, 0);
    }

    @Test
    void pdf_pades_lta() throws Exception {
        assumeDevPkiUp();
        byte[] pdfBytes = loadPdf();
        runAndAssert(Base64.getEncoder().encodeToString(pdfBytes), "P",
                "Ades-B-LTA", SignatureLevel.PAdES_BASELINE_LTA, 1, 1);
    }

    @Test
    void xml_xades_lt() throws Exception {
        assumeDevPkiUp();
        runAndAssert(Base64.getEncoder().encodeToString(TEST_XML.getBytes()), "X",
                "Ades-B-LT", SignatureLevel.XAdES_BASELINE_LT, 1, 0);
    }

    @Test
    void xml_xades_lta() throws Exception {
        assumeDevPkiUp();
        runAndAssert(Base64.getEncoder().encodeToString(TEST_XML.getBytes()), "X",
                "Ades-B-LTA", SignatureLevel.XAdES_BASELINE_LTA, 1, 1);
    }

    /**
     * Self-contained LT/LTA proof: registers a client, mints an access token,
     * seeds a BCFKS credential with the dev-PKI {@code chain.pem} + signer key,
     * authorizes with PIN in {@code authData[]}, signs the document at the
     * requested level via {@code /csc/v2/signatures/signDoc}, then validates the
     * returned bytes through the DSS validator (test verifier already trusts the
     * dev-PKI root via the production {@code CommonCertificateVerifier} bean,
     * which loads {@code app.dss.truststore.path} at startup).
     *
     * <p>All flow state is method-local, so each invocation runs the full flow
     * end-to-end with fresh state and the four cases stay independent — no
     * shared credential leaks across methods.
     */
    private void runAndAssert(String documentBase64,
                              String signatureFormat, String conformanceLevel,
                              SignatureLevel expectedLevel,
                              int minSignatureTimestamps, int minArchiveTimestamps)
            throws Exception {

        // 1) register a fresh client
        ClientRegistrationRequest reg = new ClientRegistrationRequest();
        reg.setClientName("LT/LTA IT Client (" + conformanceLevel + ")");
        reg.setScopes(Set.of("signing"));
        reg.setGrantTypes(Set.of("client_credentials"));
        MvcResult regResult = mockMvc.perform(MockMvcRequestBuilders
                .post("/client-registration")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(reg)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode regJson = objectMapper.readTree(regResult.getResponse().getContentAsString());
        String clientId = regJson.get("clientId").asText();
        String clientSecret = regJson.get("clientSecret").asText();

        // 2) mint an access token
        String encodedAuth = java.util.Base64.getEncoder().encodeToString(
                (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
        MvcResult tokenResult = mockMvc.perform(MockMvcRequestBuilders
                .post("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .header("Authorization", "Basic " + encodedAuth)
                .content("grant_type=client_credentials&scope=signing"))
                .andExpect(status().isOk())
                .andReturn();
        String accessToken = objectMapper.readTree(tokenResult.getResponse().getContentAsString())
                .get("access_token").asText();

        // 3) seed a BCFKS credential with the dev-PKI chain.pem
        String credentialId = seedDevPkiCredential(clientId);

        // 4) authorize with PIN in authData[]
        CSCAuthorizeRequest authorize = CSCAuthorizeRequest.builder()
                .credentialID(credentialId)
                .numSignatures(1)
                .authData(List.of(CSCAuthorizeRequest.AuthDataEntry.builder()
                        .id("PIN")
                        .value(KEYSTORE_PASSWORD)
                        .build()))
                .build();
        MvcResult authResult = mockMvc.perform(MockMvcRequestBuilders
                .post("/csc/v2/credentials/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .content(objectMapper.writeValueAsString(authorize)))
                .andExpect(status().isOk())
                .andReturn();
        String sad = objectMapper.readTree(authResult.getResponse().getContentAsString())
                .get("SAD").asText();

        // 5) signDoc with documents[] + signature_format + conformance_level
        CSCSignDocumentRequest.DocumentEntry entry = CSCSignDocumentRequest.DocumentEntry.builder()
                .document(documentBase64)
                .signature_format(signatureFormat)
                .conformance_level(conformanceLevel)
                .signAlgo(SHA256_RSA_OID)
                .build();
        CSCSignDocumentRequest req = CSCSignDocumentRequest.builder()
                .credentialID(credentialId)
                .SAD(sad)
                .operationMode("S")
                .documents(List.of(entry))
                .build();

        MvcResult signResult = mockMvc.perform(MockMvcRequestBuilders
                .post("/csc/v2/signatures/signDoc")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .content(objectMapper.writeValueAsString(req)))
                .andReturn();

        assertEquals(200, signResult.getResponse().getStatus(),
                "signDoc must succeed at " + conformanceLevel + "; body: "
                        + signResult.getResponse().getContentAsString());
        JsonNode body = objectMapper.readTree(signResult.getResponse().getContentAsString());
        JsonNode docs = body.get("DocumentWithSignature");
        assertNotNull(docs, "DocumentWithSignature must be present");
        assertEquals(1, docs.size(), "exactly one signed document expected");
        byte[] signedBytes = Base64.getDecoder().decode(docs.get(0).asText());

        // 6) validate via DSS: level + intact + timestamp floors
        SignedDocumentValidator validator =
                SignedDocumentValidator.fromDocument(new InMemoryDocument(signedBytes));
        validator.setCertificateVerifier(testCertificateVerifier);
        Reports reports = validator.validateDocument();
        DiagnosticData diag = reports.getDiagnosticData();

        List<SignatureWrapper> sigs = diag.getSignatures();
        assertEquals(1, sigs.size(), "exactly one signature expected");
        SignatureWrapper sig = sigs.get(0);

        String label = signatureFormat + " + " + conformanceLevel;
        assertEquals(expectedLevel, sig.getSignatureFormat(),
                label + " — DSS must report the requested baseline level "
                        + "(the spec gap Task 3 deferred; this is the closure)");
        assertTrue(sig.isSignatureIntact(),
                label + " — signature must be cryptographically intact");
        // Explicit revocation-material check (spec: "revocation/validation material
        // present in the diagnostic") — don't rely only on DSS's level classification
        // implying it.
        assertFalse(diag.getAllRevocationData().isEmpty(),
                label + " — LT/LTA must embed revocation data (CRL/OCSP) for the chain");
        assertTrue(sig.getSignatureTimestamps().size() >= minSignatureTimestamps,
                label + " — expected >= " + minSignatureTimestamps
                        + " signature timestamp(s), got " + sig.getSignatureTimestamps().size());
        // DSS classifies PAdES archive timestamps (the /Type /DocTimeStamp entries
        // produced by PAdES_BASELINE_LTA extension) as DOCUMENT_TIMESTAMP, not
        // ARCHIVE_TIMESTAMP — that classification is reserved for XAdES archive
        // timestamps (xades:ArchiveTimeStamp). So the union of both kinds is the
        // right floor for both formats: XAdES puts them in ArchiveTimestamps,
        // PAdES in DocumentTimestamps.
        int ltaLikeTimestamps = sig.getArchiveTimestamps().size()
                + sig.getDocumentTimestamps().size();
        assertTrue(ltaLikeTimestamps >= minArchiveTimestamps,
                label + " — expected >= " + minArchiveTimestamps
                        + " LTA-style timestamp(s) (archive + document), got " + ltaLikeTimestamps
                        + " (archive=" + sig.getArchiveTimestamps().size()
                        + ", document=" + sig.getDocumentTimestamps().size() + ")");

        System.out.printf("[SignDocLtltaIT] %s: format=%s, sigTimestamps=%d, archiveTimestamps=%d, documentTimestamps=%d%n",
                label, sig.getSignatureFormat(),
                sig.getSignatureTimestamps().size(),
                sig.getArchiveTimestamps().size(),
                sig.getDocumentTimestamps().size());
    }

    /**
     * Loads the dev-PKI {@code chain.pem} (signer + issuing + root, PEM bundle)
     * and the corresponding {@code signer.key}, packages them into a BCFKS
     * keystore via {@link BCFKSService#createKeystore(String, PrivateKey, Certificate[], String)},
     * and registers the credential against {@code clientId}. Returns the
     * persisted credential's ID.
     */
    private String seedDevPkiCredential(String clientId) throws Exception {
        Path chainPem = Path.of("dev-pki", "out", "signer", "chain.pem");
        Path signerKey = Path.of("dev-pki", "out", "signer", "signer.key");
        if (!Files.isRegularFile(chainPem) || !Files.isRegularFile(signerKey)) {
            throw new IllegalStateException(
                    "dev-PKI artifacts missing — run dev-pki/generate.sh first; expected "
                            + chainPem.toAbsolutePath() + " and " + signerKey.toAbsolutePath());
        }

        X509Certificate[] chain = readChainFromPem(chainPem);
        PrivateKey priv = readPrivateKeyFromPem(signerKey);
        Certificate[] chainAsCert = chain;

        String keystorePath = bcfksService.createKeystore(
                KEYSTORE_ALIAS, priv, chainAsCert, KEYSTORE_PASSWORD);

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
        assertTrue(signingCertificateRepository.existsById(cert.getId()));
        return cert.getId();
    }

    /**
     * Parses a multi-cert PEM bundle via {@link CertificateFactory}. Order is
     * preserved as written (signer, then issuing, then root for chain.pem).
     */
    private static X509Certificate[] readChainFromPem(Path pem) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        try (InputStream in = Files.newInputStream(pem)) {
            return ((java.util.Collection<? extends Certificate>) cf.generateCertificates(in))
                    .stream()
                    .map(c -> (X509Certificate) c)
                    .toArray(X509Certificate[]::new);
        }
    }

    /**
     * Parses a PEM-encoded RSA private key (the dev-PKI signer's {@code signer.key}).
     * Handles both PEM types: PKCS#8 {@code PrivateKeyInfo} (what OpenSSL writes
     * for {@code openssl genpkey}) and the legacy {@code PEMKeyPair} pair (older
     * RSA PEM files).
     */
    private static PrivateKey readPrivateKeyFromPem(Path pem) throws Exception {
        try (PEMParser parser = new PEMParser(
                new InputStreamReader(Files.newInputStream(pem), StandardCharsets.UTF_8))) {
            Object obj = parser.readObject();
            JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
            if (obj instanceof PEMKeyPair) {
                return converter.getKeyPair((PEMKeyPair) obj).getPrivate();
            } else if (obj instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo) {
                return converter.getPrivateKey((org.bouncycastle.asn1.pkcs.PrivateKeyInfo) obj);
            } else {
                throw new IllegalStateException(
                        "Expected PEMKeyPair or PrivateKeyInfo in " + pem
                                + ", got " + (obj == null ? "null" : obj.getClass().getName()));
            }
        }
    }

    private static byte[] loadPdf() throws Exception {
        try (InputStream is = SignDocLtltaIT.class.getResourceAsStream("/signdoc/minimal.pdf")) {
            assertNotNull(is, "PDF fixture /signdoc/minimal.pdf must be on the test classpath");
            return is.readAllBytes();
        }
    }
}
