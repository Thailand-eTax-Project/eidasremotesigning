package com.wpanther.eidasremotesigning.config;

import eu.europa.esig.dss.service.crl.OnlineCRLSource;
import eu.europa.esig.dss.service.http.commons.CommonsDataLoader;
import eu.europa.esig.dss.service.tsp.OnlineTSPSource;
import eu.europa.esig.dss.spi.x509.CommonTrustedCertificateSource;
import eu.europa.esig.dss.spi.x509.KeyStoreCertificateSource;
import eu.europa.esig.dss.spi.x509.tsp.TSPSource;
import eu.europa.esig.dss.validation.CommonCertificateVerifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

/**
 * Shared DSS wiring for signature verification and timestamping.
 * <p>
 * Exposes two singletons consumed by {@code CSCSignatureService} (signed in
 * Task 3 — wiring) and by LT/LTA validation flows:
 * <ul>
 *   <li>{@link #certificateVerifier()} — common DSS verifier loaded once
 *       from the trust store at startup.</li>
 *   <li>{@link #tspSource()} — RFC 3161 timestamp source (online by default;
 *       overridden in tests by {@code DssTestConfig#testTspSource}).</li>
 * </ul>
 */
@Slf4j
@Configuration
public class DSSConfig {

    @Value("${app.dss.truststore.path:}")
    private String truststorePath;

    @Value("${app.dss.truststore.type:BCFKS}")
    private String truststoreType;

    @Value("${app.dss.truststore.password:}")
    private String truststorePassword;

    @Value("${app.tsp.url:https://freetsa.org/tsr}")
    private String tspUrl;

    @Bean
    public CommonCertificateVerifier certificateVerifier() {
        CommonCertificateVerifier verifier = new CommonCertificateVerifier();
        if (truststorePath != null && !truststorePath.isBlank()
                && new File(truststorePath).isFile()) {
            try {
                // DSS rejects CertificateSource instances of type OTHER in
                // setTrustedCertSources (it only accepts TRUSTED_STORE /
                // TRUSTED_LIST). KeyStoreCertificateSource's declared type is
                // OTHER, so wrap its entries in a CommonTrustedCertificateSource
                // via importAsTrusted() before handing it to the verifier. This
                // is the same pattern DssTestConfig uses for the TestTSA cert.
                CommonTrustedCertificateSource trusted = new CommonTrustedCertificateSource();
                KeyStoreCertificateSource keystoreSource = new KeyStoreCertificateSource(
                        truststorePath, truststoreType, truststorePassword);
                trusted.importAsTrusted(keystoreSource);
                verifier.addTrustedCertSources(trusted);
                log.info("DSS trust store loaded from {} ({} entries, type {})",
                        truststorePath, trusted.getCertificates().size(), truststoreType);
            } catch (Exception e) {
                log.warn("Could not load DSS trust store from {}: {}", truststorePath, e.getMessage());
            }
        } else {
            log.warn("app.dss.truststore.path not set or missing; verifier has no explicit trusted "
                    + "source (B/T still work; LT/LTA embed validation material via AIA)");
        }

        // Always wire an explicit OnlineCRLSource so DSS fetches CRLs from the
        // cert's CRL Distribution Point via the standard CommonsDataLoader
        // (works against HTTP/1.1 responders — the local dev-PKI nginx serves
        // CRLs correctly). Without an explicit CRL source DSS relies on the
        // bundled default AIA source, which mixes AIA-OCSP + AIA-CRL fetches
        // and silently drops revocation data when the OCSP responder (e.g.
        // OpenSSL `ocsp` server) doesn't return a complete response body.
        // For LT/LTA validation this surfaces as the
        //   "Revocation data is missing for one or more certificate(s)"
        // error in CSCSignatureService. Explicit CRL source + the trust-store
        // chain pinning above is the documented DSS pattern for that case.
        OnlineCRLSource crlSource = new OnlineCRLSource(new CommonsDataLoader());
        verifier.setCrlSource(crlSource);
        log.info("DSS OnlineCRLSource wired (AIA-CRL fetch via CommonsDataLoader)");

        return verifier;
    }

    @Bean
    public TSPSource tspSource() {
        return new OnlineTSPSource(tspUrl);
    }
}
