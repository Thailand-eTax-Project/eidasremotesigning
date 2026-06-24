package com.wpanther.eidasremotesigning.service;

import com.wpanther.eidasremotesigning.controller.CSCOAuth2Controller.CSCOAuth2Exception;
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
    void tokenResponse_includesCredentialID_whenSet() {
        OAuth2Client client = new OAuth2Client();
        client.setClientId("client-1");
        client.setClientSecret("hashed");
        client.setScopes(Set.of("credential"));

        when(clientRepository.findByClientId("client-1")).thenReturn(Optional.of(client));
        when(passwordEncoder.matches("secret", "hashed")).thenReturn(true);

        var tokenResponse = service.clientCredentialsGrant("client-1", "secret");
        assertThat(tokenResponse.getAccess_token()).isNotNull();
    }
}
