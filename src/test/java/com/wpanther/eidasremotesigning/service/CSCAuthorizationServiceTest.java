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

    @Test
    void extendTransaction_generatesNewSadAndReturnsIt() {
        TransactionAuthorization existing = TransactionAuthorization.builder()
                .id("txn-1")
                .clientId("test-client")
                .sad("old-sad-value")
                .status("AUTHORIZED")
                .expiresAt(java.time.Instant.now().plusSeconds(300))
                .build();

        when(transactionRepository.findBySadAndClientId(eq("old-sad-value"), eq("test-client")))
                .thenReturn(Optional.of(existing));
        when(transactionRepository.save(any(TransactionAuthorization.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        com.wpanther.eidasremotesigning.dto.csc.CSCExtendTransactionRequest request =
                com.wpanther.eidasremotesigning.dto.csc.CSCExtendTransactionRequest.builder()
                        .credentialID("cred-1")
                        .SAD("old-sad-value")
                        .build();

        com.wpanther.eidasremotesigning.dto.csc.CSCExtendTransactionResponse response =
                service.extendTransaction(request);

        assertThat(response.getSAD())
                .as("spec §11.8: new SAD must be returned after extendTransaction")
                .isNotNull()
                .isNotEqualTo("old-sad-value");
        assertThat(response.getExpiresIn()).isGreaterThan(0);
    }
}
