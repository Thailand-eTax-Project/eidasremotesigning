package com.wpanther.eidasremotesigning.config;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.security.Security;

import static org.assertj.core.api.Assertions.assertThat;

class BouncyCastleConfigTest {

    @AfterEach
    void cleanup() {
        Security.removeProvider("BC");
    }

    @Test
    void bouncyCastleProvider_registersProviderWithCorrectName() {
        BouncyCastleConfig config = new BouncyCastleConfig();
        config.bouncyCastleProvider();

        assertThat(Security.getProvider("BC")).isNotNull();
        assertThat(Security.getProvider("BC").getName()).isEqualTo("BC");
    }

    @Test
    void bouncyCastleProvider_isIdempotent_whenCalledTwice() {
        BouncyCastleConfig config = new BouncyCastleConfig();
        config.bouncyCastleProvider();
        int countAfterFirst = Security.getProviders().length;

        config.bouncyCastleProvider();

        assertThat(Security.getProviders().length).isEqualTo(countAfterFirst);
    }

    @Test
    void bouncyCastleProvider_doesNotDisplaceExistingProviders() {
        String firstProviderBefore = Security.getProviders()[0].getName();

        BouncyCastleConfig config = new BouncyCastleConfig();
        config.bouncyCastleProvider();

        assertThat(Security.getProviders()[0].getName()).isEqualTo(firstProviderBefore);
    }

    @Test
    void bouncyCastleProvider_beanNameMatchesDependsOnContract() {
        try (var ctx = new AnnotationConfigApplicationContext(BouncyCastleConfig.class)) {
            assertThat(ctx.getBean("bouncyCastleProvider")).isInstanceOf(BouncyCastleProvider.class);
        }
    }
}
