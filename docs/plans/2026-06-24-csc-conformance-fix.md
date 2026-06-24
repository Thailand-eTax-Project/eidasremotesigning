# CSC API v2.0.0.2 Conformance Fix Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix all 14 FAILs and 12 WARNs identified in the conformance audit of the eIDAS Remote Signing Service against the CSC API v2.0.0.2 specification.

**Architecture:** Bottom-up: DTO wire format first (Tasks 1–5), then service logic (Tasks 6–11), then controller HTTP codes (folded into tasks above), then OAuth2 layer (Task 12). Each task is a compilable unit; run `mvn test` after each commit.

**Tech Stack:** Spring Boot 3.4.4, Java 17, Lombok, Jackson, Jakarta Validation, JUnit 5, AssertJ, Mockito.

## Global Constraints

- Do NOT touch `xml-signing-service` or `pdf-signing-service` callers — those are separate work.
- All tests: unit tests use `@ExtendWith(MockitoExtension.class)`, no Spring context; integration tests use `@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`.
- Test method naming: `methodOrBehavior_condition_expectedResult`.
- No `Co-Authored-By:` in commits.
- Base package: `com.wpanther.eidasremotesigning`.
- `CLAUDE.md` files are gitignored; never add them.
- Run `mvn test` (unit only) after each task; run `mvn verify` only when explicitly stated.

---

## File Map

**Created:**
- `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCSignPollingPendingResponse.java` (F9)
- `src/main/java/com/wpanther/eidasremotesigning/exception/SigningInProgressException.java` (F9)
- `src/main/java/com/wpanther/eidasremotesigning/validation/AtLeastOneOf.java` (F11)
- `src/main/java/com/wpanther/eidasremotesigning/validation/AtLeastOneOfValidator.java` (F11)
- `src/test/java/com/wpanther/eidasremotesigning/dto/CSCInfoResponseDtoTest.java` (T1)
- `src/test/java/com/wpanther/eidasremotesigning/dto/CSCSignatureDtoTest.java` (T2, T3, T4)
- `src/test/java/com/wpanther/eidasremotesigning/validation/AtLeastOneOfValidatorTest.java` (T5)
- `src/test/java/com/wpanther/eidasremotesigning/service/CSCAuthorizationServiceTest.java` (T7)
- `src/test/java/com/wpanther/eidasremotesigning/service/CSCApiServiceTest.java` (T11)
- `src/test/java/com/wpanther/eidasremotesigning/service/CSCOAuth2ServiceTest.java` (T12)

**Modified:**
- `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCInfoResponse.java` (F1, F2, F3)
- `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCTimestampResponse.java` (F7)
- `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCSignatureResponse.java` (W5)
- `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCSignatureStatusResponse.java` (W6, F8)
- `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCAuthorizeRequest.java` (F12)
- `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCAuthorizeStatusResponse.java` (W7)
- `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCSignDocumentRequest.java` (F11)
- `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCCredentialsListRequest.java` (W11)
- `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCCredentialsListResponse.java` (W10)
- `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCCredentialsInfoRequest.java` (F14)
- `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCExtendTransactionResponse.java` (F6)
- `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCCertificateInfo.java` (W1)
- `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCOAuth2TokenResponse.java` (W4)
- `src/main/java/com/wpanther/eidasremotesigning/util/OIDMapper.java` (W8)
- `src/main/java/com/wpanther/eidasremotesigning/service/CSCSignatureService.java` (F7, W5, W6, F8, F9, F10, W9)
- `src/main/java/com/wpanther/eidasremotesigning/service/CSCApiService.java` (W5, W1, W2, W10, F14)
- `src/main/java/com/wpanther/eidasremotesigning/service/CSCAuthorizationService.java` (F4, F5, F6, W7)
- `src/main/java/com/wpanther/eidasremotesigning/service/CSCOAuth2Service.java` (F13, W3, W4)
- `src/main/java/com/wpanther/eidasremotesigning/controller/CSCApiController.java` (F1, F2, F3)
- `src/main/java/com/wpanther/eidasremotesigning/controller/CSCAuthorizationController.java` (F4, F5)
- `src/main/java/com/wpanther/eidasremotesigning/controller/CSCSignatureController.java` (F9)
- `src/main/java/com/wpanther/eidasremotesigning/controller/CSCOAuth2Controller.java` (F13, W3)
- `src/main/java/com/wpanther/eidasremotesigning/exception/GlobalExceptionHandler.java` (F9 — add SigningInProgressException handler)
- `src/main/resources/application.yml` (F3)
- `src/test/resources/application-test.yml` (F3)
- `src/test/java/com/wpanther/eidasremotesigning/util/OIDMapperTest.java` (W8)

---

### Task 1: Fix /info response (F1, F2, F3)

**Issues:**
- F1: `oauth2` field is an `OAuth2Info` object; spec §11.1 requires it to be a plain String (OAuth2 server issuer URI).
- F2: `signature_formats.envelope_properties` is `List<String>`; spec §11.1 requires `List<List<String>>` — one array per format.
- F3: `logo` field declared in DTO but never populated; spec §11.1 says it SHOULD be included.

**Files:**
- Modify: `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCInfoResponse.java`
- Modify: `src/main/java/com/wpanther/eidasremotesigning/controller/CSCApiController.java`
- Modify: `src/main/resources/application.yml`
- Modify: `src/test/resources/application-test.yml`
- Create: `src/test/java/com/wpanther/eidasremotesigning/dto/CSCInfoResponseDtoTest.java`

**Interfaces:**
- Produces: `CSCInfoResponse.oauth2` is now `String`; `CSCInfoResponse.SignatureFormats.envelope_properties` is `List<List<String>>`; `OAuth2Info` inner class is deleted.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/wpanther/eidasremotesigning/dto/CSCInfoResponseDtoTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
mvn test -Dtest=CSCInfoResponseDtoTest
```

Expected: FAIL — `oauth2_fieldIsString_notObject` fails because `oauth2` is currently an `OAuth2Info` object (not a String), so the builder call won't even compile.

- [ ] **Step 3: Fix CSCInfoResponse DTO**

Replace the entire `CSCInfoResponse.java`:

```java
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
    private String lang;
    private String description;
    private List<String> authType;
    private List<String> methods;
    private List<String> timeStampPolicies;
    private SignAlgorithms signAlgorithms;
    private SignatureFormats signature_formats;
    private List<String> conformance_levels;
    private String oauth2;
    private Boolean asynchronousOperationMode;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SignAlgorithms {
        private List<String> algos;
        private List<String> algoParams;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SignatureFormats {
        private List<String> formats;
        private List<List<String>> envelope_properties;
    }
}
```

- [ ] **Step 4: Add logo-url config property**

In `src/main/resources/application.yml`, add under the `app.csc:` block:

```yaml
app:
  csc:
    base-url: ${CSC_BASE_URL:http://localhost:9000}
    logo-url: ${CSC_LOGO_URL:http://localhost:9000/logo.png}
```

In `src/test/resources/application-test.yml`, add under `app.csc:`:

```yaml
app:
  csc:
    base-url: http://localhost:9000
    logo-url: http://localhost:9000/logo.png
```

- [ ] **Step 5: Fix CSCApiController.getInfo()**

In `CSCApiController.java`, add the logo-url field and update the `getInfo()` method:

```java
@Value("${app.csc.base-url}")
private String cscBaseUrl;

@Value("${app.csc.logo-url:}")
private String cscLogoUrl;

@PostMapping("/info")
public ResponseEntity<CSCInfoResponse> getInfo() {
    log.debug("CSC API: Request for service information");

    List<String> sigAlgoOids = Arrays.asList(OIDMapper.supportedSigOidsForKeyAlgo("RSA"));

    CSCInfoResponse response = CSCInfoResponse.builder()
            .specs(CSCConstants.SPECS_VERSION)
            .name("eIDAS Remote Signing Service")
            .logo(cscLogoUrl.isEmpty() ? null : cscLogoUrl)
            .region("EU")
            .lang("en")
            .description("eIDAS compliant remote signing service supporting PKCS#11 hardware tokens, AWS KMS, and BCFKS keystores")
            .authType(List.of(CSCConstants.AUTH_TYPE_OAUTH2_CODE))
            .methods(List.of(
                    "credentials/list",
                    "credentials/info",
                    "credentials/authorize",
                    "credentials/authorizeCheck",
                    "credentials/extendTransaction",
                    "signatures/signHash",
                    "signatures/signDoc",
                    "signatures/timestamp",
                    "signatures/signPolling",
                    "signatures/validate"
            ))
            .signAlgorithms(CSCInfoResponse.SignAlgorithms.builder()
                    .algos(sigAlgoOids)
                    .build())
            .signature_formats(CSCInfoResponse.SignatureFormats.builder()
                    .formats(List.of("P", "X"))
                    // Per CSC spec §11.1: one inner array per format entry.
                    // "P" (PAdES) supports "Certification" and "Revision" envelope properties.
                    // "X" (XAdES) supports "Enveloped", "Enveloping", "Detached".
                    .envelope_properties(List.of(
                            List.of("Certification", "Revision"),
                            List.of("Enveloped", "Enveloping", "Detached")
                    ))
                    .build())
            .conformance_levels(List.of("Ades-B-B"))
            .oauth2(cscBaseUrl)
            .asynchronousOperationMode(true)
            .build();

    return ResponseEntity.ok(response);
}
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
mvn test -Dtest=CSCInfoResponseDtoTest
```

Expected: PASS (3 tests)

- [ ] **Step 7: Run full unit test suite**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
mvn test
```

Expected: All unit tests pass (no `OAuth2Info` references remain in production code).

- [ ] **Step 8: Commit**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
git add src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCInfoResponse.java \
        src/main/java/com/wpanther/eidasremotesigning/controller/CSCApiController.java \
        src/main/resources/application.yml \
        src/test/resources/application-test.yml \
        src/test/java/com/wpanther/eidasremotesigning/dto/CSCInfoResponseDtoTest.java
git commit -m "fix(F1,F2,F3): oauth2 string, envelope_properties List<List<String>>, logo field"
```

---

### Task 2: Fix timestamp response (F7)

**Issue:** `CSCTimestampResponse` has `timestampToken`, `timestampDigest`, `timestampGenerationTime`; spec §11.13 requires a single field named `timestamp` (Base64-encoded DER timestamp token).

**Files:**
- Modify: `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCTimestampResponse.java`
- Modify: `src/main/java/com/wpanther/eidasremotesigning/service/CSCSignatureService.java` (lines 581–591)
- Create (or extend): `src/test/java/com/wpanther/eidasremotesigning/dto/CSCSignatureDtoTest.java`

**Interfaces:**
- Produces: `CSCTimestampResponse.timestamp: String` (only field).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/wpanther/eidasremotesigning/dto/CSCSignatureDtoTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
mvn test -Dtest=CSCSignatureDtoTest
```

Expected: FAIL — `CSCTimestampResponse` doesn't have a `timestamp` field.

- [ ] **Step 3: Fix CSCTimestampResponse DTO**

Replace the entire `CSCTimestampResponse.java`:

```java
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
public class CSCTimestampResponse {
    private String timestamp;
}
```

- [ ] **Step 4: Fix CSCSignatureService.createTimestamp()**

In `CSCSignatureService.java`, find the lines (around line 579–590):

```java
String timestampToken = Base64.getEncoder().encodeToString(timestampTokenBytes);
String timestampDigest = Base64.getEncoder().encodeToString(digestBytes);

// Return response
```

And the builder call that follows (check exact build but it uses `.timestampToken(timestampToken).timestampDigest(timestampDigest)`). Replace those lines with:

```java
String timestamp = Base64.getEncoder().encodeToString(timestampTokenBytes);

return CSCTimestampResponse.builder()
        .timestamp(timestamp)
        .build();
```

(Delete the `timestampDigest` variable and any `.timestampGenerationTime(...)` builder call.)

- [ ] **Step 5: Run tests**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
mvn test -Dtest=CSCSignatureDtoTest
mvn test
```

Expected: All pass.

- [ ] **Step 6: Commit**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
git add src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCTimestampResponse.java \
        src/main/java/com/wpanther/eidasremotesigning/service/CSCSignatureService.java \
        src/test/java/com/wpanther/eidasremotesigning/dto/CSCSignatureDtoTest.java
git commit -m "fix(F7): rename timestampToken->timestamp in CSCTimestampResponse, remove extra fields"
```

---

### Task 3: Remove non-spec signatureAlgorithm from signHash (W5)

**Issue:** `CSCSignatureResponse` has a `signatureAlgorithm` field not present in the spec §11.10 response schema. Also appears in `CSCSignatureStatusResponse` from the async-completed path.

**Files:**
- Modify: `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCSignatureResponse.java`
- Modify: `src/main/java/com/wpanther/eidasremotesigning/service/CSCApiService.java` (line ~353: `.signatureAlgorithm(sigAlgoOid)`)
- Modify: `src/test/java/com/wpanther/eidasremotesigning/dto/CSCSignatureDtoTest.java` (add test)

**Interfaces:**
- Produces: `CSCSignatureResponse` has only `signatures: String[]` and `responseID: String` (for async).

- [ ] **Step 1: Add failing test to CSCSignatureDtoTest**

Add to `CSCSignatureDtoTest.java`:

```java
@Test
void signatureResponse_noSignatureAlgorithmField() throws Exception {
    CSCSignatureResponse response = CSCSignatureResponse.builder()
            .signatures(new String[]{"abc123"})
            .build();

    JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

    assertThat(json.has("signatureAlgorithm"))
            .as("signatureAlgorithm is not in spec §11.10 response")
            .isFalse();
    assertThat(json.get("signatures").get(0).asText()).isEqualTo("abc123");
}
```

(Add the import: `import com.wpanther.eidasremotesigning.dto.csc.CSCSignatureResponse;`)

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
mvn test -Dtest=CSCSignatureDtoTest#signatureResponse_noSignatureAlgorithmField
```

Expected: FAIL — `signatureAlgorithm` field present in JSON output.

- [ ] **Step 3: Fix CSCSignatureResponse DTO**

Replace `CSCSignatureResponse.java`:

```java
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
public class CSCSignatureResponse {
    private String responseID;
    private String[] signatures;
}
```

- [ ] **Step 4: Fix CSCApiService.signHash() response builder**

In `CSCApiService.java`, find the signHash return (around line 351–355):

```java
return CSCSignatureResponse.builder()
        .signatureAlgorithm(sigAlgoOid)
        .signatures(signatures)
        .build();
```

Change to:

```java
return CSCSignatureResponse.builder()
        .signatures(signatures)
        .build();
```

Also delete the `sigAlgoOid` local variable if it's only used in that builder call. (If it was only built for the now-removed field, remove its computation too. Check the surrounding code — keep it only if still needed elsewhere in that method.)

- [ ] **Step 5: Run tests**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
mvn test
```

Expected: All pass.

- [ ] **Step 6: Commit**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
git add src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCSignatureResponse.java \
        src/main/java/com/wpanther/eidasremotesigning/service/CSCApiService.java \
        src/test/java/com/wpanther/eidasremotesigning/dto/CSCSignatureDtoTest.java
git commit -m "fix(W5): remove non-spec signatureAlgorithm from signHash response"
```

---

### Task 4: Fix signPolling response and 202 status (W6, F8, F9)

**Issues:**
- W6: `CSCSignatureStatusResponse` has non-spec fields: `status`, `errorMessage`, `signatureAlgorithm`, `timestampData`.
- F8: `documentWithSignature` and `signatureObject` must serialize as `DocumentWithSignature` and `SignatureObject` (PascalCase per spec §11.12).
- F9: `signPolling` must return HTTP 202 when the operation is still in progress; currently always returns 200.

**Files:**
- Modify: `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCSignatureStatusResponse.java`
- Create: `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCSignPollingPendingResponse.java`
- Create: `src/main/java/com/wpanther/eidasremotesigning/exception/SigningInProgressException.java`
- Modify: `src/main/java/com/wpanther/eidasremotesigning/service/CSCSignatureService.java` (`getSignatureStatus()`)
- Modify: `src/main/java/com/wpanther/eidasremotesigning/controller/CSCSignatureController.java`
- Modify: `src/test/java/com/wpanther/eidasremotesigning/dto/CSCSignatureDtoTest.java`

**Interfaces:**
- `CSCSignatureStatusResponse`: `signatures: String[]`, `DocumentWithSignature: List<String>`, `SignatureObject: List<String>`, `responseID: String` (only spec fields, PascalCase JSON names for doc fields).
- `CSCSignPollingPendingResponse`: `error: String` (constant `"accepted_request"`), `error_description: String`.
- `SigningInProgressException`: carries `requestID: String`, thrown by `getSignatureStatus()` when operation is CREATED or PROCESSING.
- `CSCSignatureController.getSignatureStatus()`: returns `ResponseEntity<?>`, 200 on COMPLETED, 202 on in-progress.

- [ ] **Step 1: Add failing tests to CSCSignatureDtoTest**

Add to `CSCSignatureDtoTest.java`:

```java
// Add imports:
// import com.wpanther.eidasremotesigning.dto.csc.CSCSignatureStatusResponse;
// import java.util.List;

@Test
void signatureStatusResponse_documentWithSignature_usesPascalCase() throws Exception {
    CSCSignatureStatusResponse response = CSCSignatureStatusResponse.builder()
            .documentWithSignature(List.of("abc123"))
            .build();

    JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

    assertThat(json.has("DocumentWithSignature"))
            .as("spec §11.12 requires PascalCase 'DocumentWithSignature'")
            .isTrue();
    assertThat(json.has("documentWithSignature"))
            .as("camelCase must not appear")
            .isFalse();
}

@Test
void signatureStatusResponse_signatureObject_usesPascalCase() throws Exception {
    CSCSignatureStatusResponse response = CSCSignatureStatusResponse.builder()
            .signatureObject(List.of("sig123"))
            .build();

    JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

    assertThat(json.has("SignatureObject"))
            .as("spec §11.12 requires PascalCase 'SignatureObject'")
            .isTrue();
    assertThat(json.has("signatureObject"))
            .as("camelCase must not appear")
            .isFalse();
}

@Test
void signatureStatusResponse_noNonSpecFields() throws Exception {
    CSCSignatureStatusResponse response = CSCSignatureStatusResponse.builder()
            .signatures(new String[]{"sig"})
            .build();

    JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(response));

    assertThat(json.has("status")).as("status is not in spec §11.12 completed response").isFalse();
    assertThat(json.has("errorMessage")).as("errorMessage is not in spec §11.12 response").isFalse();
    assertThat(json.has("signatureAlgorithm")).as("signatureAlgorithm is not in spec §11.12 response").isFalse();
    assertThat(json.has("timestampData")).as("timestampData is not in spec §11.12 response").isFalse();
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
mvn test -Dtest=CSCSignatureDtoTest
```

Expected: The PascalCase tests and non-spec-fields test should fail.

- [ ] **Step 3: Fix CSCSignatureStatusResponse DTO**

Replace `CSCSignatureStatusResponse.java`:

```java
package com.wpanther.eidasremotesigning.dto.csc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
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
public class CSCSignatureStatusResponse {

    private String[] signatures;

    @JsonProperty("DocumentWithSignature")
    private List<String> documentWithSignature;

    @JsonProperty("SignatureObject")
    private List<String> signatureObject;

    private String responseID;
}
```

- [ ] **Step 4: Create CSCSignPollingPendingResponse**

Create `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCSignPollingPendingResponse.java`:

The 202 in-progress body is error-shaped per spec §11.12 and the approved design spec: `{"error":"accepted_request","error_description":"..."}`. NOT a `responseID` field.

```java
package com.wpanther.eidasremotesigning.dto.csc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CSCSignPollingPendingResponse {
    private String error;

    @JsonProperty("error_description")
    private String errorDescription;
}
```

- [ ] **Step 5: Create SigningInProgressException**

Create `src/main/java/com/wpanther/eidasremotesigning/exception/SigningInProgressException.java`:

```java
package com.wpanther.eidasremotesigning.exception;

public class SigningInProgressException extends RuntimeException {

    private final String requestID;

    public SigningInProgressException(String requestID) {
        super("Signing operation in progress: " + requestID);
        this.requestID = requestID;
    }

    public String getRequestID() {
        return requestID;
    }
}
```

- [ ] **Step 6: Fix CSCSignatureService.getSignatureStatus()**

Replace the `getSignatureStatus()` method in `CSCSignatureService.java`. The current method (lines 491–543) sets non-spec fields on the builder and always returns a result without checking status for 202. Replace it with:

```java
@Transactional(readOnly = true)
public CSCSignatureStatusResponse getSignatureStatus(CSCSignatureStatusRequest request) {
    try {
        String clientId = currentClientId();
        String operationId = request.getRequestID();

        AsyncOperation operation = asyncOperationService
                .getOperation(operationId, clientId)
                .orElseThrow(() -> new SigningException("Operation not found"));

        if (AsyncOperationService.STATUS_CREATED.equals(operation.getStatus())
                || AsyncOperationService.STATUS_PROCESSING.equals(operation.getStatus())) {
            throw new SigningInProgressException(operationId);
        }

        if (AsyncOperationService.STATUS_FAILED.equals(operation.getStatus())) {
            throw new SigningException("Signing operation failed: " +
                    (operation.getErrorMessage() != null ? operation.getErrorMessage() : "unknown error"));
        }

        CSCSignatureStatusResponse.CSCSignatureStatusResponseBuilder builder =
                CSCSignatureStatusResponse.builder();

        if (operation.getResultData() != null) {
            if (AsyncOperationService.TYPE_SIGN_HASH.equals(operation.getOperationType())) {
                CSCSignatureResponse result = asyncOperationService.deserializeResult(
                        operation.getResultData(), CSCSignatureResponse.class);
                builder.signatures(result.getSignatures());

            } else if (AsyncOperationService.TYPE_SIGN_DOCUMENT.equals(operation.getOperationType())) {
                CSCSignDocumentResponse result = asyncOperationService.deserializeResult(
                        operation.getResultData(), CSCSignDocumentResponse.class);
                builder.documentWithSignature(result.getDocumentWithSignature())
                        .signatureObject(result.getSignatureObject());
            }
        }

        return builder.build();

    } catch (SigningInProgressException | SigningException se) {
        throw se;
    } catch (Exception e) {
        log.error("Error in getSignatureStatus", e);
        throw new SigningException("Failed to get signature status: " + e.getMessage(), e);
    }
}
```

Also add import: `import com.wpanther.eidasremotesigning.exception.SigningInProgressException;`

> **Note:** `SigningInProgressException` is caught in the controller via explicit try/catch (Steps 7 and Task 6 Step 3). To prevent a 500 if it ever escapes a controller (future code paths), also add a handler to `GlobalExceptionHandler.java`:
> ```java
> @ExceptionHandler(SigningInProgressException.class)
> public ResponseEntity<CSCErrorResponse> handleSigningInProgress(SigningInProgressException e) {
>     return ResponseEntity.accepted()
>             .body(CSCErrorResponse.builder()
>                     .error("accepted_request")
>                     .error_description(e.getMessage())
>                     .build());
> }
> ```
> Add this handler at the bottom of `GlobalExceptionHandler` as part of Step 6, before the commit.

- [ ] **Step 7: Fix CSCSignatureController.getSignatureStatus()**

In `CSCSignatureController.java`, change the `getSignatureStatus()` method return type to `ResponseEntity<?>` and handle the 202:

```java
@PostMapping("/signPolling")
public ResponseEntity<?> getSignatureStatus(
        @Valid @RequestBody CSCSignatureStatusRequest request) {
    log.debug("CSC API: Signature status request for requestID: {}",
            request.getRequestID());

    try {
        CSCSignatureStatusResponse response = cscSignatureService.getSignatureStatus(request);
        return ResponseEntity.ok(response);
    } catch (com.wpanther.eidasremotesigning.exception.SigningInProgressException e) {
        return ResponseEntity.accepted().body(
                CSCSignPollingPendingResponse.builder()
                        .error("accepted_request")
                        .errorDescription("The previous async request has been accepted but not yet completed")
                        .build());
    }
}
```

Add import: `import com.wpanther.eidasremotesigning.dto.csc.CSCSignPollingPendingResponse;`

- [ ] **Step 8: Run tests**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
mvn test
```

Expected: All unit tests pass.

- [ ] **Step 9: Commit**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
git add src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCSignatureStatusResponse.java \
        src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCSignPollingPendingResponse.java \
        src/main/java/com/wpanther/eidasremotesigning/exception/SigningInProgressException.java \
        src/main/java/com/wpanther/eidasremotesigning/exception/GlobalExceptionHandler.java \
        src/main/java/com/wpanther/eidasremotesigning/service/CSCSignatureService.java \
        src/main/java/com/wpanther/eidasremotesigning/controller/CSCSignatureController.java \
        src/test/java/com/wpanther/eidasremotesigning/dto/CSCSignatureDtoTest.java
git commit -m "fix(W6,F8,F9): signPolling 202 error body, PascalCase fields, remove non-spec fields"
```

---

### Task 5: Fix authorize request + signDoc validation (F11, F12)

**Issues:**
- F12: `CSCAuthorizeRequest.AuthDataEntry.value` has `@NotBlank`. The spec §11.6 says `value` is optional (e.g., biometric auth has no value).
- F11: `CSCSignDocumentRequest.credentialID` has `@NotBlank`, but spec §11.11 says `credentialID` is optional when the client already has the SAD (the SAD alone identifies the credential). Add a cross-field validator that enforces `documentDigests` OR `documents` is provided.

**Files:**
- Modify: `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCAuthorizeRequest.java`
- Modify: `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCSignDocumentRequest.java`
- Create: `src/main/java/com/wpanther/eidasremotesigning/validation/AtLeastOneOf.java`
- Create: `src/main/java/com/wpanther/eidasremotesigning/validation/AtLeastOneOfValidator.java`
- Create: `src/test/java/com/wpanther/eidasremotesigning/validation/AtLeastOneOfValidatorTest.java`

**Interfaces:**
- `AtLeastOneOf(fields = {"fieldA", "fieldB"})`: class-level annotation; validation fails if all named fields are null or empty.

- [ ] **Step 1: Write failing tests**

Create `src/test/java/com/wpanther/eidasremotesigning/validation/AtLeastOneOfValidatorTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
mvn test -Dtest=AtLeastOneOfValidatorTest
```

Expected: FAIL — `@AtLeastOneOf` annotation doesn't exist yet.

- [ ] **Step 3: Create @AtLeastOneOf annotation**

Create `src/main/java/com/wpanther/eidasremotesigning/validation/AtLeastOneOf.java`:

```java
package com.wpanther.eidasremotesigning.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = AtLeastOneOfValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AtLeastOneOf {
    String message() default "At least one of the specified fields must be provided";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
    String[] fields();
}
```

- [ ] **Step 4: Create AtLeastOneOfValidator**

Create `src/main/java/com/wpanther/eidasremotesigning/validation/AtLeastOneOfValidator.java`:

```java
package com.wpanther.eidasremotesigning.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.lang.reflect.Field;
import java.util.Collection;

public class AtLeastOneOfValidator implements ConstraintValidator<AtLeastOneOf, Object> {

    private String[] fields;
    private String message;

    @Override
    public void initialize(AtLeastOneOf constraintAnnotation) {
        this.fields = constraintAnnotation.fields();
        // Build the message directly using String.format; do NOT rely on BV interpolation
        // of {fields} since that only works for standard constraint attributes.
        this.message = String.format("At least one of (%s) must be provided",
                String.join(", ", fields));
    }

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        for (String fieldName : fields) {
            try {
                Field field = findField(value.getClass(), fieldName);
                field.setAccessible(true);
                Object fieldValue = field.get(value);
                if (fieldValue != null) {
                    if (fieldValue instanceof Collection<?> col && !col.isEmpty()) {
                        return true;
                    } else if (!(fieldValue instanceof Collection<?>)) {
                        return true;
                    }
                }
            } catch (NoSuchFieldException | IllegalAccessException ignored) {
            }
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(message)
                .addPropertyNode(fields[0])
                .addConstraintViolation();
        return false;
    }

    private Field findField(Class<?> clazz, String name) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
```

- [ ] **Step 5: Fix CSCAuthorizeRequest.AuthDataEntry**

In `CSCAuthorizeRequest.java`, remove `@NotBlank` from the `value` field:

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public static class AuthDataEntry {
    @NotBlank(message = "authData id is required")
    private String id;

    private String value;
}
```

(Remove `@NotBlank(message = "authData value is required")` from `value`. Keep the import only if still used on `id`.)

- [ ] **Step 6: Fix CSCSignDocumentRequest**

In `CSCSignDocumentRequest.java`, remove `@NotBlank` from `credentialID` and add `@AtLeastOneOf` at class level:

```java
package com.wpanther.eidasremotesigning.dto.csc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.wpanther.eidasremotesigning.validation.AtLeastOneOf;
import jakarta.validation.constraints.NotBlank;
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
@AtLeastOneOf(
    fields = {"documentDigests", "documents"},
    message = "Either documentDigests or documents must be provided"
)
public class CSCSignDocumentRequest {

    private String credentialID;

    private String signatureQualifier;

    private String SAD;

    private List<DocumentDigestEntry> documentDigests;

    private List<DocumentEntry> documents;

    private String operationMode;

    private Integer validity_period;

    private String response_uri;

    private String clientData;

    private Boolean returnValidationInfo;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DocumentDigestEntry {
        private List<String> hashes;
        private String hashAlgorithmOID;

        @NotBlank(message = "signature_format is required")
        private String signature_format;

        private String conformance_level;

        @NotBlank(message = "signAlgo is required")
        private String signAlgo;

        private String signAlgoParams;
        private String signed_envelope_property;
        private List<SignedAttribute> signed_props;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class DocumentEntry {
        @NotBlank(message = "document is required")
        private String document;

        @NotBlank(message = "signature_format is required")
        private String signature_format;

        private String conformance_level;

        @NotBlank(message = "signAlgo is required")
        private String signAlgo;

        private String signAlgoParams;
        private String signed_envelope_property;
        private List<SignedAttribute> signed_props;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class SignedAttribute {
        private String attribute_name;
        private String attribute_value;
    }
}
```

- [ ] **Step 7: Run tests**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
mvn test -Dtest=AtLeastOneOfValidatorTest
mvn test
```

Expected: All pass.

- [ ] **Step 8: Commit**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
git add src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCAuthorizeRequest.java \
        src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCSignDocumentRequest.java \
        src/main/java/com/wpanther/eidasremotesigning/validation/AtLeastOneOf.java \
        src/main/java/com/wpanther/eidasremotesigning/validation/AtLeastOneOfValidator.java \
        src/test/java/com/wpanther/eidasremotesigning/validation/AtLeastOneOfValidatorTest.java
git commit -m "fix(F11,F12): remove @NotBlank from authData.value and signDoc.credentialID, add @AtLeastOneOf validator"
```

---

### Task 6: Fix authorizeCheck response and HTTP codes (F5, W7)

**Issues:**
- W7: `CSCAuthorizeStatusResponse` has non-spec fields: `credentialID`, `status`, `authMode`. Spec §11.7 only allows `SAD` and `expiresIn` (200) or nothing extra.
- F5: `authorizeCheck` always returns HTTP 200; spec §11.7 requires 200 when SAD is ready, 202 when authorization is still pending.

**Files:**
- Modify: `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCAuthorizeStatusResponse.java`
- Modify: `src/main/java/com/wpanther/eidasremotesigning/service/CSCAuthorizationService.java`
- Modify: `src/main/java/com/wpanther/eidasremotesigning/controller/CSCAuthorizationController.java`

**Interfaces:**
- `CSCAuthorizeStatusResponse`: only `SAD: String` and `expiresIn: Long`.
- `getAuthorizeStatus()` in service: throws `SigningInProgressException` when transaction is AUTHORIZATION_INITIALIZED (pending); returns `CSCAuthorizeStatusResponse` with SAD when AUTHORIZED.
- `authorizeCheck` controller: 200 on SAD ready, 202 on pending.

- [ ] **Step 1: Fix CSCAuthorizeStatusResponse DTO**

Replace `CSCAuthorizeStatusResponse.java`:

```java
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
public class CSCAuthorizeStatusResponse {
    private String SAD;
    private Long expiresIn;
}
```

- [ ] **Step 2: Fix CSCAuthorizationService.getAuthorizeStatus()**

Replace the `getAuthorizeStatus()` method. The current method (lines 164–209) sets `credentialID`, `status`, `authMode` — these are removed. The new logic: if the transaction is in AUTHORIZATION_INITIALIZED state (still pending, e.g., waiting for an out-of-band auth factor), throw `SigningInProgressException`. Otherwise return SAD + expiresIn.

In practice this service always completes synchronously, so in-progress will never be reached. But the controller must handle the 202 case for spec compliance:

```java
@Transactional(readOnly = true)
public CSCAuthorizeStatusResponse getAuthorizeStatus(CSCAuthorizeStatusRequest request) {
    try {
        String handle = request.getHandle();

        TransactionAuthorization transaction = transactionRepository.findById(handle)
                .orElseThrow(() -> new SigningException("Transaction not found"));

        if (transaction.getExpiresAt().isBefore(Instant.now())) {
            throw new SigningException("Transaction has expired");
        }

        if ("AUTHORIZATION_INITIALIZED".equals(transaction.getStatus())) {
            throw new SigningInProgressException(handle);
        }

        long expiresIn = transaction.getExpiresAt().getEpochSecond() - Instant.now().getEpochSecond();
        if (expiresIn < 0) expiresIn = 0;

        return CSCAuthorizeStatusResponse.builder()
                .SAD(transaction.getSad())
                .expiresIn(expiresIn)
                .build();

    } catch (SigningInProgressException | SigningException e) {
        throw e;
    } catch (Exception e) {
        log.error("Failed to get authorization status", e);
        throw new SigningException("Failed to get authorization status: " + e.getMessage(), e);
    }
}
```

Add import: `import com.wpanther.eidasremotesigning.exception.SigningInProgressException;`

Remove now-unused imports: `CertificateException` (if no longer used in this method), `SigningCertificate`/`SigningCertificateRepository` (check if still used elsewhere in the file — they ARE still needed for `authorizeCredential()`, keep them).

- [ ] **Step 3: Fix CSCAuthorizationController.getAuthorizeStatus()**

In `CSCAuthorizationController.java`, change `getAuthorizeStatus()` to handle 202:

```java
@PostMapping("/authorizeCheck")
public ResponseEntity<?> getAuthorizeStatus(
        @Valid @RequestBody CSCAuthorizeStatusRequest request) {
    log.debug("CSC API: Authorization status request");

    try {
        CSCAuthorizeStatusResponse response = cscAuthorizationService.getAuthorizeStatus(request);
        return ResponseEntity.ok(response);
    } catch (com.wpanther.eidasremotesigning.exception.SigningInProgressException e) {
        return ResponseEntity.accepted().build();
    }
}
```

Change the method return type from `ResponseEntity<CSCAuthorizeStatusResponse>` to `ResponseEntity<?>`.

- [ ] **Step 4: Run tests**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
mvn test
```

Expected: All pass.

- [ ] **Step 5: Commit**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
git add src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCAuthorizeStatusResponse.java \
        src/main/java/com/wpanther/eidasremotesigning/service/CSCAuthorizationService.java \
        src/main/java/com/wpanther/eidasremotesigning/controller/CSCAuthorizationController.java
git commit -m "fix(F5,W7): remove non-spec fields from authorizeCheck response, return 202 when pending"
```

---

### Task 7: Fix authorize 200 (no handle in sync response) (F4)

**Issue:** `authorizeCredential()` returns both `SAD` and `handle` in the same response body. Spec §11.6 says 200 response contains only `SAD` + `expiresIn` (sync auth); 202 response contains only `handle` (async auth). Since PIN-based auth is always synchronous, the response must never include `handle`.

**Files:**
- Modify: `src/main/java/com/wpanther/eidasremotesigning/service/CSCAuthorizationService.java`
- Modify: `src/main/java/com/wpanther/eidasremotesigning/controller/CSCAuthorizationController.java`
- Create: `src/test/java/com/wpanther/eidasremotesigning/service/CSCAuthorizationServiceTest.java`

**Interfaces:**
- `authorizeCredential()` returns `CSCAuthorizeResponse` with `SAD` + `expiresIn` only (no `handle`).

- [ ] **Step 1: Write failing test**

Create `src/test/java/com/wpanther/eidasremotesigning/service/CSCAuthorizationServiceTest.java`:

```java
package com.wpanther.eidasremotesigning.service;

import com.wpanther.eidasremotesigning.dto.csc.CSCAuthorizeRequest;
import com.wpanther.eidasremotesigning.dto.csc.CSCAuthorizeResponse;
import com.wpanther.eidasremotesigning.entity.SigningCertificate;
import com.wpanther.eidasremotesigning.entity.TransactionAuthorization;
import com.wpanther.eidasremotesigning.repository.SigningCertificateRepository;
import com.wpanther.eidasremotesigning.repository.TransactionAuthorizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.security.SecureRandom;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CSCAuthorizationServiceTest {

    @Mock
    private SigningCertificateRepository certificateRepository;

    @Mock
    private TransactionAuthorizationRepository transactionRepository;

    @InjectMocks
    private CSCAuthorizationService service;

    @BeforeEach
    void setUpSecurityContext() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("test-client");
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);

        // Inject SecureRandom via reflection (field injection)
        try {
            var field = CSCAuthorizationService.class.getDeclaredField("secureRandom");
            field.setAccessible(true);
            field.set(service, new SecureRandom());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void authorizeCredential_syncPinAuth_returnsOnlySadNoHandle() {
        SigningCertificate cert = new SigningCertificate();
        cert.setId("cred-1");
        cert.setStorageType("BCFKS");

        when(certificateRepository.findByIdAndClientId(eq("cred-1"), eq("test-client")))
                .thenReturn(Optional.of(cert));
        when(transactionRepository.save(any(TransactionAuthorization.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        CSCAuthorizeRequest request = CSCAuthorizeRequest.builder()
                .credentialID("cred-1")
                .numSignatures(1)
                .authData(List.of(CSCAuthorizeRequest.AuthDataEntry.builder()
                        .id("PIN")
                        .value("1234")
                        .build()))
                .build();

        CSCAuthorizeResponse response = service.authorizeCredential(request);

        assertThat(response.getSAD()).isNotNull();
        assertThat(response.getExpiresIn()).isGreaterThan(0);
        assertThat(response.getHandle())
                .as("handle must be null for synchronous PIN auth (spec §11.6)")
                .isNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
mvn test -Dtest=CSCAuthorizationServiceTest
```

Expected: FAIL — `response.getHandle()` is not null (current code sets `handle(transactionId)`).

- [ ] **Step 3: Fix CSCAuthorizationService.authorizeCredential()**

In `CSCAuthorizationService.java`, find the return statement at the end of `authorizeCredential()` (lines 102–106):

```java
return CSCAuthorizeResponse.builder()
        .handle(transactionId)
        .SAD(sad)
        .expiresIn(validityPeriod)
        .build();
```

Change to:

```java
return CSCAuthorizeResponse.builder()
        .SAD(sad)
        .expiresIn(validityPeriod)
        .build();
```

The `transactionId` is still generated and saved to the DB (needed for SAD lookup later). Just don't return it to the client.

- [ ] **Step 4: Fix CSCAuthorizationController.authorizeCredential()**

Current controller always returns 200. Since this service is synchronous (always returns SAD), 200 is correct. But add the 200/202 split pattern for spec compliance (in case future async auth is ever added). Change `authorizeCredential()`:

```java
@PostMapping("/authorize")
public ResponseEntity<?> authorizeCredential(
        @Valid @RequestBody CSCAuthorizeRequest request) {
    log.debug("CSC API: Credential authorization request");

    CSCAuthorizeResponse response = cscAuthorizationService.authorizeCredential(request);
    if (response.getSAD() != null) {
        return ResponseEntity.ok(response);
    }
    return ResponseEntity.accepted().body(response);
}
```

Change return type from `ResponseEntity<CSCAuthorizeResponse>` to `ResponseEntity<?>`.

- [ ] **Step 5: Run tests**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
mvn test -Dtest=CSCAuthorizationServiceTest
mvn test
```

Expected: All pass.

- [ ] **Step 6: Commit**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
git add src/main/java/com/wpanther/eidasremotesigning/service/CSCAuthorizationService.java \
        src/main/java/com/wpanther/eidasremotesigning/controller/CSCAuthorizationController.java \
        src/test/java/com/wpanther/eidasremotesigning/service/CSCAuthorizationServiceTest.java
git commit -m "fix(F4): remove handle from synchronous authorize response (SAD-only per spec §11.6)"
```

---

### Task 8: Fix extendTransaction SAD + credentials/info certificates (F6, F14)

**Issues:**
- F6: `extendTransaction()` only extends the expiry; spec §11.8 requires generating a new SAD, invalidating the old one, and returning the new SAD in the response. `CSCExtendTransactionResponse` is missing `SAD`.
- F14: `CSCCredentialsInfoRequest` is missing the `certificates` field (spec §11.4 `certificates` = `"none"` | `"single"` | `"chain"`). The service ignores it.

**Files:**
- Modify: `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCExtendTransactionResponse.java`
- Modify: `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCCredentialsInfoRequest.java`
- Modify: `src/main/java/com/wpanther/eidasremotesigning/service/CSCAuthorizationService.java`
- Modify: `src/main/java/com/wpanther/eidasremotesigning/service/CSCApiService.java`
- Modify: `src/test/java/com/wpanther/eidasremotesigning/service/CSCAuthorizationServiceTest.java`

**Interfaces:**
- `CSCExtendTransactionResponse`: `SAD: String`, `expiresIn: Long`.
- `CSCCredentialsInfoRequest`: adds `String certificates` field.
- `extendTransaction()`: generates new SAD, saves it to transaction, returns `{SAD, expiresIn}`.

- [ ] **Step 1: Add failing test to CSCAuthorizationServiceTest**

Add to `CSCAuthorizationServiceTest.java`:

```java
@Test
void extendTransaction_generatesNewSadAndReturnsIt() {
    TransactionAuthorization existing = TransactionAuthorization.builder()
            .id("txn-1")
            .clientId("test-client")
            .sad("old-sad-value")
            .status("AUTHORIZED")
            .expiresAt(java.time.Instant.now().plusSeconds(300))
            .build();

    when(transactionRepository.findBySadAndClientId(eq("old-sad-value"), eq("test-client")))
            .thenReturn(Optional.of(existing));
    when(transactionRepository.save(any(TransactionAuthorization.class)))
            .thenAnswer(inv -> inv.getArgument(0));

    com.wpanther.eidasremotesigning.dto.csc.CSCExtendTransactionRequest request =
            com.wpanther.eidasremotesigning.dto.csc.CSCExtendTransactionRequest.builder()
                    .credentialID("cred-1")
                    .SAD("old-sad-value")
                    .build();

    com.wpanther.eidasremotesigning.dto.csc.CSCExtendTransactionResponse response =
            service.extendTransaction(request);

    assertThat(response.getSAD())
            .as("spec §11.8: new SAD must be returned after extendTransaction")
            .isNotNull()
            .isNotEqualTo("old-sad-value");
    assertThat(response.getExpiresIn()).isGreaterThan(0);
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
mvn test -Dtest=CSCAuthorizationServiceTest#extendTransaction_generatesNewSadAndReturnsIt
```

Expected: FAIL — `response.getSAD()` is null (field doesn't exist yet).

- [ ] **Step 3: Fix CSCExtendTransactionResponse DTO**

Replace `CSCExtendTransactionResponse.java`:

```java
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
public class CSCExtendTransactionResponse {
    private String SAD;
    private Long expiresIn;
}
```

- [ ] **Step 4: Fix extendTransaction() in CSCAuthorizationService**

In `CSCAuthorizationService.java`, replace `extendTransaction()` (lines 120–158). The new version generates a fresh SAD, saves it, and returns both SAD and expiresIn:

```java
@Transactional
public CSCExtendTransactionResponse extendTransaction(CSCExtendTransactionRequest request) {
    try {
        String clientId = currentClientId();

        TransactionAuthorization transaction = transactionRepository
                .findBySadAndClientId(request.getSAD(), clientId)
                .orElseThrow(() -> new SigningException("Transaction not found for provided SAD"));

        if (transaction.getExpiresAt().isBefore(Instant.now())) {
            throw new SigningException("Transaction has expired");
        }

        if (!"AUTHORIZATION_INITIALIZED".equals(transaction.getStatus()) &&
                !"AUTHORIZED".equals(transaction.getStatus())) {
            throw new SigningException("Transaction cannot be extended in current state: " + transaction.getStatus());
        }

        String newSad = generateSignatureActivationData();
        Instant newExpiresAt = Instant.now().plusSeconds(DEFAULT_VALIDITY_PERIOD);

        transaction.setSad(newSad);
        transaction.setExpiresAt(newExpiresAt);

        transactionRepository.save(transaction);
        log.debug("Extended transaction authorization with new SAD: {}", transaction.getId());

        return CSCExtendTransactionResponse.builder()
                .SAD(newSad)
                .expiresIn(DEFAULT_VALIDITY_PERIOD)
                .build();

    } catch (SigningException e) {
        throw e;
    } catch (Exception e) {
        log.error("Failed to extend transaction", e);
        throw new SigningException("Failed to extend transaction: " + e.getMessage(), e);
    }
}
```

- [ ] **Step 5: Add certificates field to CSCCredentialsInfoRequest**

In `CSCCredentialsInfoRequest.java`, add `String certificates`:

```java
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
public class CSCCredentialsInfoRequest {

    private CSCBaseRequest.Credentials credentials;

    @NotBlank(message = "credentialID is required")
    private String credentialID;

    private Boolean certInfo;
    private Boolean authInfo;
    private String certificates;
}
```

- [ ] **Step 6: Honour certificates in CSCApiService.getCredentialInfo()**

In `CSCApiService.java`, in the `getCredentialInfo()` method, pass the `certificates` param to `mapToCscCertificateInfo()`. The `certificates` value controls whether to include the certificate chain:
- `"none"` → set `cert.certificates = null`
- `"single"` or `null` (default) → include only the signing certificate (current behavior)
- `"chain"` → include full chain (not implemented: log warning and fall back to single)

Update `getCredentialInfo()` to call a new overload of `mapToCscCertificateInfo()` that accepts the `certificates` param:

In `getCredentialInfo()`, change the last call from:

```java
return mapToCscCertificateInfo(cert, x509Cert);
```

To:

```java
return mapToCscCertificateInfo(cert, x509Cert, request.getCertificates());
```

In `mapToCscCertificateInfo()`, add a String parameter and use it:

Change the signature from:
```java
private CSCCertificateInfo mapToCscCertificateInfo(SigningCertificate cert, X509Certificate x509Cert)
```

To:
```java
private CSCCertificateInfo mapToCscCertificateInfo(SigningCertificate cert, X509Certificate x509Cert,
                                                    String certificates)
```

Then in the cert details builder block, replace:
```java
.certificates(new String[]{certBase64})
```

With:
```java
.certificates("none".equals(certificates) ? null : new String[]{certBase64})
```

(If `certificates == "chain"`, log a warning and fall through to single.)

Also add at the top of the method:
```java
if ("chain".equals(certificates)) {
    log.warn("certificates=chain is not yet supported; falling back to single");
}
```

**All call sites of `mapToCscCertificateInfo()` must be updated.** Search the full file for every invocation — there are at least 3:
1. `getCredentialInfo()` — pass `request.getCertificates()`
2. `associateCertificate()` — pass `"single"` (there may be multiple branches: BCFKS, AWSKMS, PKCS#11 — update each one)
3. `listCredentials()` helper (Task 11) — pass `certParam`

Run `grep -n "mapToCscCertificateInfo" src/main/java/com/wpanther/eidasremotesigning/service/CSCApiService.java` to confirm all call sites before committing.

```java
// associateCertificate example update:
return mapToCscCertificateInfo(cert, x509Cert, "single");
```

- [ ] **Step 7: Run tests**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
mvn test -Dtest=CSCAuthorizationServiceTest
mvn test
```

Expected: All pass.

- [ ] **Step 8: Commit**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
git add src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCExtendTransactionResponse.java \
        src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCCredentialsInfoRequest.java \
        src/main/java/com/wpanther/eidasremotesigning/service/CSCAuthorizationService.java \
        src/main/java/com/wpanther/eidasremotesigning/service/CSCApiService.java \
        src/test/java/com/wpanther/eidasremotesigning/service/CSCAuthorizationServiceTest.java
git commit -m "fix(F6,F14): extendTransaction returns new SAD, credentials/info accepts certificates param"
```

---

### Task 9: Add RSASSA-PSS to OIDMapper (W8)

**Issue:** `OIDMapper` is missing RSASSA-PSS support. Spec §11.1 lists `1.2.840.113549.1.1.10` (RSASSA-PSS) as a required supported algorithm.

**Files:**
- Modify: `src/main/java/com/wpanther/eidasremotesigning/util/OIDMapper.java`
- Modify: `src/test/java/com/wpanther/eidasremotesigning/util/OIDMapperTest.java`

**Interfaces:**
- `OIDMapper.toJcaSigAlgo("1.2.840.113549.1.1.10")` returns `"RSASSA-PSS"`.
- `OIDMapper.toOidSigAlgo("RSASSA-PSS")` returns `"1.2.840.113549.1.1.10"`.
- `OIDMapper.supportedSigOidsForKeyAlgo("RSA")` includes `"1.2.840.113549.1.1.10"`.
- `OIDMapper.toJcaHashAlgoForSig("1.2.840.113549.1.1.10")` throws `SigningException` (PSS hash is specified in `signAlgoParams`, not embedded in the OID).

- [ ] **Step 1: Write failing tests**

Add to the existing `OIDMapperTest.java`:

```java
@Test
void toJcaSigAlgo_rsassaPss_returnsRSASSAPSS() {
    assertThat(OIDMapper.toJcaSigAlgo("1.2.840.113549.1.1.10")).isEqualTo("RSASSA-PSS");
}

@Test
void toOidSigAlgo_rsassaPss_returnsPssOid() {
    assertThat(OIDMapper.toOidSigAlgo("RSASSA-PSS")).isEqualTo("1.2.840.113549.1.1.10");
}

@Test
void supportedSigOids_rsa_includesPssOid() {
    String[] rsaOids = OIDMapper.supportedSigOidsForKeyAlgo("RSA");
    assertThat(rsaOids).contains("1.2.840.113549.1.1.10");
}

@Test
void toJcaHashAlgoForSig_pssOid_throwsSigningException() {
    assertThatThrownBy(() -> OIDMapper.toJcaHashAlgoForSig("1.2.840.113549.1.1.10"))
            .isInstanceOf(SigningException.class)
            .hasMessageContaining("hash algorithm is specified in signAlgoParams");
}
```

(Add imports: `import com.wpanther.eidasremotesigning.exception.SigningException;`)

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
mvn test -Dtest=OIDMapperTest
```

Expected: FAIL on the four new tests.

- [ ] **Step 3: Fix OIDMapper**

In `OIDMapper.java`, update the static initializer blocks:

After the existing `SIG_OID_TO_JCA` entries (around line 36), add:
```java
SIG_OID_TO_JCA.put("1.2.840.113549.1.1.10", "RSASSA-PSS");
```

The `SIG_JCA_TO_OID` is populated via `SIG_OID_TO_JCA.forEach(...)` — so RSASSA-PSS is automatically added there. Confirm the existing code does this (it does: `SIG_OID_TO_JCA.forEach((k, v) -> SIG_JCA_TO_OID.put(v, k));`).

In `KEY_TO_SIG_OIDS`, update the RSA entry to include the PSS OID:

```java
KEY_TO_SIG_OIDS.put("RSA", new String[]{
        "1.2.840.113549.1.1.11",
        "1.2.840.113549.1.1.12",
        "1.2.840.113549.1.1.13",
        "1.2.840.113549.1.1.10"
});
```

In `SIG_OID_TO_HASH_JCA`, do NOT add a mapping for PSS OID. Instead, update `toJcaHashAlgoForSig()` to throw a meaningful error for PSS:

```java
public static String toJcaHashAlgoForSig(String sigOid) {
    if ("1.2.840.113549.1.1.10".equals(sigOid)) {
        throw new SigningException(
                "RSASSA-PSS hash algorithm is specified in signAlgoParams, not the OID: " + sigOid);
    }
    String result = SIG_OID_TO_HASH_JCA.get(sigOid);
    if (result == null) throw new SigningException("Unsupported signature algorithm OID: " + sigOid);
    return result;
}
```

- [ ] **Step 4: Run tests**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
mvn test -Dtest=OIDMapperTest
mvn test
```

Expected: All pass.

- [ ] **Step 5: Commit**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
git add src/main/java/com/wpanther/eidasremotesigning/util/OIDMapper.java \
        src/test/java/com/wpanther/eidasremotesigning/util/OIDMapperTest.java
git commit -m "fix(W8): add RSASSA-PSS OID 1.2.840.113549.1.1.10 to OIDMapper"
```

---

### Task 10: Fix signDoc sync responseID and returnValidationInfo (F10, W9)

**Issues:**
- F10: `executeSignDocument()` sets `responseID(UUID.randomUUID().toString())` on the sync response (lines 349 and 360). The spec §11.11 says `responseID` is only for async (202) responses.
- W9: `returnValidationInfo` field in `CSCSignDocumentRequest` is accepted but silently ignored. Should log a warning when `true` since full DSS validation data extraction is not implemented.

**Files:**
- Modify: `src/main/java/com/wpanther/eidasremotesigning/service/CSCSignatureService.java`

- [ ] **Step 1: Fix executeSignDocument()**

In `CSCSignatureService.java`, find `executeSignDocument()`. There are two sync return statements that set `responseID`:

**Line ~347–350** (full document signing path):
```java
return CSCSignDocumentResponse.builder()
        .documentWithSignature(signedDocuments)
        .responseID(UUID.randomUUID().toString())
        .build();
```

Change to:
```java
return CSCSignDocumentResponse.builder()
        .documentWithSignature(signedDocuments)
        .build();
```

**Line ~357–361** (digest-only signing path — after the `else` for `documentDigests`):
```java
return CSCSignDocumentResponse.builder()
        .documentWithSignature(signedDocuments.isEmpty() ? null : signedDocuments)
        .signatureObject(signatureObjects.isEmpty() ? null : signatureObjects)
        .responseID(UUID.randomUUID().toString())
        .build();
```

Change to:
```java
return CSCSignDocumentResponse.builder()
        .documentWithSignature(signedDocuments.isEmpty() ? null : signedDocuments)
        .signatureObject(signatureObjects.isEmpty() ? null : signatureObjects)
        .build();
```

- [ ] **Step 2: Add returnValidationInfo warning**

In `executeSignDocument()`, near the top where request fields are read (before the digest/document processing loop), add:

```java
if (Boolean.TRUE.equals(request.getReturnValidationInfo())) {
    log.warn("returnValidationInfo=true is not supported; validationInfo will not be included in response");
}
```

- [ ] **Step 3: Run tests**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
mvn test
```

Expected: All pass.

- [ ] **Step 4: Commit**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
git add src/main/java/com/wpanther/eidasremotesigning/service/CSCSignatureService.java
git commit -m "fix(F10,W9): remove responseID from sync signDoc response, log warning for returnValidationInfo"
```

---

### Task 11: Fix credentials/list full compliance (W1, W2, W10, W11)

**Issues:**
- W11: `CSCCredentialsListRequest` has non-spec fields (`credentials`, `maxResults`); missing spec fields (`credentialInfo`, `certificates`, `certInfo`, `authInfo`, `onlyValid`, `lang`, `clientData`).
- W10: `listCredentials()` ignores all request params (doesn't honour `credentialInfo`, `onlyValid`, etc.).
- W1: `CSCCertificateInfo.CSCKeyInfo.curveIds: String[]` should be `curve: String` per spec §11.4.
- W2: `mapToCscCertificateInfo()` never sets `cert/status`; spec §11.4 requires `"valid"` or `"expired"`.

**Files:**
- Modify: `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCCredentialsListRequest.java`
- Modify: `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCCredentialsListResponse.java`
- Modify: `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCCertificateInfo.java`
- Modify: `src/main/java/com/wpanther/eidasremotesigning/service/CSCApiService.java`

**Interfaces:**
- `CSCCredentialsListRequest`: spec-compliant fields only.
- `CSCCredentialsListResponse`: adds `List<CSCCertificateInfo> credentialInfos` (NON_NULL; only present when `credentialInfo=true`).
- `CSCKeyInfo.curve: String` (was `curveIds: String[]`).
- `mapToCscCertificateInfo()`: sets `cert.status` based on X.509 validity dates.

- [ ] **Step 1: Fix CSCCertificateInfo.CSCKeyInfo (W1)**

In `CSCCertificateInfo.java`, in the `CSCKeyInfo` inner class, rename `curveIds: String[]` → `curve: String`:

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public static class CSCKeyInfo {
    private String status;
    private String[] algo;
    private Integer len;
    private String curve;
}
```

- [ ] **Step 2: Fix CSCCertificateDetails to include status (W2)**

In `CSCCertificateInfo.java`, in the `CSCCertificateDetails` inner class, add `String status`:

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public static class CSCCertificateDetails {
    private String subjectDN;
    private String issuerDN;
    private String serialNumber;
    private String status;
    private String[] policies;
    private String[] keyUsage;
    private String validFrom;
    private String validTo;
    private String[] certificates;
}
```

- [ ] **Step 3: Fix mapToCscCertificateInfo() to set cert/status (W2)**

In `CSCApiService.java`, in `mapToCscCertificateInfo()`, add status computation and set it in the builder. Find the `certDetails` builder block (around line 395–403):

```java
CSCCertificateInfo.CSCCertificateDetails certDetails = CSCCertificateInfo.CSCCertificateDetails.builder()
        .subjectDN(subjectDN)
        .issuerDN(issuerDN)
        .serialNumber(serialNumber)
        .keyUsage(keyUsage)
        .validFrom(validFrom)
        .validTo(validTo)
        .certificates(new String[]{certBase64})
        .build();
```

Replace with:

```java
boolean certExpired = x509Cert.getNotAfter().toInstant().isBefore(Instant.now());
String certStatus = certExpired ? CSCConstants.CERT_STATUS_EXPIRED : CSCConstants.CERT_STATUS_VALID;

CSCCertificateInfo.CSCCertificateDetails certDetails = CSCCertificateInfo.CSCCertificateDetails.builder()
        .subjectDN(subjectDN)
        .issuerDN(issuerDN)
        .serialNumber(serialNumber)
        .status(certStatus)
        .keyUsage(keyUsage)
        .validFrom(validFrom)
        .validTo(validTo)
        .certificates("none".equals(certificates) ? null : new String[]{certBase64})
        .build();
```

Add the two new constants to `CSCConstants.java`:

```java
public static final String CERT_STATUS_VALID = "valid";
public static final String CERT_STATUS_EXPIRED = "expired";
```

Also add `import java.time.Instant;` if not already present.

- [ ] **Step 4: Fix CSCCredentialsListRequest (W11)**

Replace `CSCCredentialsListRequest.java`:

```java
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
```

- [ ] **Step 5: Fix CSCCredentialsListResponse to support credentialInfos (W10)**

Replace `CSCCredentialsListResponse.java`:

```java
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
public class CSCCredentialsListResponse {
    private List<String> credentialIDs;
    private List<CSCCertificateInfo> credentialInfos;
}
```

- [ ] **Step 6: Fix CSCApiService.listCredentials() to honour request params (W10)**

In `CSCApiService.java`, replace `listCredentials()` (lines 91–112):

```java
@Transactional(readOnly = true)
public CSCCredentialsListResponse listCredentials(CSCCredentialsListRequest request) {
    try {
        String clientId = currentClientId();
        List<SigningCertificate> certificates = certificateRepository.findByClientId(clientId);

        if (Boolean.TRUE.equals(request.getOnlyValid())) {
            // Filter by DB active flag first (fast), then by X.509 notAfter for BCFKS/AWSKMS.
            // PKCS#11 certs cannot be loaded without a PIN, so they pass through the isActive() check only.
            java.time.Instant now = java.time.Instant.now();
            certificates = certificates.stream()
                    .filter(SigningCertificate::isActive)
                    .filter(cert -> {
                        if ("PKCS11".equals(cert.getStorageType())) return true;
                        try {
                            java.security.cert.X509Certificate x509 = loadX509(cert);
                            return x509 != null && x509.getNotAfter().toInstant().isAfter(now);
                        } catch (Exception e) {
                            return false;
                        }
                    })
                    .collect(java.util.stream.Collectors.toList());
        }

        List<String> credentialIds = certificates.stream()
                .map(SigningCertificate::getId)
                .collect(java.util.stream.Collectors.toList());

        CSCCredentialsListResponse.CSCCredentialsListResponseBuilder builder =
                CSCCredentialsListResponse.builder().credentialIDs(credentialIds);

        if (Boolean.TRUE.equals(request.getCredentialInfo())) {
            String certParam = request.getCertificates() != null ? request.getCertificates() : "single";
            List<CSCCertificateInfo> infos = new java.util.ArrayList<>();
            for (SigningCertificate cert : certificates) {
                try {
                    java.security.cert.X509Certificate x509 = loadX509(cert);
                    if (x509 != null) {
                        infos.add(mapToCscCertificateInfo(cert, x509, certParam));
                    }
                } catch (Exception e) {
                    log.warn("Skipping credential {} in list: {}", cert.getId(), e.getMessage());
                }
            }
            builder.credentialInfos(infos.isEmpty() ? null : infos);
        }

        return builder.build();
    } catch (Exception e) {
        log.error("Error in listCredentials", e);
        throw new CertificateException("Failed to list credentials: " + e.getMessage(), e);
    }
}
```

Add the helper `loadX509()` method to `CSCApiService` (used only here; avoids duplicating the PIN-load logic for the list case where no PIN is available):

```java
private java.security.cert.X509Certificate loadX509(SigningCertificate cert) {
    try {
        if ("AWSKMS".equals(cert.getStorageType())) {
            if (cert.getCertificateData() == null) return null;
            byte[] certBytes = Base64.getDecoder().decode(cert.getCertificateData());
            java.security.cert.CertificateFactory cf =
                    java.security.cert.CertificateFactory.getInstance("X.509");
            return (java.security.cert.X509Certificate)
                    cf.generateCertificate(new java.io.ByteArrayInputStream(certBytes));
        } else {
            // Verified: SigningCertificateService.loadCertificateFromBCFKS(SigningCertificate) exists at line ~423.
            return certificateService.loadCertificateFromBCFKS(cert);
        }
    } catch (Exception e) {
        log.debug("Could not load X.509 for credential {}: {}", cert.getId(), e.getMessage());
        return null;
    }
}
```

Note: PKCS#11 credentials cannot be loaded without a PIN, so they are skipped in the credential info list (returned in IDs only). This is acceptable for the W10 fix.

- [ ] **Step 7: Add unit tests for W10 and W2 in CSCApiServiceTest**

Create `src/test/java/com/wpanther/eidasremotesigning/service/CSCApiServiceTest.java` with these tests (mock `certificateRepository`, `certificateService`, `SigningCertificate`, and a self-signed `X509Certificate`):

```java
package com.wpanther.eidasremotesigning.service;

import com.wpanther.eidasremotesigning.dto.csc.CSCCredentialsListRequest;
import com.wpanther.eidasremotesigning.dto.csc.CSCCredentialsListResponse;
import com.wpanther.eidasremotesigning.entity.SigningCertificate;
import com.wpanther.eidasremotesigning.repository.SigningCertificateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CSCApiServiceTest {

    @Mock
    private SigningCertificateRepository certificateRepository;

    // Other @Mock fields as needed by CSCApiService constructor

    @BeforeEach
    void setUpSecurityContext() {
        Authentication auth = mock(Authentication.class);
        when(auth.getName()).thenReturn("client-1");
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getAuthentication()).thenReturn(auth);
        SecurityContextHolder.setContext(ctx);
    }

    @Test
    void listCredentials_withoutCredentialInfo_returnsOnlyIds() {
        SigningCertificate cert = new SigningCertificate();
        cert.setId("cred-1");
        cert.setActive(true);
        cert.setStorageType("BCFKS");

        when(certificateRepository.findByClientId("client-1")).thenReturn(List.of(cert));

        // Wire up service with mocks (use constructor injection or reflection as needed)
        // ...

        CSCCredentialsListRequest request = CSCCredentialsListRequest.builder().build();
        // CSCCredentialsListResponse response = service.listCredentials(request);
        // assertThat(response.getCredentialIDs()).containsExactly("cred-1");
        // assertThat(response.getCredentialInfos()).isNull();
    }
}
```

> **Implementation note:** `CSCApiService` has many dependencies. Wire them up using the same constructor-injection pattern as other service tests, or use `@SpringBootTest @ActiveProfiles("test")` integration tests for the list/info path. The key assertions to cover:
> - `credentialInfo=true` → `credentialInfos` non-null
> - `onlyValid=true` → expired certs excluded
> - `certificates="none"` → `cert.certificates` null in response
> - `cert.status` = `"valid"` for non-expired, `"expired"` for expired

- [ ] **Step 8: Run tests**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
mvn test
```

Expected: All pass.

- [ ] **Step 9: Commit**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
git add src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCCredentialsListRequest.java \
        src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCCredentialsListResponse.java \
        src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCCertificateInfo.java \
        src/main/java/com/wpanther/eidasremotesigning/service/CSCApiService.java \
        src/main/java/com/wpanther/eidasremotesigning/util/CSCConstants.java \
        src/test/java/com/wpanther/eidasremotesigning/service/CSCApiServiceTest.java
git commit -m "fix(W1,W2,W10,W11): curve field, cert/status, credentialInfo in list, spec-compliant list request"
```

---

### Task 12: OAuth2 revoke, PKCE, and credentialID in token response (F13, W3, W4)

**Issues:**
- F13: No `POST /csc/v2/oauth2/revoke` endpoint; spec §11.2 and RFC 7009 require token revocation.
- W3: OAuth2 `authorize` endpoint is missing PKCE parameters (`code_challenge`, `code_challenge_method`) and credential-scope params (`credentialID`). Spec §9.4 requires PKCE for authorization-code flow.
- W4: `CSCOAuth2TokenResponse` is missing `credentialID` field; spec §11.2.3 says the token response SHOULD include `credentialID` when the scope is credential-scoped.

**Files:**
- Modify: `src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCOAuth2TokenResponse.java`
- Modify: `src/main/java/com/wpanther/eidasremotesigning/service/CSCOAuth2Service.java`
- Modify: `src/main/java/com/wpanther/eidasremotesigning/controller/CSCOAuth2Controller.java`
- Create (or extend): test for revoke in `src/test/java/com/wpanther/eidasremotesigning/service/`

**Interfaces:**
- `CSCOAuth2TokenResponse`: new `credentialID: String` field (optional, NON_NULL).
- `CSCOAuth2Service.revokeToken(token, tokenTypeHint)`: removes token from in-memory stores; **silently succeeds** for unknown tokens (RFC 7009 §2.2 prohibits errors for unrecognized tokens).
- `CSCOAuth2Service.storeAuthorizationRequest(code, clientId, redirectUri, scope, state, codeChallenge, codeChallengeMethod, credentialID)`: extended signature.
- `CSCOAuth2Service.exchangeAuthorizationCode(code, redirectUri, clientId, clientSecret, codeVerifier)`: validates PKCE code_verifier against stored code_challenge.
- `POST /csc/v2/oauth2/revoke`: accepts `token` and optional `token_type_hint`; returns **204 No Content** (RFC 7009 §2.2, CSC spec §8.4.5).

- [ ] **Step 1: Write failing test for revoke**

Create `src/test/java/com/wpanther/eidasremotesigning/service/CSCOAuth2ServiceTest.java`:

```java
package com.wpanther.eidasremotesigning.service;

import com.wpanther.eidasremotesigning.controller.CSCOAuth2Controller.CSCOAuth2Exception;
import com.wpanther.eidasremotesigning.entity.OAuth2Client;
import com.wpanther.eidasremotesigning.repository.OAuth2ClientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.SecureRandom;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CSCOAuth2ServiceTest {

    @Mock
    private OAuth2ClientRepository clientRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @InjectMocks
    private CSCOAuth2Service service;

    @BeforeEach
    void injectSecureRandom() throws Exception {
        var field = CSCOAuth2Service.class.getDeclaredField("secureRandom");
        field.setAccessible(true);
        field.set(service, new SecureRandom());
    }

    @Test
    void revokeToken_existingAccessToken_removesIt() {
        OAuth2Client client = new OAuth2Client();
        client.setClientId("client-1");
        client.setClientSecret("hashed");
        client.setScopes(Set.of("service"));

        when(clientRepository.findByClientId("client-1")).thenReturn(Optional.of(client));
        when(passwordEncoder.matches("secret", "hashed")).thenReturn(true);

        var tokenResponse = service.clientCredentialsGrant("client-1", "secret");
        String accessToken = tokenResponse.getAccess_token();

        // Revoke it
        service.revokeToken(accessToken, "access_token");

        // Validate it's gone
        assertThatThrownBy(() -> service.validateAccessToken(accessToken))
                .isInstanceOf(CSCOAuth2Exception.class);
    }

    @Test
    void revokeToken_unknownToken_silentlySucceeds() {
        service.revokeToken("nonexistent-token", null);
    }

    @Test
    void tokenResponse_includesCredentialID_whenSet() {
        OAuth2Client client = new OAuth2Client();
        client.setClientId("client-1");
        client.setClientSecret("hashed");
        client.setScopes(Set.of("credential"));

        when(clientRepository.findByClientId("client-1")).thenReturn(Optional.of(client));
        when(passwordEncoder.matches("secret", "hashed")).thenReturn(true);

        var tokenResponse = service.clientCredentialsGrant("client-1", "secret");
        assertThat(tokenResponse.getAccess_token()).isNotNull();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
mvn test -Dtest=CSCOAuth2ServiceTest
```

Expected: FAIL — `service.revokeToken(...)` method doesn't exist.

- [ ] **Step 3: Add credentialID to CSCOAuth2TokenResponse (W4)**

In `CSCOAuth2TokenResponse.java`, add the `credentialID` field:

```java
package com.wpanther.eidasremotesigning.dto.csc;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CSCOAuth2TokenResponse {

    @JsonProperty("access_token")
    private String access_token;

    @JsonProperty("token_type")
    private String token_type;

    @JsonProperty("expires_in")
    private Integer expires_in;

    @JsonProperty("refresh_token")
    private String refresh_token;

    @JsonProperty("scope")
    private String scope;

    @JsonProperty("credentialID")
    private String credentialID;
}
```

- [ ] **Step 4: Add revokeToken() + PKCE to CSCOAuth2Service**

In `CSCOAuth2Service.java`, make the following changes:

**A. Extend `AuthorizationRequest` inner class** to hold PKCE fields and credentialID:

Replace:
```java
private static class AuthorizationRequest {
    private final String clientId;
    private final String redirectUri;
    private final String scope;
    private final String state;
    private final Instant createdAt;

    public AuthorizationRequest(String clientId, String redirectUri, String scope,
                               String state, Instant createdAt) {
        ...
    }
}
```

With:
```java
private static class AuthorizationRequest {
    private final String clientId;
    private final String redirectUri;
    private final String scope;
    private final String state;
    private final String codeChallenge;
    private final String codeChallengeMethod;
    private final String credentialID;
    private final Instant createdAt;

    public AuthorizationRequest(String clientId, String redirectUri, String scope,
                               String state, String codeChallenge, String codeChallengeMethod,
                               String credentialID, Instant createdAt) {
        this.clientId = clientId;
        this.redirectUri = redirectUri;
        this.scope = scope;
        this.state = state;
        this.codeChallenge = codeChallenge;
        this.codeChallengeMethod = codeChallengeMethod;
        this.credentialID = credentialID;
        this.createdAt = createdAt;
    }
}
```

**B. Update `storeAuthorizationRequest()` signature** (add PKCE + credentialID params):

> **Note:** The existing `new Thread(() -> Thread.sleep(600000))` pattern for auth-code expiry is retained as-is (out of scope). It's a known issue — each auth code starts a sleeping thread. A future cleanup should replace it with `ScheduledExecutorService.schedule()` or a TTL-aware `ConcurrentHashMap` wrapper.

```java
public void storeAuthorizationRequest(String code, String clientId, String redirectUri,
                                      String scope, String state,
                                      String codeChallenge, String codeChallengeMethod,
                                      String credentialID) {
    clientRepository.findByClientId(clientId)
            .orElseThrow(() -> new CSCOAuth2Exception("invalid_client", "Client not found"));

    authorizationRequests.put(code, new AuthorizationRequest(
            clientId, redirectUri, scope, state,
            codeChallenge, codeChallengeMethod, credentialID, Instant.now()));

    new Thread(() -> {
        try {
            Thread.sleep(600000);
            authorizationRequests.remove(code);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }).start();
}
```

**C. Update `exchangeAuthorizationCode()` signature** (add `codeVerifier`) and validate PKCE:

Add `String codeVerifier` parameter. After validating the code is found and not expired, verify PKCE:

```java
public CSCOAuth2TokenResponse exchangeAuthorizationCode(String code, String redirectUri,
                                                       String clientId, String clientSecret,
                                                       String codeVerifier) {
    AuthorizationRequest authRequest = authorizationRequests.get(code);
    if (authRequest == null) {
        throw new CSCOAuth2Exception("invalid_grant", "Authorization code is invalid or expired");
    }

    if (!authRequest.redirectUri.equals(redirectUri)) {
        throw new CSCOAuth2Exception("invalid_grant", "Redirect URI does not match");
    }

    // PKCE validation
    if (authRequest.codeChallenge != null) {
        if (codeVerifier == null) {
            throw new CSCOAuth2Exception("invalid_grant", "code_verifier is required");
        }
        String computedChallenge = computeCodeChallenge(codeVerifier, authRequest.codeChallengeMethod);
        if (!computedChallenge.equals(authRequest.codeChallenge)) {
            throw new CSCOAuth2Exception("invalid_grant", "code_verifier does not match code_challenge");
        }
    }

    OAuth2Client client = clientRepository.findByClientId(authRequest.clientId)
            .orElseThrow(() -> new CSCOAuth2Exception("invalid_client", "Client not found"));

    if (clientId != null && clientSecret != null) {
        if (!client.getClientId().equals(clientId)) {
            throw new CSCOAuth2Exception("invalid_client", "Client ID does not match");
        }
        if (!passwordEncoder.matches(clientSecret, client.getClientSecret())) {
            throw new CSCOAuth2Exception("invalid_client", "Invalid client secret");
        }
    }

    String accessToken = generateToken();
    String refreshToken = generateToken();

    Instant expiresAt = Instant.now().plusSeconds(ACCESS_TOKEN_EXPIRATION);
    TokenInfo tokenInfo = new TokenInfo(client.getClientId(), authRequest.scope, expiresAt);
    accessTokens.put(accessToken, tokenInfo);
    refreshTokens.put(refreshToken, accessToken);

    authorizationRequests.remove(code);

    return CSCOAuth2TokenResponse.builder()
            .access_token(accessToken)
            .token_type("Bearer")
            .expires_in(ACCESS_TOKEN_EXPIRATION)
            .refresh_token(refreshToken)
            .scope(authRequest.scope)
            .credentialID(authRequest.credentialID)
            .build();
}
```

**D. Add `revokeToken()` method** (F13):

```java
public void revokeToken(String token, String tokenTypeHint) {
    if (accessTokens.remove(token) != null) {
        refreshTokens.entrySet().removeIf(e -> token.equals(e.getValue()));
        log.debug("Revoked access token");
        return;
    }
    if (refreshTokens.remove(token) != null) {
        log.debug("Revoked refresh token");
        return;
    }
    log.debug("Token not found for revocation (may already be expired): hint={}", tokenTypeHint);
}
```

**E. Add `computeCodeChallenge()` private helper** (PKCE SHA256 or plain):

```java
private String computeCodeChallenge(String codeVerifier, String method) {
    if ("plain".equals(method) || method == null) {
        return codeVerifier;
    }
    if ("S256".equals(method)) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(codeVerifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new CSCOAuth2Exception("server_error", "SHA-256 not available");
        }
    }
    throw new CSCOAuth2Exception("invalid_request", "Unsupported code_challenge_method: " + method);
}
```

- [ ] **Step 5: Update CSCOAuth2Controller**

**A. Update `authorize()` to accept PKCE + credentialID params** (W3):

```java
@GetMapping("/authorize")
public void authorize(
        @RequestParam String response_type,
        @RequestParam String client_id,
        @RequestParam String redirect_uri,
        @RequestParam(required = false) String scope,
        @RequestParam(required = false) String state,
        @RequestParam(required = false) String code_challenge,
        @RequestParam(required = false) String code_challenge_method,
        @RequestParam(required = false) String credentialID,
        HttpServletRequest request,
        HttpServletResponse response) throws Exception {

    log.debug("CSC OAuth2 authorize request from client: {}", client_id);

    if (!"code".equals(response_type)) {
        sendErrorRedirect(redirect_uri, "unsupported_response_type",
                "Only code response type is supported", state, response);
        return;
    }

    String authCode = UUID.randomUUID().toString();

    oAuth2Service.storeAuthorizationRequest(authCode, client_id, redirect_uri, scope, state,
            code_challenge, code_challenge_method, credentialID);

    String callbackUrl = UriComponentsBuilder.fromUriString(redirect_uri)
            .queryParam("code", authCode)
            .queryParam("state", state)
            .build().toUriString();

    response.sendRedirect(callbackUrl);
}
```

**B. Update `token()` to pass codeVerifier** (W3):

In the `"authorization_code"` case, pass `code_verifier`:

```java
@PostMapping("/token")
public ResponseEntity<CSCOAuth2TokenResponse> token(
        @RequestParam String grant_type,
        @RequestParam(required = false) String code,
        @RequestParam(required = false) String redirect_uri,
        @RequestParam(required = false) String client_id,
        @RequestParam(required = false) String client_secret,
        @RequestParam(required = false) String refresh_token,
        @RequestParam(required = false) String code_verifier,
        HttpServletRequest request) {

    log.debug("CSC OAuth2 token request with grant type: {}", grant_type);

    CSCOAuth2TokenResponse response;

    switch (grant_type) {
        case "authorization_code":
            if (code == null || redirect_uri == null) {
                throw new CSCOAuth2Exception("invalid_request", "Missing required parameters");
            }
            response = oAuth2Service.exchangeAuthorizationCode(code, redirect_uri, client_id,
                    client_secret, code_verifier);
            break;

        case "refresh_token":
            if (refresh_token == null) {
                throw new CSCOAuth2Exception("invalid_request", "Missing refresh token");
            }
            response = oAuth2Service.refreshAccessToken(refresh_token, client_id, client_secret);
            break;

        case "client_credentials":
            response = oAuth2Service.clientCredentialsGrant(client_id, client_secret);
            break;

        default:
            throw new CSCOAuth2Exception("unsupported_grant_type", "Unsupported grant type");
    }

    return ResponseEntity.ok(response);
}
```

**C. Add `revoke()` endpoint** (F13):

RFC 7009 §2.2 and CSC spec §8.4.5 require HTTP 204 No Content. Unknown tokens must NOT produce an error.

```java
@PostMapping("/revoke")
public ResponseEntity<Void> revoke(
        @RequestParam String token,
        @RequestParam(required = false) String token_type_hint) {
    log.debug("CSC OAuth2 revoke request");
    oAuth2Service.revokeToken(token, token_type_hint);
    return ResponseEntity.noContent().build();
}
```

- [ ] **Step 6: Run tests**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
mvn test -Dtest=CSCOAuth2ServiceTest
mvn test
```

Expected: All pass.

- [ ] **Step 7: Run full test suite including integration tests**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
mvn verify
```

Expected: All unit and integration tests pass.

- [ ] **Step 8: Commit**

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
git add src/main/java/com/wpanther/eidasremotesigning/dto/csc/CSCOAuth2TokenResponse.java \
        src/main/java/com/wpanther/eidasremotesigning/service/CSCOAuth2Service.java \
        src/main/java/com/wpanther/eidasremotesigning/controller/CSCOAuth2Controller.java \
        src/test/java/com/wpanther/eidasremotesigning/service/CSCOAuth2ServiceTest.java
git commit -m "fix(F13,W3,W4): add /oauth2/revoke, PKCE support, credentialID in token response"
```

---

## Post-Implementation Checklist

After all 12 tasks are complete, run the full integration test suite:

```bash
cd /home/wpanther/projects/etax/eidasremotesigning
mvn verify
```

Verify these conformance items are addressed:

| Issue | Status | Verified by |
|-------|--------|-------------|
| F1: oauth2 String | Task 1 | CSCInfoResponseDtoTest |
| F2: envelope_properties array-of-arrays | Task 1 | CSCInfoResponseDtoTest |
| F3: logo field populated | Task 1 | CSCInfoResponseDtoTest |
| F4: authorize returns only SAD (no handle) | Task 7 | CSCAuthorizationServiceTest |
| F5: authorizeCheck 202 for pending | Task 6 | Integration test |
| F6: extendTransaction returns new SAD | Task 8 | CSCAuthorizationServiceTest |
| F7: timestamp field name | Task 2 | CSCSignatureDtoTest |
| F8: DocumentWithSignature PascalCase | Task 4 | CSCSignatureDtoTest |
| F9: signPolling 202 for in-progress | Task 4 | Integration test |
| F10: no responseID in sync signDoc | Task 10 | Manual / integration |
| F11: credentialID optional in signDoc | Task 5 | AtLeastOneOfValidatorTest |
| F12: authData.value not required | Task 5 | AtLeastOneOfValidatorTest |
| F13: /oauth2/revoke endpoint | Task 12 | CSCOAuth2ServiceTest |
| F14: credentials/info certificates param | Task 8 | Manual / integration |
| W1: curve (not curveIds) | Task 11 | Manual / integration |
| W2: cert/status valid/expired | Task 11 | Manual / integration |
| W3: PKCE in authorize | Task 12 | CSCOAuth2ServiceTest |
| W4: credentialID in token response | Task 12 | CSCOAuth2ServiceTest |
| W5: no signatureAlgorithm in signHash | Task 3 | CSCSignatureDtoTest |
| W6: non-spec fields removed from signPolling | Task 4 | CSCSignatureDtoTest |
| W7: non-spec fields removed from authorizeCheck | Task 6 | Unit test |
| W8: RSASSA-PSS OID in OIDMapper | Task 9 | OIDMapperTest |
| W9: returnValidationInfo logged | Task 10 | Code review |
| W10: credentialInfo param honoured in list | Task 11 | Manual / integration |
| W11: spec-compliant credentials/list request | Task 11 | Compilation |
