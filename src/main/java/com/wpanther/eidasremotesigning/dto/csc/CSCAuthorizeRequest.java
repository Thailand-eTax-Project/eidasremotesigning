package com.wpanther.eidasremotesigning.dto.csc;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
public class CSCAuthorizeRequest {

    @NotBlank(message = "credentialID is required")
    private String credentialID;

    @NotNull(message = "numSignatures is required")
    @Min(value = 1, message = "numSignatures must be at least 1")
    private Integer numSignatures;

    private List<String> hashes;

    private String hashAlgorithmOID;

    private List<AuthDataEntry> authData;

    private String description;

    private String clientData;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AuthDataEntry {
        @NotBlank(message = "authData id is required")
        private String id;

        private String value;
    }
}