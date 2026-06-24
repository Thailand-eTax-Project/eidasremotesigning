package com.wpanther.eidasremotesigning.dto.csc;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CSCCredentialsListRequest {
    private Boolean credentialInfo;
    private String certificates;
    private Boolean certInfo;
    private Boolean authInfo;
    private Boolean onlyValid;
    private String lang;
    private String clientData;
}
