package com.wpanther.eidasremotesigning.dto.csc;

import com.fasterxml.jackson.annotation.JsonInclude;
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
public class CSCExtendTransactionRequest {

    @NotBlank(message = "credentialID is required")
    private String credentialID;

    @NotBlank(message = "SAD is required")
    private String SAD;

    private List<String> hashes;

    private String hashAlgorithmOID;

    private String clientData;
}