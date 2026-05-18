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
public class CSCTimestampRequest {
    @NotBlank(message = "hash is required")
    private String hash;

    @NotBlank(message = "hashAlgo is required")
    private String hashAlgo;

    private String nonce;

    private String clientData;
}