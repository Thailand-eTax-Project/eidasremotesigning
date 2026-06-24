package com.wpanther.eidasremotesigning.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wpanther.eidasremotesigning.dto.csc.CSCInfoResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CSCInfoResponseDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void oauth2_fieldIsString_notObject() throws Exception {
        CSCInfoResponse response = CSCInfoResponse.builder()
                .oauth2("http://localhost:9000")
                .build();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertThat(json.get("oauth2").isTextual())
                .as("oauth2 must serialize as a JSON string, not an object")
                .isTrue();
        assertThat(json.get("oauth2").asText()).isEqualTo("http://localhost:9000");
    }

    @Test
    void envelopeProperties_isArrayOfArrays() throws Exception {
        CSCInfoResponse response = CSCInfoResponse.builder()
                .signature_formats(CSCInfoResponse.SignatureFormats.builder()
                        .formats(List.of("P", "X"))
                        .envelope_properties(List.of(List.of("b64"), List.of("b64")))
                        .build())
                .build();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));
        JsonNode envelopeProps = json.get("signature_formats").get("envelope_properties");

        assertThat(envelopeProps.isArray()).isTrue();
        assertThat(envelopeProps.get(0).isArray())
                .as("Each envelope_properties entry must be an array")
                .isTrue();
    }

    @Test
    void logo_serializedWhenSet() throws Exception {
        CSCInfoResponse response = CSCInfoResponse.builder()
                .logo("http://localhost:9000/logo.png")
                .build();

        JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

        assertThat(json.get("logo").asText()).isEqualTo("http://localhost:9000/logo.png");
    }
}
