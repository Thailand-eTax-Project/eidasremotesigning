package com.wpanther.eidasremotesigning.util;

import com.wpanther.eidasremotesigning.exception.SigningException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OIDMapperTest {

    @Test
    void toJcaHashAlgo_sha256Oid_returnsSha256() {
        assertThat(OIDMapper.toJcaHashAlgo("2.16.840.1.101.3.4.2.1")).isEqualTo("SHA-256");
    }

    @Test
    void toJcaHashAlgo_sha384Oid_returnsSha384() {
        assertThat(OIDMapper.toJcaHashAlgo("2.16.840.1.101.3.4.2.2")).isEqualTo("SHA-384");
    }

    @Test
    void toJcaHashAlgo_sha512Oid_returnsSha512() {
        assertThat(OIDMapper.toJcaHashAlgo("2.16.840.1.101.3.4.2.3")).isEqualTo("SHA-512");
    }

    @Test
    void toJcaHashAlgo_unknownOid_throwsSigningException() {
        assertThatThrownBy(() -> OIDMapper.toJcaHashAlgo("1.2.3.4.5"))
                .isInstanceOf(SigningException.class)
                .hasMessageContaining("Unsupported hash algorithm OID");
    }

    @Test
    void toOidHashAlgo_sha256_returnsOid() {
        assertThat(OIDMapper.toOidHashAlgo("SHA-256")).isEqualTo("2.16.840.1.101.3.4.2.1");
    }

    @Test
    void toOidHashAlgo_sha384_returnsOid() {
        assertThat(OIDMapper.toOidHashAlgo("SHA-384")).isEqualTo("2.16.840.1.101.3.4.2.2");
    }

    @Test
    void toOidHashAlgo_sha512_returnsOid() {
        assertThat(OIDMapper.toOidHashAlgo("SHA-512")).isEqualTo("2.16.840.1.101.3.4.2.3");
    }

    @Test
    void toOidHashAlgo_unknownAlgo_throwsSigningException() {
        assertThatThrownBy(() -> OIDMapper.toOidHashAlgo("MD5"))
                .isInstanceOf(SigningException.class)
                .hasMessageContaining("Unsupported hash algorithm JCA name");
    }

    @Test
    void toJcaSigAlgo_sha256RsaOid_returnsJcaName() {
        assertThat(OIDMapper.toJcaSigAlgo("1.2.840.113549.1.1.11")).isEqualTo("SHA256withRSA");
    }

    @Test
    void toJcaSigAlgo_sha256EcdsaOid_returnsJcaName() {
        assertThat(OIDMapper.toJcaSigAlgo("1.2.840.10045.4.3.2")).isEqualTo("SHA256withECDSA");
    }

    @Test
    void toJcaSigAlgo_unknownOid_throwsSigningException() {
        assertThatThrownBy(() -> OIDMapper.toJcaSigAlgo("9.9.9.9"))
                .isInstanceOf(SigningException.class)
                .hasMessageContaining("Unsupported signature algorithm OID");
    }

    @Test
    void toOidSigAlgo_sha256WithRsa_returnsOid() {
        assertThat(OIDMapper.toOidSigAlgo("SHA256withRSA")).isEqualTo("1.2.840.113549.1.1.11");
    }

    @Test
    void toOidSigAlgo_sha384WithRsa_returnsOid() {
        assertThat(OIDMapper.toOidSigAlgo("SHA384withRSA")).isEqualTo("1.2.840.113549.1.1.12");
    }

    @Test
    void toOidSigAlgo_sha512WithRsa_returnsOid() {
        assertThat(OIDMapper.toOidSigAlgo("SHA512withRSA")).isEqualTo("1.2.840.113549.1.1.13");
    }

    @Test
    void toOidSigAlgo_unknownAlgo_throwsSigningException() {
        assertThatThrownBy(() -> OIDMapper.toOidSigAlgo("MD5withRSA"))
                .isInstanceOf(SigningException.class)
                .hasMessageContaining("Unsupported signature algorithm JCA name");
    }

    @Test
    void toJcaKeyAlgo_rsaOid_returnsRsa() {
        assertThat(OIDMapper.toJcaKeyAlgo("1.2.840.113549.1.1.1")).isEqualTo("RSA");
    }

    @Test
    void toJcaKeyAlgo_ecOid_returnsEc() {
        assertThat(OIDMapper.toJcaKeyAlgo("1.2.840.10045.2.1")).isEqualTo("EC");
    }

    @Test
    void toOidKeyAlgo_rsa_returnsOid() {
        assertThat(OIDMapper.toOidKeyAlgo("RSA")).isEqualTo("1.2.840.113549.1.1.1");
    }

    @Test
    void toOidKeyAlgo_ec_returnsOid() {
        assertThat(OIDMapper.toOidKeyAlgo("EC")).isEqualTo("1.2.840.10045.2.1");
    }

    @Test
    void supportedSigOidsForKeyAlgo_rsa_returnsThreeRsaOids() {
        String[] oids = OIDMapper.supportedSigOidsForKeyAlgo("RSA");
        assertThat(oids).containsExactly(
                "1.2.840.113549.1.1.11",
                "1.2.840.113549.1.1.12",
                "1.2.840.113549.1.1.13"
        );
    }

    @Test
    void supportedSigOidsForKeyAlgo_ec_returnsThreeEcOids() {
        String[] oids = OIDMapper.supportedSigOidsForKeyAlgo("EC");
        assertThat(oids).containsExactly(
                "1.2.840.10045.4.3.2",
                "1.2.840.10045.4.3.3",
                "1.2.840.10045.4.3.4"
        );
    }

    @Test
    void supportedSigOidsForKeyAlgo_unknownKey_throwsSigningException() {
        assertThatThrownBy(() -> OIDMapper.supportedSigOidsForKeyAlgo("DSA"))
                .isInstanceOf(SigningException.class)
                .hasMessageContaining("No supported signature algorithms for key type");
    }

    @Test
    void roundTrip_hashAlgo_sha256() {
        String oid = OIDMapper.toOidHashAlgo("SHA-256");
        assertThat(OIDMapper.toJcaHashAlgo(oid)).isEqualTo("SHA-256");
    }

    @Test
    void roundTrip_sigAlgo_sha384WithEcdsa() {
        String oid = OIDMapper.toOidSigAlgo("SHA384withECDSA");
        assertThat(OIDMapper.toJcaSigAlgo(oid)).isEqualTo("SHA384withECDSA");
    }
}
