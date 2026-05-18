package com.wpanther.eidasremotesigning.dto.csc;

import com.fasterxml.jackson.annotation.JsonInclude;
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
public class CSCSignDocumentRequest {
    @NotBlank(message = "clientId is required")
    private String clientId;

    private CSCBaseRequest.Credentials credentials;

    @NotBlank(message = "credentialID is required")
    private String credentialID;

    private String SAD;

    private String documentDigest;

    @NotBlank(message = "hashAlgo is required")
    private String hashAlgo;

    private String operationMode;

    private SignatureOptions signatureOptions;

    private String document;
}
