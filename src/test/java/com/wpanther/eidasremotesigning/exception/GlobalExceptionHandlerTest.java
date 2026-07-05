package com.wpanther.eidasremotesigning.exception;

import com.wpanther.eidasremotesigning.dto.csc.CSCErrorResponse;
import com.wpanther.eidasremotesigning.util.CSCConstants;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GlobalExceptionHandler#handleCSCUnsupportedOperation(CSCUnsupportedOperationException)}.
 *
 * <p>Locks in the CSC v2.0 spec-compliant response shape for the {@code "C"},
 * {@code "J"}, and unknown/null {@code signature_format} rejection path in
 * {@link com.wpanther.eidasremotesigning.service.CSCSignatureService#mapSignatureFormat(String)}.
 * Anyone who changes the {@code error} code, swaps the HTTP status away from
 * 400, or drops {@code errorDescription} without realizing it's part of the
 * CSC spec contract will trip these tests.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleCSCUnsupportedOperation_returnsSpecCompliantCscErrorResponse() {
        ResponseEntity<CSCErrorResponse> response =
                handler.handleCSCUnsupportedOperation(new CSCUnsupportedOperationException("test message"));

        assertThat(response.getStatusCode())
                .as("the supported signature_format cases reject via HTTP 400 (CSC spec)")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError())
                .as("error must equal CSCConstants.ERROR_UNSUPPORTED_OPERATION spec string")
                .isEqualTo(CSCConstants.ERROR_UNSUPPORTED_OPERATION)
                .isEqualTo("unsupported_operation");
        assertThat(response.getBody().getErrorDescription())
                .as("error_description must carry the thrower-provided message")
                .isEqualTo("test message");
    }
}
