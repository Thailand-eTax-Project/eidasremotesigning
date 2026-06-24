package com.wpanther.eidasremotesigning.service;

import com.wpanther.eidasremotesigning.controller.CSCOAuth2Controller.CSCOAuth2Exception;
import com.wpanther.eidasremotesigning.dto.csc.CSCOAuth2TokenResponse;
import com.wpanther.eidasremotesigning.entity.OAuth2Client;
import com.wpanther.eidasremotesigning.repository.OAuth2ClientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CSCOAuth2ServiceTest {

    @Mock
    private OAuth2ClientRepository clientRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private CSCOAuth2Service service;

    @BeforeEach
    void injectSecureRandom() throws Exception {
        var field = CSCOAuth2Service.class.getDeclaredField("secureRandom");
        field.setAccessible(true);
        field.set(service, new SecureRandom());
    }

    @Test
    void revokeToken_existingAccessToken_removesIt() {
        OAuth2Client client = new OAuth2Client();
        client.setClientId("client-1");
        client.setClientSecret("hashed");
        client.setScopes(Set.of("service"));

        when(clientRepository.findByClientId("client-1")).thenReturn(Optional.of(client));
        when(passwordEncoder.matches("secret", "hashed")).thenReturn(true);

        var tokenResponse = service.clientCredentialsGrant("client-1", "secret");
        String accessToken = tokenResponse.getAccess_token();

        // Revoke it
        service.revokeToken(accessToken, "access_token");

        // Validate it's gone
        assertThatThrownBy(() -> service.validateAccessToken(accessToken))
                .isInstanceOf(CSCOAuth2Exception.class);
    }

    @Test
    void revokeToken_unknownToken_silentlySucceeds() {
        service.revokeToken("nonexistent-token", null);
    }

    @Test
    void exchangeAuthorizationCode_withCredentialID_returnsItInResponse() {
        OAuth2Client client = new OAuth2Client();
        client.setClientId("client-1");
        client.setClientSecret("hashed");
        client.setScopes(Set.of("credential"));

        when(clientRepository.findByClientId("client-1")).thenReturn(Optional.of(client));
        when(passwordEncoder.matches("secret", "hashed")).thenReturn(true);

        // Manually store an authorization request with a credentialID
        service.storeAuthorizationRequest("auth-code-1", "client-1",
                "https://redirect.example/cb", "credential", "state-123",
                null, null, "cred-xyz");

        CSCOAuth2TokenResponse response = service.exchangeAuthorizationCode(
                "auth-code-1", "https://redirect.example/cb", "client-1", "secret", null);

        assertThat(response.getCredentialID())
                .as("credentialID from authorization request must be in token response (W4)")
                .isEqualTo("cred-xyz");
    }

    @Test
    void exchangeAuthorizationCode_withPkceS256_validVerifier_returnsToken() throws Exception {
        OAuth2Client client = new OAuth2Client();
        client.setClientId("client-1");
        client.setClientSecret("hashed");
        client.setScopes(Set.of("service"));

        when(clientRepository.findByClientId("client-1")).thenReturn(Optional.of(client));

        // Pre-computed: SHA256("verifier-12345678901234567890") base64url-encoded
        String verifier = "verifier-12345678901234567890";
        String challenge = computeS256Challenge(verifier);

        service.storeAuthorizationRequest("auth-code-pkce", "client-1",
                "https://redirect.example/cb", "service", null,
                challenge, "S256", null);

        CSCOAuth2TokenResponse response = service.exchangeAuthorizationCode(
                "auth-code-pkce", "https://redirect.example/cb", null, null, verifier);

        assertThat(response.getAccess_token()).isNotNull();
    }

    @Test
    void exchangeAuthorizationCode_withPkceS256_invalidVerifier_rejects() throws Exception {
        OAuth2Client client = new OAuth2Client();
        client.setClientId("client-1");
        client.setClientSecret("hashed");
        client.setScopes(Set.of("service"));

        when(clientRepository.findByClientId("client-1")).thenReturn(Optional.of(client));

        String challenge = computeS256Challenge("correct-verifier-12345678901234567890");

        service.storeAuthorizationRequest("auth-code-bad-pkce", "client-1",
                "https://redirect.example/cb", "service", null,
                challenge, "S256", null);

        assertThatThrownBy(() -> service.exchangeAuthorizationCode(
                "auth-code-bad-pkce", "https://redirect.example/cb", null, null,
                "wrong-verifier"))
                .isInstanceOf(CSCOAuth2Exception.class)
                .hasMessageContaining("code_verifier");
    }

    private String computeS256Challenge(String verifier) throws Exception {
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }
}
