package com.wpanther.eidasremotesigning.validation;

import com.wpanther.eidasremotesigning.dto.csc.CSCAuthorizeRequest;
import com.wpanther.eidasremotesigning.dto.csc.CSCSignDocumentRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AtLeastOneOfValidatorTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void authorizeRequest_authDataValueCanBeNull() {
        CSCAuthorizeRequest.AuthDataEntry entry = CSCAuthorizeRequest.AuthDataEntry.builder()
                .id("biometric")
                .value(null)
                .build();
        Set<ConstraintViolation<CSCAuthorizeRequest.AuthDataEntry>> violations = validator.validate(entry);
        assertThat(violations).isEmpty();
    }

    @Test
    void authorizeRequest_authDataValueCanBeBlank() {
        CSCAuthorizeRequest.AuthDataEntry entry = CSCAuthorizeRequest.AuthDataEntry.builder()
                .id("biometric")
                .value("")
                .build();
        Set<ConstraintViolation<CSCAuthorizeRequest.AuthDataEntry>> violations = validator.validate(entry);
        assertThat(violations).isEmpty();
    }

    @Test
    void signDocRequest_withDocumentDigests_passes() {
        CSCSignDocumentRequest request = CSCSignDocumentRequest.builder()
                .SAD("some-sad")
                .documentDigests(List.of(CSCSignDocumentRequest.DocumentDigestEntry.builder()
                        .signature_format("P")
                        .signAlgo("1.2.840.113549.1.1.11")
                        .build()))
                .build();
        Set<ConstraintViolation<CSCSignDocumentRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    void signDocRequest_withDocuments_passes() {
        CSCSignDocumentRequest request = CSCSignDocumentRequest.builder()
                .SAD("some-sad")
                .documents(List.of(CSCSignDocumentRequest.DocumentEntry.builder()
                        .document("dGVzdA==")
                        .signature_format("X")
                        .signAlgo("1.2.840.113549.1.1.11")
                        .build()))
                .build();
        Set<ConstraintViolation<CSCSignDocumentRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    void signDocRequest_neitherDocumentsNorDigests_fails() {
        CSCSignDocumentRequest request = CSCSignDocumentRequest.builder()
                .SAD("some-sad")
                .build();
        Set<ConstraintViolation<CSCSignDocumentRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations.iterator().next().getMessage())
                .contains("documentDigests")
                .contains("documents");
    }

    @Test
    void signDocRequest_credentialIDCanBeNull() {
        CSCSignDocumentRequest request = CSCSignDocumentRequest.builder()
                .SAD("some-sad")
                .documentDigests(List.of(CSCSignDocumentRequest.DocumentDigestEntry.builder()
                        .signature_format("P")
                        .signAlgo("1.2.840.113549.1.1.11")
                        .build()))
                .build();
        Set<ConstraintViolation<CSCSignDocumentRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }
}
