package com.wpanther.eidasremotesigning.dto.csc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CSCSignatureRequest {

    private CSCBaseRequest.Credentials credentials;

    @JsonProperty("SAD")
    private String SAD;

    @NotBlank(message = "credentialID is required")
    private String credentialID;

    private String[] hashes;

    private String hashAlgorithmOID;

    private String signAlgo;

    private String signAlgoParams;

    private String operationMode;

    private SignatureOptions signatureOptions;

    private String clientData;
}