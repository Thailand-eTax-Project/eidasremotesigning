package com.wpanther.eidasremotesigning.dto.csc;

import com.fasterxml.jackson.annotation.JsonInclude;
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
public class CSCInfoResponse {
    private String specs;
    private String name;
    private String logo;
    private String region;
    private List<String> lang;
    private String description;
    private List<String> authType;
    private List<String> methods;
    private List<String> timeStampPolicies;
}
