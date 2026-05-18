package com.wpanther.eidasremotesigning.dto.csc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
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
public class CSCSignDocumentRequest {

    @NotBlank(message = "credentialID is required")
    private String credentialID;

    private String signatureQualifier;

    private String SAD;

    private List<DocumentDigestEntry> documentDigests;

    private List<DocumentEntry> documents;

    private String operationMode;

    private Integer validity_period;

    private String response_uri;

    private String clientData;

    private Boolean returnValidationInfo;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DocumentDigestEntry {
        private List<String> hashes;
        private String hashAlgorithmOID;

        @NotBlank(message = "signature_format is required")
        private String signature_format;

        private String conformance_level;

        @NotBlank(message = "signAlgo is required")
        private String signAlgo;

        private String signAlgoParams;

        private String signed_envelope_property;

        private List<SignedAttribute> signed_props;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DocumentEntry {
        @NotBlank(message = "document is required")
        private String document;

        @NotBlank(message = "signature_format is required")
        private String signature_format;

        private String conformance_level;

        @NotBlank(message = "signAlgo is required")
        private String signAlgo;

        private String signAlgoParams;

        private String signed_envelope_property;

        private List<SignedAttribute> signed_props;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SignedAttribute {
        private String attribute_name;
        private String attribute_value;
    }
}