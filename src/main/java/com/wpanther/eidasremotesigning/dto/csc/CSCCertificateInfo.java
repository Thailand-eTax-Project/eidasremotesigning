package com.wpanther.eidasremotesigning.dto.csc;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CSCCertificateInfo {
    private String credentialID;
    private String description;
    private String status;
    private CSCCertificateDetails cert;
    private CSCKeyInfo key;
    private String authMode;
    private String scal;
    private Integer multisign;
    private CSCPINInfo pin;
    private CSCOTPInfo otp;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CSCCertificateDetails {
        private String subjectDN;
        private String issuerDN;
        private String serialNumber;
        private String[] policies;
        private String[] keyUsage;
        private String validFrom;
        private String validTo;
        private String[] certificates;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CSCKeyInfo {
        private String status;
        private String[] algo;
        private Integer len;
        private String[] curveIds;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CSCPINInfo {
        private String presence;
        private String format;
        private String label;
        private String description;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CSCOTPInfo {
        private String presence;
        private String type;
        private String provider;
        private String description;
    }
}
