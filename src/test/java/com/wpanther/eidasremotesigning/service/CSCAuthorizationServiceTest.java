package com.wpanther.eidasremotesigning.service;

import com.wpanther.eidasremotesigning.dto.csc.CSCAuthorizeRequest;
import com.wpanther.eidasremotesigning.dto.csc.CSCAuthorizeResponse;
import com.wpanther.eidasremotesigning.dto.csc.CSCAuthorizeStatusRequest;
import com.wpanther.eidasremotesigning.dto.csc.CSCAuthorizeStatusResponse;
import com.wpanther.eidasremotesigning.entity.SigningCertificate;
import com.wpanther.eidasremotesigning.entity.TransactionAuthorization;
import com.wpanther.eidasremotesigning.exception.SigningException;
import com.wpanther.eidasremotesigning.exception.SigningInProgressException;
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
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

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
        // lenient: not all tests call currentClientId(), but SecurityContextHolder is shared state
        lenient().when(auth.getName()).thenReturn("test-client");
        SecurityContext ctx = mock(SecurityContext.class);
        lenient().when(ctx.getAuthentication()).thenReturn(auth);
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
    void getAuthorizeStatus_authorizedTransaction_returnsSadAndExpiresIn() {
        TransactionAuthorization authorized = TransactionAuthorization.builder()
                .id("handle-1")
                .clientId("test-client")
                .sad("active-sad")
                .status("AUTHORIZED")
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        when(transactionRepository.findById("handle-1")).thenReturn(Optional.of(authorized));

        CSCAuthorizeStatusRequest request = CSCAuthorizeStatusRequest.builder()
                .handle("handle-1")
                .build();

        CSCAuthorizeStatusResponse response = service.getAuthorizeStatus(request);

        assertThat(response.getSAD()).isEqualTo("active-sad");
        assertThat(response.getExpiresIn()).isPositive();
    }

    @Test
    void getAuthorizeStatus_pendingTransaction_throwsSigningInProgressException() {
        TransactionAuthorization pending = TransactionAuthorization.builder()
                .id("handle-pending")
                .clientId("test-client")
                .sad("pending-sad")
                .status("AUTHORIZATION_INITIALIZED")
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        when(transactionRepository.findById("handle-pending")).thenReturn(Optional.of(pending));

        CSCAuthorizeStatusRequest request = CSCAuthorizeStatusRequest.builder()
                .handle("handle-pending")
                .build();

        assertThatThrownBy(() -> service.getAuthorizeStatus(request))
                .isInstanceOf(SigningInProgressException.class)
                .as("AUTHORIZATION_INITIALIZED status must throw SigningInProgressException for 202 response");
    }

    @Test
    void getAuthorizeStatus_expiredTransaction_throwsSigningException() {
        TransactionAuthorization expired = TransactionAuthorization.builder()
                .id("handle-expired")
                .clientId("test-client")
                .sad("old-sad")
                .status("AUTHORIZED")
                .expiresAt(Instant.now().minusSeconds(60))
                .build();

        when(transactionRepository.findById("handle-expired")).thenReturn(Optional.of(expired));

        CSCAuthorizeStatusRequest request = CSCAuthorizeStatusRequest.builder()
                .handle("handle-expired")
                .build();

        assertThatThrownBy(() -> service.getAuthorizeStatus(request))
                .isInstanceOf(SigningException.class)
                .hasMessageContaining("expired");
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
