package com.wpanther.eidasremotesigning.dto.csc;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CSCSignatureStatusResponse {

    private String status;
    private String errorMessage;

    // signHash results
    private String signatureAlgorithm;
    private String[] signatures;

    // signDocument results
    private List<String> documentWithSignature;
    private List<String> signatureObject;
    private String responseID;

    // Common
    private Map<String, Object> timestampData;
}
