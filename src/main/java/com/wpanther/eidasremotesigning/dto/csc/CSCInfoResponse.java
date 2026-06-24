package com.wpanther.eidasremotesigning.dto.csc;

import com.fasterxml.jackson.annotation.JsonInclude;
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
public class CSCInfoResponse {
    private String specs;
    private String name;
    private String logo;
    private String region;
    private String lang;
    private String description;
    private List<String> authType;
    private List<String> methods;
    private List<String> timeStampPolicies;
    private SignAlgorithms signAlgorithms;
    private SignatureFormats signature_formats;
    private List<String> conformance_levels;
    private String oauth2;
    private Boolean asynchronousOperationMode;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SignAlgorithms {
        private List<String> algos;
        private List<String> algoParams;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SignatureFormats {
        private List<String> formats;
        private List<List<String>> envelope_properties;
    }
}
