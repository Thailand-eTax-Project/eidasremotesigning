package com.wpanther.eidasremotesigning.config;

import eu.europa.esig.dss.service.tsp.OnlineTSPSource;
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
                verifier.setTrustedCertSources(
                        new KeyStoreCertificateSource(truststorePath, truststoreType, truststorePassword));
                log.info("DSS trust store loaded from {} ({})", truststorePath, truststoreType);
            } catch (Exception e) {
                log.warn("Could not load DSS trust store from {}: {}", truststorePath, e.getMessage());
            }
        } else {
            log.warn("app.dss.truststore.path not set or missing; verifier has no explicit trusted "
                    + "source (B/T still work; LT/LTA embed validation material via AIA)");
        }
        return verifier;
    }

    @Bean
    public TSPSource tspSource() {
        return new OnlineTSPSource(tspUrl);
    }
}
