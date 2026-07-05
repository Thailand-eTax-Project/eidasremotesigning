package com.wpanther.eidasremotesigning.testsupport;

import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource;
import eu.europa.esig.dss.spi.x509.tsp.TSPSource;
import eu.europa.esig.dss.validation.CommonCertificateVerifier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Test-only DSS beans: a deterministic {@link TestTSPSource} and a verifier that
 * trusts its TSA certificate, so LT/LTA validation passes without network.
 *
 * <p>{@code application-test.yml} need not set {@code app.dss.truststore.path}.
 * Marked {@code @Primary} to override the production {@code DSSConfig} beans when
 * the test context loads.
 *
 * <p>Important: when this test verifier is the {@code @Primary} bean (overriding
 * the production {@code DSSConfig#certificateVerifier()}), CSCSignatureService
 * and the DSS validation APIs all receive THIS verifier, not the production
 * one. So we must propagate the production verifier's revocation sources
 * (CRL/OCSP) here — otherwise LT/LTA signing fails at DSS-level validation
 * material fetch with
 * <pre>"Revocation data is missing for one or more certificate(s)"</pre>
 * because the test verifier starts with no revocation source configured.
 * Copying the trusted cert sources alone is not enough; without a CRL source
 * DSS cannot honour CRL Distribution Point AIA even when the URL is reachable.
 */
@Configuration
public class DssTestConfig {

    @Bean
    public TestTSPSource testTspSourceInstance() throws Exception {
        return new TestTSPSource();
    }

    @Bean
    @Primary
    public TSPSource testTspSource(TestTSPSource testTspSource) {
        return testTspSource;
    }

    /**
     * Self-contained verifier for tests: starts from whatever the production
     * {@code DSSConfig} wired (potentially an empty trusted source if the dev-PKI
     * trust store is missing on CI) and adds the mock TSA cert as a trusted-list
     * source so mock timestamps validate.
     */
    @Bean
    @Primary
    public CommonCertificateVerifier testCertificateVerifier(
            @Qualifier("certificateVerifier") CommonCertificateVerifier prodVerifier,
            TestTSPSource testTspSource) {
        CommonCertificateVerifier v = new CommonCertificateVerifier();
        v.setTrustedCertSources(prodVerifier.getTrustedCertSources());

        // Propagate the production verifier's CRL source. Without a revocation
        // fetcher the test verifier makes DSS report "Revocation data is missing
        // for one or more certificate(s)" for LT/LTA — even though the production
        // CRL endpoints work fine. The Trust Store + trusted TSA cert alone are
        // not enough; LT/LTA chain validation requires a revocation source.
        //
        // Deliberately do NOT propagate the production OCSP source: the dev
        // OpenSSL responder never answers DSS's POST, so OCSP-first would add a
        // read-timeout stall per certificate fetch to every LT/LTA test. CRL is
        // the deterministic revocation path for the dev PKI.
        v.setCrlSource(prodVerifier.getCrlSource());

        // Add the mock TSA cert as a TRUSTED_LIST source so timestamps it mints
        // validate. CommonCertificateSource is type OTHER and DSS rejects it in
        // trustedCertSources; CommonTrustedCertificateSource extends it with
        // type TRUSTED_LIST, which DSS's verifier accepts.
        CommonTrustedCertificateSource tsa = new CommonTrustedCertificateSource();
        tsa.addCertificate(new CertificateToken(testTspSource.getTsaCertificate()));
        v.addAdjunctCertSources(tsa);
        v.addTrustedCertSources(tsa);
        return v;
    }
}
