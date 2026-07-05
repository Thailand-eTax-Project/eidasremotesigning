package com.wpanther.eidasremotesigning.service;

import com.wpanther.eidasremotesigning.dto.DigestSigningRequest;
import com.wpanther.eidasremotesigning.exception.CSCUnsupportedOperationException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link CSCSignatureService#mapSignatureFormat(String)}.
 *
 * <p>Locks in the CSC v2.0 signature_format wire-value normalization:
 * "P"/"PADES" -> PADES, "X"/"XADES" -> XADES, anything else (including
 * "C", "J", null) -> {@link CSCUnsupportedOperationException}. Also asserts
 * the helper trims surrounding whitespace and accepts mixed-case input, so
 * the implementation cannot silently regress to a {@code .toUpperCase()} or
 * {@code .equalsIgnoreCase()} comparison without these tests failing.
 */
class CSCSignatureFormatMappingTest {

    @Test
    void mapsCscWireValues() {
        assertThat(CSCSignatureService.mapSignatureFormat("P"))
                .isEqualTo(DigestSigningRequest.SignatureType.PADES);
        assertThat(CSCSignatureService.mapSignatureFormat("X"))
                .isEqualTo(DigestSigningRequest.SignatureType.XADES);
    }

    @Test
    void mapsLegacyValuesCaseInsensitively() {
        assertThat(CSCSignatureService.mapSignatureFormat("pades"))
                .isEqualTo(DigestSigningRequest.SignatureType.PADES);
        assertThat(CSCSignatureService.mapSignatureFormat("XAdES"))
                .isEqualTo(DigestSigningRequest.SignatureType.XADES);
    }

    @Test
    void rejectsUnsupportedFormats() {
        assertThatThrownBy(() -> CSCSignatureService.mapSignatureFormat("C"))
                .isInstanceOf(CSCUnsupportedOperationException.class);
        assertThatThrownBy(() -> CSCSignatureService.mapSignatureFormat("J"))
                .isInstanceOf(CSCUnsupportedOperationException.class);
        assertThatThrownBy(() -> CSCSignatureService.mapSignatureFormat(null))
                .isInstanceOf(CSCUnsupportedOperationException.class);
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(CSCSignatureService.mapSignatureFormat("  pades  "))
                .as("mapSignatureFormat must trim whitespace before normalizing case")
                .isEqualTo(DigestSigningRequest.SignatureType.PADES);
        assertThat(CSCSignatureService.mapSignatureFormat("\tX\n"))
                .as("mapSignatureFormat must trim surrounding tabs/newlines too")
                .isEqualTo(DigestSigningRequest.SignatureType.XADES);
    }

    @Test
    void acceptsMixedCaseInput() {
        assertThat(CSCSignatureService.mapSignatureFormat("xades"))
                .as("lowercase 'xades' must map to XADES — guards against dropping .toUpperCase()")
                .isEqualTo(DigestSigningRequest.SignatureType.XADES);
        assertThat(CSCSignatureService.mapSignatureFormat("Pades"))
                .as("mixed-case 'Pades' must map to PADES — guards against dropping case normalization")
                .isEqualTo(DigestSigningRequest.SignatureType.PADES);
    }
}
