package com.wpanther.eidasremotesigning.dto.csc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
    private AuthInfo auth;

    @JsonProperty("SCAL")
    private String scal;

    private Integer multisign;
    private String lang;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CSCCertificateDetails {
        private String subjectDN;
        private String issuerDN;
        private String serialNumber;
        private String status;
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
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CSCKeyInfo {
        private String status;
        private String[] algo;
        private Integer len;
        private String curve;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AuthInfo {
        private String mode;
        private String expression;
        private List<AuthObject> objects;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AuthObject {
        private String type;
        private String id;
        private String format;
        private String generator;
        private String label;
        private String description;
    }
}