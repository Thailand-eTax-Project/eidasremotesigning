package com.wpanther.eidasremotesigning.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.eidasremotesigning.dto.*;
import com.wpanther.eidasremotesigning.repository.OAuth2ClientRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Base64;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for eIDAS Remote Signing Service
 * These tests cover the complete flow from client registration to signing
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class EidasRemoteSigningIT {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @Autowired
        private OAuth2ClientRepository oauth2ClientRepository;


        // Test variables
        private static String clientId;
        private static String clientSecret;
        private static String accessToken;

        /**
         * Cleanup before running tests
         */
        @BeforeAll
        public static void setup() {
                // This will be executed before all test methods
        }

        /**
         * Test client registration
         */
        @Test
        @Order(1)
        public void testClientRegistration() throws Exception {
                // Create client registration request
                ClientRegistrationRequest request = new ClientRegistrationRequest();
                request.setClientName("Integration Test Client");
                request.setScopes(Set.of("signing"));
                request.setGrantTypes(Set.of("client_credentials"));

                // Call the registration endpoint
                MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                                .post("/client-registration")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(request)))
                                .andExpect(status().isCreated())
                                .andReturn();

                // Parse response
                String responseContent = result.getResponse().getContentAsString();
                JsonNode responseJson = objectMapper.readTree(responseContent);

                // Save client credentials for subsequent tests
                clientId = responseJson.get("clientId").asText();
                clientSecret = responseJson.get("clientSecret").asText();

                // Verify registration data
                assertNotNull(clientId);
                assertNotNull(clientSecret);
                assertEquals("Integration Test Client", responseJson.get("clientName").asText());
                assertTrue(responseJson.get("scopes").isArray());
                assertTrue(responseJson.get("grantTypes").isArray());

                // Verify client exists in database
                assertTrue(oauth2ClientRepository.existsByClientId(clientId));

                System.out.println("Registered client with ID: " + clientId);
        }

        /**
         * Test OAuth2 token generation
         */
        @Test
        @Order(2)
        public void testOAuthTokenGeneration() throws Exception {
                // Prepare Basic authentication header
                String auth = clientId + ":" + clientSecret;
                String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());

                // Request an OAuth access token
                MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                                .post("/oauth2/token")
                                .contentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE)
                                .header("Authorization", "Basic " + encodedAuth)
                                .content("grant_type=client_credentials&scope=signing"))
                                .andExpect(status().isOk())
                                .andReturn();

                // Parse response
                String responseContent = result.getResponse().getContentAsString();
                JsonNode responseJson = objectMapper.readTree(responseContent);

                // Save access token for subsequent tests
                accessToken = responseJson.get("access_token").asText();

                // Verify token data
                assertNotNull(accessToken);
                assertTrue(responseJson.has("expires_in"));
                assertTrue(responseJson.has("token_type"));

                System.out.println("Generated OAuth token successfully");
        }

        /**
         * Test that /csc/v2/info accepts POST and rejects GET (CSC API v2.0 spec)
         */
        @Test
        @Order(0)
        public void testInfoEndpointIsPost() throws Exception {
            mockMvc.perform(MockMvcRequestBuilders
                    .post("/csc/v2/info")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}"))
                    .andExpect(status().isOk());

            // GET should no longer work
            mockMvc.perform(MockMvcRequestBuilders
                    .get("/csc/v2/info"))
                    .andExpect(status().isMethodNotAllowed());
        }

        /**
         * Test CSC API /info endpoint
         */
        @Test
        @Order(3)
        public void testServiceInfo() throws Exception {
                MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                                .post("/csc/v2/info")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isOk())
                                .andReturn();

                JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());

                // Assert specs field
                assertTrue(json.has("specs"), "Response must include specs field");
                assertEquals("2.0.0.0", json.get("specs").asText());

                // Assert authType is a JSON array of strings
                assertTrue(json.has("authType"), "Response must include authType field");
                assertTrue(json.get("authType").isArray(), "authType must be an array");
                assertEquals(1, json.get("authType").size());
                assertEquals("oauth2code", json.get("authType").get(0).asText());

                // Assert methods list includes key endpoints
                assertTrue(json.has("methods"), "Response must include methods field");
                assertTrue(json.get("methods").isArray(), "methods must be an array");

                String methodsText = json.get("methods").toString();
                assertTrue(methodsText.contains("credentials/authorizeCheck"),
                                "methods must include credentials/authorizeCheck");
                assertTrue(methodsText.contains("signatures/signDoc"),
                                "methods must include signatures/signDoc");
                assertTrue(methodsText.contains("signatures/signPolling"),
                                "methods must include signatures/signPolling");
                assertTrue(methodsText.contains("signatures/validate"),
                                "methods must include signatures/validate");

                System.out.println("CSC /info endpoint validated successfully");
        }

        /**
         * Test that /info response contains all required CSC v2.0 fields
         */
        @Test
        @Order(4)
        public void testInfoResponseContainsRequiredFields() throws Exception {
                MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                                .post("/csc/v2/info")
                                .header("Authorization", "Bearer " + accessToken)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                                .andExpect(status().isOk())
                                .andReturn();

                JsonNode info = objectMapper.readTree(result.getResponse().getContentAsString());

                // signAlgorithms
                assertTrue(info.has("signAlgorithms"), "missing signAlgorithms");
                assertTrue(info.get("signAlgorithms").has("algos"), "missing signAlgorithms.algos");
                assertTrue(info.get("signAlgorithms").get("algos").size() > 0, "signAlgorithms.algos is empty");

                // signature_formats
                assertTrue(info.has("signature_formats"), "missing signature_formats");
                assertTrue(info.get("signature_formats").has("formats"), "missing signature_formats.formats");

                // conformance_levels
                assertTrue(info.has("conformance_levels"), "missing conformance_levels");
                assertTrue(info.get("conformance_levels").isArray(), "conformance_levels is not an array");

                // oauth2 — spec §11.1: plain String (OAuth2 server base URL), not an object
                assertTrue(info.has("oauth2"), "missing oauth2");
                assertTrue(info.get("oauth2").isTextual(), "oauth2 must be a String per spec §11.1");
                assertFalse(info.get("oauth2").asText().isBlank(), "oauth2 must not be blank");

                // asynchronousOperationMode
                assertTrue(info.has("asynchronousOperationMode"), "missing asynchronousOperationMode");
                assertTrue(info.get("asynchronousOperationMode").asBoolean(), "asynchronousOperationMode should be true");

                System.out.println("CSC /info required fields validated successfully");
        }

}
