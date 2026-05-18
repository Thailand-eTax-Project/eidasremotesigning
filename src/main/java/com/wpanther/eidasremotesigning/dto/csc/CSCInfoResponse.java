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
public class CSCInfoResponse {
    private String specs;
    private String name;
    private String logo;
    private String region;
    private String lang;
    private String description;
    private String[] authType;
    private String[] methods;
    private String[] timeStampPolicies;
}