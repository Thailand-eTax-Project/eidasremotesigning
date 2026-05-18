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
public class CSCAuthorizeRequest {

    private CSCBaseRequest.Credentials credentials;

    @NotBlank(message = "credentialID is required")
    private String credentialID;

    private Integer numSignatures;

    private Long validityPeriod;

    private String description;
}
