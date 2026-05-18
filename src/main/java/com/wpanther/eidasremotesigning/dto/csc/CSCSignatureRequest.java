package com.wpanther.eidasremotesigning.dto.csc;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    @NotBlank(message = "clientId is required")
    private String clientId;

    private CSCBaseRequest.Credentials credentials;

    private String SAD;

    @NotBlank(message = "credentialID is required")
    private String credentialID;

    @NotBlank(message = "hashAlgo is required")
    private String hashAlgo;

    private String signAlgo;

    @NotNull(message = "hash is required")
    private String[] hash;

    private String operationMode;

    private SignatureOptions signatureOptions;
}
