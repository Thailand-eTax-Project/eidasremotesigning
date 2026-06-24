package com.wpanther.eidasremotesigning.service;

import com.wpanther.eidasremotesigning.dto.csc.CSCAuthorizeRequest;
import com.wpanther.eidasremotesigning.dto.csc.CSCAuthorizeResponse;
import com.wpanther.eidasremotesigning.entity.SigningCertificate;
import com.wpanther.eidasremotesigning.entity.TransactionAuthorization;
import com.wpanther.eidasremotesigning.repository.SigningCertificateRepository;
import com.wpanther.eidasremotesigning.repository.TransactionAuthorizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CSCAuthorizationServiceTest {

    @Mock
    private SigningCertificateRepository certificateRepository;

    @Mock
    private TransactionAuthorizationRepository transactionRepository;

    @InjectMocks
    private CSCAuthorizationService service;

    @BeforeEach
    void setUpSecurityContext() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("test-client");
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);

        // Inject SecureRandom via reflection (field injection)
        try {
            var field = CSCAuthorizationService.class.getDeclaredField("secureRandom");
            field.setAccessible(true);
            field.set(service, new SecureRandom());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void authorizeCredential_syncPinAuth_returnsOnlySadNoHandle() {
        SigningCertificate cert = new SigningCertificate();
        cert.setId("cred-1");
        cert.setStorageType("BCFKS");

        when(certificateRepository.findByIdAndClientId(eq("cred-1"), eq("test-client")))
                .thenReturn(Optional.of(cert));
        when(transactionRepository.save(any(TransactionAuthorization.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CSCAuthorizeRequest request = CSCAuthorizeRequest.builder()
                .credentialID("cred-1")
                .numSignatures(1)
                .authData(List.of(CSCAuthorizeRequest.AuthDataEntry.builder()
                        .id("PIN")
                        .value("1234")
                        .build()))
                .build();

        CSCAuthorizeResponse response = service.authorizeCredential(request);

        assertThat(response.getSAD()).isNotNull();
        assertThat(response.getExpiresIn()).isGreaterThan(0);
        assertThat(response.getHandle())
                .as("handle must be null for synchronous PIN auth (spec §11.6)")
                .isNull();
    }
}
