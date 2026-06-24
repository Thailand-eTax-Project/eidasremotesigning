package com.wpanther.eidasremotesigning.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.eidasremotesigning.dto.csc.CSCSignatureResponse;
import com.wpanther.eidasremotesigning.dto.csc.CSCTimestampResponse;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

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

    @Test
    void signatureResponse_noSignatureAlgorithmField() throws Exception {
        // Verify via reflection: the field must not exist in the class itself,
        // not just be null in this instance (which @JsonInclude(NON_NULL) would hide).
        List<String> fieldNames = Arrays.stream(CSCSignatureResponse.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toList());

        assertThat(fieldNames)
                .as("signatureAlgorithm is not in spec §11.10 response")
                .doesNotContain("signatureAlgorithm");

        // Verify JSON serialization still works for the allowed spec fields
        CSCSignatureResponse response = CSCSignatureResponse.builder()
                .signatures(new String[]{"abc123"})
                .build();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));
        assertThat(json.get("signatures").get(0).asText()).isEqualTo("abc123");
    }
}
