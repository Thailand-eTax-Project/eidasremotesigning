package com.wpanther.eidasremotesigning.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.eidasremotesigning.dto.ClientRegistrationRequest;
import com.wpanther.eidasremotesigning.dto.csc.*;
import com.wpanther.eidasremotesigning.entity.SigningCertificate;
import com.wpanther.eidasremotesigning.repository.OAuth2ClientRepository;
import com.wpanther.eidasremotesigning.repository.SigningCertificateRepository;
import com.wpanther.eidasremotesigning.service.BCFKSService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class XAdESSignHashIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OAuth2ClientRepository oauth2ClientRepository;

    @Autowired
    private SigningCertificateRepository signingCertificateRepository;

    @Autowired
    private BCFKSService bcfksService;

    // Shared state across ordered test methods
    private static String clientId;
    private static String clientSecret;
    private static String accessToken;
    private static String credentialId;
    private static String sad;
    private static String certificateData;
    private static String signature;
    private static String digest;

    // Keystore constants
    private static final String KEYSTORE_PATH =
            "/home/wpanther/projects/etax/invoice-microservices/docker/eidasremotesigning/keystores/eidas-signing.bfks";
    private static final String KEYSTORE_ALIAS = "signing-key";
    private static final String KEYSTORE_PASSWORD = "eidas-signing-2024";

    // Payload to sign
    private static final String TEST_XML = "<Invoice><ID>TEST-001</ID></Invoice>";

    @Test
    @Order(1)
    public void testClientRegistration() throws Exception {
        ClientRegistrationRequest request = new ClientRegistrationRequest();
        request.setClientName("XAdES SignHash Test Client");
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

        assertNotNull(clientId, "clientId must not be null");
        assertNotNull(clientSecret, "clientSecret must not be null");
        assertEquals("XAdES SignHash Test Client", json.get("clientName").asText());
        assertTrue(oauth2ClientRepository.existsByClientId(clientId));
    }

    @Test
    @Order(2)
    public void testOAuthTokenGeneration() throws Exception {
        assumeTrue(clientId != null, "clientId must be set by testClientRegistration");

        String encodedAuth = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes());

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .post("/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                .header("Authorization", "Basic " + encodedAuth)
                .content("grant_type=client_credentials&scope=signing"))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        accessToken = json.get("access_token").asText();

        assertNotNull(accessToken, "access_token must not be null");
        assertTrue(json.has("expires_in"));
        assertTrue(json.has("token_type"));
    }

    @Test
    @Order(3)
    public void testRegisterBCFKSCertificate() throws Exception {
        assumeTrue(clientId != null, "clientId must be set by testClientRegistration");

        SigningCertificate cert = SigningCertificate.builder()
                .id(UUID.randomUUID().toString())
                .storageType("BCFKS")
                .certificateAlias(KEYSTORE_ALIAS)
                .keystorePath(KEYSTORE_PATH)
                .keystorePassword(KEYSTORE_PASSWORD)
                .active(true)
                .clientId(clientId)
                .createdAt(Instant.now())
                .build();

        signingCertificateRepository.save(cert);
        credentialId = cert.getId();

        assertNotNull(credentialId, "credentialId must not be null after save");
        assertTrue(signingCertificateRepository.existsById(credentialId));

        // Load the X.509 certificate to capture Base64 DER for verification later
        X509Certificate x509Cert = bcfksService.loadCertificate(
                KEYSTORE_PATH, KEYSTORE_ALIAS, KEYSTORE_PASSWORD);
        assertNotNull(x509Cert, "Certificate must be loadable from keystore");
        certificateData = Base64.getEncoder().encodeToString(x509Cert.getEncoded());
        assertFalse(certificateData.isBlank(), "certificateData must not be blank");
    }

    @Test
    @Order(4)
    public void testAuthorizeCredential() throws Exception {
        assumeTrue(clientId != null, "clientId must be set by testClientRegistration");
        assumeTrue(accessToken != null, "accessToken must be set by testOAuthTokenGeneration");
        assumeTrue(credentialId != null, "credentialId must be set by testRegisterBCFKSCertificate");

        CSCAuthorizeRequest authorizeRequest = CSCAuthorizeRequest.builder()
                .credentialID(credentialId)
                .numSignatures(1)
                .build();

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .post("/csc/v2/credentials/authorize")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .content(objectMapper.writeValueAsString(authorizeRequest)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        // The authorize response uses lowercase "sad" key
        sad = json.get("sad").asText();

        assertNotNull(sad, "SAD must not be null");
        assertFalse(sad.isBlank(), "SAD must not be blank");
        assertTrue(json.has("handle"), "Response must include handle");
    }

    @Test
    @Order(5)
    public void testSignHashXAdES() throws Exception {
        assumeTrue(clientId != null, "clientId must be set by testClientRegistration");
        assumeTrue(accessToken != null, "accessToken must be set by testOAuthTokenGeneration");
        assumeTrue(credentialId != null, "credentialId must be set by testRegisterBCFKSCertificate");
        assumeTrue(sad != null, "SAD must be set by testAuthorizeCredential");

        // Compute SHA-256 digest of the test XML
        digest = TestUtils.calculateSHA256Digest(TEST_XML);
        assertNotNull(digest, "digest must not be null");

        // Build the sign request
        CSCBaseRequest.PIN pin = CSCBaseRequest.PIN.builder()
                .value(KEYSTORE_PASSWORD)
                .build();

        CSCBaseRequest.Credentials credentials = CSCBaseRequest.Credentials.builder()
                .pin(pin)
                .build();

        CSCSignatureRequest signRequest = CSCSignatureRequest.builder()
                .credentialID(credentialId)
                .SAD(sad)
                .credentials(credentials)
                .hashAlgorithmOID("2.16.840.1.101.3.4.2.1")
                .hashes(new String[]{digest})
                .operationMode("S")
                .build();

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                .post("/csc/v2/signatures/signHash")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + accessToken)
                .content(objectMapper.writeValueAsString(signRequest)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode signaturesNode = json.get("signatures");

        assertNotNull(signaturesNode, "signatures array must be present");
        assertTrue(signaturesNode.isArray(), "signatures must be an array");
        assertEquals(1, signaturesNode.size(), "exactly one signature expected");
        signature = signaturesNode.get(0).asText();
        assertFalse(signature.isBlank(), "signature value must not be blank");

        // Verify CSC v2.0 wire format: signatureAlgorithm is OID, no certificate field
        String sigAlgoOid = json.get("signatureAlgorithm").asText();
        assertTrue(sigAlgoOid.startsWith("1.2.840") || sigAlgoOid.startsWith("2.16.840"),
                "signatureAlgorithm must be an OID, got: " + sigAlgoOid);
        assertFalse(json.has("certificate"), "certificate must not be present in signHash response");
    }

    @Test
    @Order(6)
    public void testVerifySignature() throws Exception {
        assumeTrue(digest != null, "digest must be set by testSignHashXAdES");
        assumeTrue(signature != null, "signature must be set by testSignHashXAdES");
        assumeTrue(certificateData != null, "certificateData must be set by testRegisterBCFKSCertificate");

        boolean valid = TestUtils.verifyDigestSignature(
                digest, signature, certificateData, "SHA256withRSA");

        assertTrue(valid, "XAdES signature must verify correctly against the signer's public key");
    }
}
