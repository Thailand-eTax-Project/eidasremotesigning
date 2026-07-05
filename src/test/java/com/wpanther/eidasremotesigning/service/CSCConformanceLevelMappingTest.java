package com.wpanther.eidasremotesigning.service;

import com.wpanther.eidasremotesigning.exception.CSCInvalidRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CSCConformanceLevelMappingTest {

    @Test
    void mapsAllWireForms() {
        assertThat(CSCSignatureService.mapConformanceLevel("Ades-B-B")).isEqualTo(CSCSignatureService.ConformanceLevel.B);
        assertThat(CSCSignatureService.mapConformanceLevel("Ades-B")).isEqualTo(CSCSignatureService.ConformanceLevel.B);
        assertThat(CSCSignatureService.mapConformanceLevel("Ades-B-T")).isEqualTo(CSCSignatureService.ConformanceLevel.T);
        assertThat(CSCSignatureService.mapConformanceLevel("Ades-T")).isEqualTo(CSCSignatureService.ConformanceLevel.T);
        assertThat(CSCSignatureService.mapConformanceLevel("Ades-B-LT")).isEqualTo(CSCSignatureService.ConformanceLevel.LT);
        assertThat(CSCSignatureService.mapConformanceLevel("Ades-LT")).isEqualTo(CSCSignatureService.ConformanceLevel.LT);
        assertThat(CSCSignatureService.mapConformanceLevel("Ades-B-LTA")).isEqualTo(CSCSignatureService.ConformanceLevel.LTA);
        assertThat(CSCSignatureService.mapConformanceLevel("Ades-LTA")).isEqualTo(CSCSignatureService.ConformanceLevel.LTA);
    }

    @Test
    void defaultIsNullSafeAndCaseInsensitive() {
        assertThat(CSCSignatureService.mapConformanceLevel(null)).isEqualTo(CSCSignatureService.ConformanceLevel.B);
        assertThat(CSCSignatureService.mapConformanceLevel("  ")).isEqualTo(CSCSignatureService.ConformanceLevel.B);
        assertThat(CSCSignatureService.mapConformanceLevel("ades-b-lt")).isEqualTo(CSCSignatureService.ConformanceLevel.LT);
        assertThat(CSCSignatureService.mapConformanceLevel("AdES-B-LTA")).isEqualTo(CSCSignatureService.ConformanceLevel.LTA);
    }

    @Test
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> CSCSignatureService.mapConformanceLevel("Ades-B-Z"))
                .isInstanceOf(CSCInvalidRequestException.class);
        assertThatThrownBy(() -> CSCSignatureService.mapConformanceLevel("LTA"))
                .isInstanceOf(CSCInvalidRequestException.class);
        assertThatThrownBy(() -> CSCSignatureService.mapConformanceLevel("C"))
                .isInstanceOf(CSCInvalidRequestException.class);
    }
}
