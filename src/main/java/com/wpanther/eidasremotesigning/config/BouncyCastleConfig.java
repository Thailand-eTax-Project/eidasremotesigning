package com.wpanther.eidasremotesigning.config;

import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.Security;

/**
 * Registers the plain (non-FIPS) BouncyCastle provider under the standard name "BC".
 * DSS 5.13's DSSSecurityProvider hardcodes new BouncyCastleProvider(); with bcprov
 * on the classpath that resolves natively, so we no longer inject a provider via
 * DSSSecurityProvider.setSecurityProvider() and we no longer ship a verifier stub.
 * The bean name bouncyCastleProvider is referenced by BCFKSService's @DependsOn.
 */
@Slf4j
@Configuration
public class BouncyCastleConfig {

    @Bean
    public BouncyCastleProvider bouncyCastleProvider() {
        BouncyCastleProvider existing = (BouncyCastleProvider) Security.getProvider("BC");
        if (existing != null) {
            log.info("BouncyCastle (BC) provider already registered");
            return existing;
        }
        BouncyCastleProvider provider = new BouncyCastleProvider();
        Security.addProvider(provider);
        log.info("Registered BouncyCastle (BC) provider");
        return provider;
    }
}
