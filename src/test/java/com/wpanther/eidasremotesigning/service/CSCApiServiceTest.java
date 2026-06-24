package com.wpanther.eidasremotesigning.service;

import com.wpanther.eidasremotesigning.dto.csc.CSCCertificateInfo;
import com.wpanther.eidasremotesigning.dto.csc.CSCCredentialsListRequest;
import com.wpanther.eidasremotesigning.dto.csc.CSCCredentialsListResponse;
import com.wpanther.eidasremotesigning.entity.SigningCertificate;
import com.wpanther.eidasremotesigning.repository.OAuth2ClientRepository;
import com.wpanther.eidasremotesigning.repository.SigningCertificateRepository;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider;
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
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CSCApiServiceTest {

    @Mock SigningCertificateRepository certificateRepository;
    @Mock SigningCertificateService certificateService;
    @Mock EIDASComplianceService eidasComplianceService;
    @Mock SigningLogService signingLogService;
    @Mock OAuth2ClientRepository oauth2ClientRepository;
    @Mock AsyncOperationService asyncOperationService;
    @Mock CSCAuthorizationService cscAuthorizationService;
    @Mock Executor asyncExecutor;

    CSCApiService service;

    X509Certificate validCert;
    X509Certificate expiredCert;

    @BeforeAll
    static void setupProvider() {
        if (Security.getProvider("BCFIPS") == null) {
            Security.insertProviderAt(new BouncyCastleFipsProvider(), 2);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        service = new CSCApiService(
                certificateRepository,
                certificateService,
                eidasComplianceService,
                signingLogService,
                oauth2ClientRepository,
                asyncOperationService,
                cscAuthorizationService,
                asyncExecutor,
                30);

        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(new UsernamePasswordAuthenticationToken(
                "client-1", null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(ctx);

        validCert = generateSelfSignedCert(Instant.now().plus(365, ChronoUnit.DAYS));
        expiredCert = generateSelfSignedCert(Instant.now().minus(1, ChronoUnit.DAYS));
    }

    @Test
    void listCredentials_withoutCredentialInfo_returnsOnlyIds() {
        SigningCertificate cert = new SigningCertificate();
        cert.setId("cred-1");
        cert.setActive(true);
        cert.setStorageType("BCFKS");

        when(certificateRepository.findByClientId("client-1")).thenReturn(List.of(cert));

        CSCCredentialsListRequest request = CSCCredentialsListRequest.builder().build();
        CSCCredentialsListResponse response = service.listCredentials(request);

        assertThat(response.getCredentialIDs()).containsExactly("cred-1");
        assertThat(response.getCredentialInfos())
                .as("credentialInfo not requested → credentialInfos must be null")
                .isNull();
    }

    @Test
    void listCredentials_withCredentialInfo_buildsCredentialInfos() throws Exception {
        SigningCertificate cert = new SigningCertificate();
        cert.setId("cred-1");
        cert.setActive(true);
        cert.setStorageType("BCFKS");

        when(certificateRepository.findByClientId("client-1")).thenReturn(List.of(cert));
        lenient().when(certificateService.loadCertificateFromBCFKS(cert)).thenReturn(validCert);

        CSCCredentialsListRequest request = CSCCredentialsListRequest.builder()
                .credentialInfo(true)
                .build();

        CSCCredentialsListResponse response = service.listCredentials(request);

        assertThat(response.getCredentialIDs()).containsExactly("cred-1");
        assertThat(response.getCredentialInfos())
                .as("credentialInfo=true → credentialInfos must be populated")
                .isNotNull()
                .hasSize(1);
        CSCCertificateInfo info = response.getCredentialInfos().get(0);
        assertThat(info.getCredentialID()).isEqualTo("cred-1");
        assertThat(info.getCert()).isNotNull();
        assertThat(info.getCert().getStatus())
                .as("Non-expired cert must report status=valid (spec §11.4)")
                .isEqualTo("valid");
        assertThat(info.getCert().getCertificates())
                .as("Default (single) cert param must return certificates array")
                .isNotNull()
                .hasSize(1);
    }

    @Test
    void listCredentials_certificatesNone_omitsCertificatesArray() throws Exception {
        SigningCertificate cert = new SigningCertificate();
        cert.setId("cred-1");
        cert.setActive(true);
        cert.setStorageType("BCFKS");

        when(certificateRepository.findByClientId("client-1")).thenReturn(List.of(cert));
        lenient().when(certificateService.loadCertificateFromBCFKS(cert)).thenReturn(validCert);

        CSCCredentialsListRequest request = CSCCredentialsListRequest.builder()
                .credentialInfo(true)
                .certificates("none")
                .build();

        CSCCredentialsListResponse response = service.listCredentials(request);

        assertThat(response.getCredentialInfos()).isNotNull().hasSize(1);
        assertThat(response.getCredentialInfos().get(0).getCert().getCertificates())
                .as("certificates=none must yield null certificates array on each entry")
                .isNull();
    }

    @Test
    void listCredentials_onlyValidTrue_filtersExpiredCerts() throws Exception {
        SigningCertificate valid = new SigningCertificate();
        valid.setId("valid-1");
        valid.setActive(true);
        valid.setStorageType("BCFKS");

        SigningCertificate expired = new SigningCertificate();
        expired.setId("expired-1");
        expired.setActive(true);
        expired.setStorageType("BCFKS");

        SigningCertificate inactive = new SigningCertificate();
        inactive.setId("inactive-1");
        inactive.setActive(false);
        inactive.setStorageType("BCFKS");

        when(certificateRepository.findByClientId("client-1"))
                .thenReturn(List.of(valid, expired, inactive));
        lenient().when(certificateService.loadCertificateFromBCFKS(valid)).thenReturn(validCert);
        lenient().when(certificateService.loadCertificateFromBCFKS(expired)).thenReturn(expiredCert);
        lenient().when(certificateService.loadCertificateFromBCFKS(inactive)).thenReturn(validCert);

        CSCCredentialsListRequest request = CSCCredentialsListRequest.builder()
                .onlyValid(true)
                .build();

        CSCCredentialsListResponse response = service.listCredentials(request);

        assertThat(response.getCredentialIDs())
                .as("onlyValid=true must filter out expired and inactive certs")
                .containsExactly("valid-1");
    }

    @Test
    void listCredentials_onlyValidTrue_excludesInactiveCertsEvenWhenCertValid() {
        SigningCertificate inactive = new SigningCertificate();
        inactive.setId("inactive-1");
        inactive.setActive(false);
        inactive.setStorageType("BCFKS");

        when(certificateRepository.findByClientId("client-1")).thenReturn(List.of(inactive));

        CSCCredentialsListRequest request = CSCCredentialsListRequest.builder()
                .onlyValid(true)
                .build();

        CSCCredentialsListResponse response = service.listCredentials(request);

        assertThat(response.getCredentialIDs())
                .as("Inactive cert (active=false) must be filtered out by onlyValid=true")
                .isEmpty();
    }

    @Test
    void mapToCscCertificateInfo_setsExpiredStatusForExpiredCert() throws Exception {
        SigningCertificate cert = new SigningCertificate();
        cert.setId("cred-1");
        cert.setActive(true);
        cert.setStorageType("BCFKS");

        when(certificateRepository.findByClientId("client-1")).thenReturn(List.of(cert));
        lenient().when(certificateService.loadCertificateFromBCFKS(cert)).thenReturn(expiredCert);

        CSCCredentialsListRequest request = CSCCredentialsListRequest.builder()
                .credentialInfo(true)
                .build();

        CSCCredentialsListResponse response = service.listCredentials(request);

        assertThat(response.getCredentialInfos()).isNotNull().hasSize(1);
        CSCCertificateInfo info = response.getCredentialInfos().get(0);
        assertThat(info.getCert().getStatus())
                .as("Expired cert must report status=expired (spec §11.4)")
                .isEqualTo("expired");
    }

    @Test
    void listCredentials_pkcs11Cert_passesOnlyValidFilterWithoutPinLoad() {
        // PKCS#11 certs cannot be loaded without PIN → must still pass through isActive() filter only.
        SigningCertificate pkcs11 = new SigningCertificate();
        pkcs11.setId("pkcs-1");
        pkcs11.setActive(true);
        pkcs11.setStorageType("PKCS11");

        when(certificateRepository.findByClientId("client-1")).thenReturn(List.of(pkcs11));

        CSCCredentialsListRequest request = CSCCredentialsListRequest.builder()
                .onlyValid(true)
                .build();

        CSCCredentialsListResponse response = service.listCredentials(request);

        assertThat(response.getCredentialIDs())
                .as("PKCS#11 cert with active=true must pass onlyValid filter (PIN not required for list)")
                .containsExactly("pkcs-1");
    }

    @Test
    void listCredentials_credentialInfo_awsKmsCert_buildsInfoFromCertificateData() throws Exception {
        SigningCertificate cert = new SigningCertificate();
        cert.setId("kms-1");
        cert.setActive(true);
        cert.setStorageType("AWSKMS");
        cert.setCertificateData(Base64.getEncoder().encodeToString(validCert.getEncoded()));

        when(certificateRepository.findByClientId("client-1")).thenReturn(List.of(cert));

        CSCCredentialsListRequest request = CSCCredentialsListRequest.builder()
                .credentialInfo(true)
                .build();

        CSCCredentialsListResponse response = service.listCredentials(request);

        assertThat(response.getCredentialInfos())
                .as("AWSKMS cert must be loaded from certificateData without PIN")
                .isNotNull()
                .hasSize(1);
        assertThat(response.getCredentialInfos().get(0).getCert().getStatus()).isEqualTo("valid");
    }

    @Test
    void listCredentials_credentialInfo_awsKmsCert_filteredByOnlyValidWhenExpired() throws Exception {
        X509Certificate expired = generateSelfSignedCert(Instant.now().minus(10, ChronoUnit.DAYS));

        SigningCertificate cert = new SigningCertificate();
        cert.setId("kms-expired");
        cert.setActive(true);
        cert.setStorageType("AWSKMS");
        cert.setCertificateData(Base64.getEncoder().encodeToString(expired.getEncoded()));

        when(certificateRepository.findByClientId("client-1")).thenReturn(List.of(cert));

        CSCCredentialsListRequest request = CSCCredentialsListRequest.builder()
                .onlyValid(true)
                .credentialInfo(true)
                .build();

        CSCCredentialsListResponse response = service.listCredentials(request);

        assertThat(response.getCredentialIDs())
                .as("Expired AWSKMS cert must be filtered by onlyValid=true (X.509 notAfter check)")
                .isEmpty();
    }

    /**
     * Generates a self-signed X.509Certificate with the given notAfter date using BCFIPS.
     */
    private X509Certificate generateSelfSignedCert(Instant notAfter) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BCFIPS");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        X500Name name = new X500Name("CN=test");
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Instant notBefore = Instant.now().minus(1, ChronoUnit.DAYS);

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, serial, Date.from(notBefore), Date.from(notAfter), name, kp.getPublic());

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BCFIPS")
                .build(kp.getPrivate());

        return new JcaX509CertificateConverter()
                .setProvider("BCFIPS")
                .getCertificate(builder.build(signer));
    }
}
