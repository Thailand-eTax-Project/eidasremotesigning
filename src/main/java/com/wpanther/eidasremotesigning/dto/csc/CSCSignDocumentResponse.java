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
public class CSCSignDocumentResponse {

    @JsonProperty("DocumentWithSignature")
    private List<String> documentWithSignature;

    @JsonProperty("SignatureObject")
    private List<String> signatureObject;

    private String responseID;

    private ValidationInfo validationInfo;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ValidationInfo {
        private List<String> ocsp;
        private List<String> crl;
        private List<String> certificates;
    }
}