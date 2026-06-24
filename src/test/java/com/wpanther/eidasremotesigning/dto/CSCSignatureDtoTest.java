package com.wpanther.eidasremotesigning.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.eidasremotesigning.dto.csc.CSCTimestampResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CSCSignatureDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void timestampResponse_hasTimestampField() throws Exception {
        CSCTimestampResponse response = CSCTimestampResponse.builder()
                .timestamp("dGVzdA==")
                .build();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertThat(json.has("timestamp")).isTrue();
        assertThat(json.get("timestamp").asText()).isEqualTo("dGVzdA==");
        assertThat(json.has("timestampToken"))
                .as("deprecated field 'timestampToken' must not be serialized")
                .isFalse();
        assertThat(json.has("timestampDigest"))
                .as("deprecated field 'timestampDigest' must not be serialized")
                .isFalse();
    }
}
