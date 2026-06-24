# CSC API v2.0.0.2 Conformance Fix — Design

**Date:** 2026-06-24
**Scope:** `eidasremotesigning` Spring Boot service (port 9000)
**Spec:** `docs/csc-api-v2.0.0.2.md`
**Callers:** `xml-signing-service` and `pdf-signing-service` will be updated separately after this work.

---

## Background

A conformance audit against the CSC API v2.0.0.2 spec found **14 FAIL** (hard spec violations) and **12 WARN** (minor deviations) across DTOs, services, controllers, and the OAuth2 layer. All 26 items are fixed in this design. Production code changes are accompanied by updated/new unit and integration tests.

---

## Approach: Fix by Component Layer (Bottom-Up)

Changes are ordered by dependency: DTOs must be correct before the services that use them, and services before the controllers that call them.

| Batch | Layer | Items |
|-------|-------|-------|
| 1 | DTOs | F1, F2, F3 (partial), F7, F8, F11, F12, F14, W1, W4, W5, W6, W7, W11 |
| 2 | Services | F6, F10, W2, W8, W9, W10 |
| 3 | Controllers | F2 (populate), F3 (populate), F4, F5, F9, F14 (wire-up) |
| 4 | OAuth2 | F13, W3, W4 (wire-up) |

---

## Layer 1: DTOs

### `CSCInfoResponse`

**F1 — `oauth2` type change**
- Change field type: `OAuth2Info oauth2` → `String oauth2`
- The value is the base URI of the OAuth2 authorization server (e.g. `"http://localhost:9000"`). Clients discover individual endpoints by appending standard paths or via RFC 8414 discovery.
- Delete the `OAuth2Info` inner class entirely.

**F2 — `envelope_properties` type change**
- In `SignatureFormats` inner class: change `List<String> envelopeProperties` → `List<List<String>> envelopeProperties`
- `@JsonProperty("envelope_properties")` annotation retained.
- Population deferred to Layer 3 (controller).

**F3 — add `logo` field**
- Add `String logo` field with `@JsonProperty("logo")`.
- Value sourced from `app.csc.logo-url` config property (default: `"http://localhost:9000/logo.png"`).

**F7 — `CSCTimestampResponse` field rename**
- Rename `timestampToken` → `timestamp` with `@JsonProperty("timestamp")`.
- Remove non-spec fields: `timestampDigest`, `timestampGenerationTime`.
- After: only `timestamp` (String, Base64-encoded RFC 3161 token).

**F8 — `CSCSignatureStatusResponse` PascalCase**
- Add `@JsonProperty("DocumentWithSignature")` to the document-with-signature field.
- Add `@JsonProperty("SignatureObject")` to the signature-object field.

**F11 — `CSCSignDocumentRequest` credential validation**
- Remove `@NotBlank` from `credentialID`.
- Add class-level custom constraint `@AtLeastOneOf(fields = {"credentialID", "signatureQualifier"})` with a validator that rejects requests where both are null/blank.

**F12 — `CSCAuthorizeRequest.AuthDataEntry.value`**
- Remove `@NotBlank` from `value`.
- `value` becomes fully optional — PasswordOOB and ChallengeResponseOOB auth objects send no value, only `id`.

**F14 — `CSCCredentialsInfoRequest` add `certificates`**
- Add `String certificates` field (allowed values: `"none"` | `"single"` | `"chain"`; default `"single"` if absent).

**W1 — `CSCCertificateInfo.CSCKeyInfo.curve`**
- Rename field `curveIds: String[]` → `curve: String` (single OID string per spec §11.5).
- Update `@JsonProperty("curve")` annotation.
- Update all call sites that previously set `curveIds`.

**W4 — `CSCOAuth2TokenResponse` add `credentialID`**
- Add `@JsonProperty("credentialID") String credentialID` field (nullable; only populated when AS resolves a `signatureQualifier` to a credential).

**W5 — `CSCSignatureResponse` remove non-spec field**
- Remove `signatureAlgorithm` field from `CSCSignatureResponse` (signHash response).
- Spec §11.10 output: only `signatures[]` (sync) or `responseID` (async).

**W6 — `CSCSignatureStatusResponse` remove non-spec fields**
- Remove: `status`, `errorMessage`, `signatureAlgorithm`, `timestampData`, `responseID`.
- Retained spec fields: `signatures[]`, `DocumentWithSignature[]`, `SignatureObject[]`.

**W7 — `CSCAuthorizeStatusResponse` remove non-spec fields**
- Remove: `credentialID`, `status`, `authMode`.
- Retained spec fields: `SAD` (200 path), `expiresIn` (200 path), `handle` (202 path).

**W11 — `CSCCredentialsListRequest` align to spec**
- Remove non-spec field: `maxResults`.
- Add spec-defined fields:
  - `Boolean credentialInfo` (default `false`)
  - `String certificates` (default `"single"`)
  - `Boolean certInfo` (default `false`)
  - `Boolean authInfo` (default `false`)
  - `Boolean onlyValid` (default `false`)
  - `String lang`

### Tests (Layer 1)
- **Unit**: JSON serialization tests for each modified DTO — assert field names serialize to spec-defined names and removed fields are absent.
- **Unit**: Validation tests for `@AtLeastOneOf` on `CSCSignDocumentRequest` — assert rejection when both `credentialID` and `signatureQualifier` are null, and acceptance when either is present.
- **Unit**: Validation test for `AuthDataEntry` — assert requests with no `value` (PasswordOOB) are accepted.

---

## Layer 2: Services

### `CSCAuthorizationService.extendTransaction()` (F6)

**Problem:** Returns only `expiresIn`; `SAD` field is always null — breaks multi-signature PDF flows entirely.

**Fix:**
- After validating the current SAD, generate a new SAD using the same mechanism as `authorizeCredential()` (create a new `TransactionAuthorization` entity, persist it, return its `sad` value).
- Set `response.setSAD(newSad)` before returning.
- Invalidate (or decrement) the old SAD's remaining signatures so it cannot be reused independently.

### `CSCSignatureService.executeSignDocument()` (F10)

**Problem:** Sets `responseID` on the synchronous path.

**Fix:**
- Move `responseID` assignment inside the `if (operationMode == "A")` branch only. The sync path response builder must not call `responseID(...)`.

### `CSCSignatureService.executeSignDocument()` (W9 — `returnValidationInfo`)

**Problem:** `returnValidationInfo` is parsed but never used; `validationInfo` is never populated.

**Fix:**
- After signing, if `request.getReturnValidationInfo() == Boolean.TRUE`:
  - Extract OCSP responses, CRLs, and extra certificates from the EU DSS `CertificateVerifier` / `ValidationData` available after the signing operation.
  - Populate `validationInfo.ocsp[]`, `validationInfo.crl[]`, `validationInfo.certificates[]` as Base64-encoded DER structures.
- If `returnValidationInfo` is false/null, leave `validationInfo` null.

### `CSCApiService.mapToCscCertificateInfo()` (W2 — `cert/status`)

**Fix:**
```java
try {
    x509Cert.checkValidity();
    certDetails.setStatus("valid");
} catch (CertificateExpiredException e) {
    certDetails.setStatus("expired");
} catch (CertificateNotYetValidException e) {
    certDetails.setStatus("valid"); // not yet valid treated as valid per spec (not a defined status)
}
// Revocation (revoked/suspended) requires CRL/OCSP — not implemented; leave as "valid"
```

### `OIDMapper` (W8 — RSASSA-PSS)

**Fix:**
- Add to `SIG_OID_TO_JCA`: `"1.2.840.113549.1.1.10"` → `"RSASSA-PSS"`
- Add reverse mapping in `JCA_TO_SIG_OID`.
- Add `"1.2.840.113549.1.1.10"` to the RSA key algo entry in `supportedSigOidsForKeyAlgo()`.

### `CSCApiService.listCredentials()` and `getCredentialInfo()` (W10, F14)

**`listCredentials()` fix:**
- When `request.getCredentialInfo() == true`: for each credential, build a full `CredentialInfo` object (reusing `getCredentialInfo()` logic) and populate `credentialInfos[]` in the response.
- When `request.getOnlyValid() == true`: filter to credentials where `key.status = "enabled"` and cert is not expired. Set `onlyValid: true` in the response.
- Honour `certInfo`, `authInfo`, `certificates` by passing them through to the per-credential info builder.

**`getCredentialInfo()` fix:**
- Switch on `request.getCertificates()`:
  - `"none"` → omit `cert.certificates[]`
  - `"single"` (default) → return end-entity certificate only
  - `"chain"` → return full chain (current behaviour)

### Tests (Layer 2)
- **Unit** (`CSCAuthorizationServiceTests`): `extendTransactionReturnsSAD()` — assert non-null SAD in response; `extendTransactionInvalidatesOldSAD()` — assert old SAD no longer accepted after extend.
- **Unit** (`CSCSignatureServiceTests`): `signDocSyncDoesNotReturnResponseID()` — assert `responseID` null on sync response; `signDocAsyncReturnsResponseID()` — assert `responseID` non-null on async response.
- **Unit** (`CSCSignatureServiceTests`): `signDocPopulatesValidationInfoWhenRequested()` — assert `validationInfo` non-null when `returnValidationInfo=true`; `signDocOmitsValidationInfoByDefault()`.
- **Unit** (`OIDMapperTests`): `rsaPssOidMapsToJca()` and `rsaPssJcaMapsToOid()`.
- **Unit** (`CSCApiServiceTests`): `listCredentialsWithCredentialInfo()`, `listCredentialsOnlyValid()`, `getCredentialInfoCertificatesNone()`, `getCredentialInfoCertificatesSingle()`, `getCredentialInfoCertificatesChain()`.

---

## Layer 3: Controllers

### `CSCApiController.getInfo()` (F2, F3)

**`logo`:** Read from `@Value("${app.csc.logo-url:http://localhost:9000/logo.png}") String logoUrl` and set on the response.

**`envelope_properties`:** Build as `List<List<String>>` matching the `formats` list order:
```java
List<String> formats = List.of("P", "X", "C");
List<List<String>> envelopeProps = List.of(
    List.of("Certification", "Revision"),      // P = PAdES
    List.of("Enveloped", "Enveloping", "Detached"), // X = XAdES
    List.of("Detached", "Attached", "Parallel")     // C = CAdES
);
```

### `CSCAuthorizationController.authorizeCredential()` (F4)

**Fix:**
```java
CSCAuthorizeResponse response = authorizationService.authorizeCredential(request);
if (response.getSAD() != null) {
    return ResponseEntity.ok(response);          // 200 — SAD issued synchronously
} else {
    return ResponseEntity.status(202).body(response); // 202 — async, handle returned
}
```
The service sets either `SAD` (sync) or `handle` (async) — never both.

### `CSCAuthorizationController.getAuthorizeStatus()` (F5)

**Fix:** Same pattern as above:
```java
CSCAuthorizeStatusResponse response = authorizationService.getAuthorizeStatus(request);
if (response.getSAD() != null) {
    return ResponseEntity.ok(response);
} else {
    return ResponseEntity.status(202).body(response);
}
```

### `CSCSignatureController.getSignatureStatus()` (F9)

**Fix:**
```java
AsyncOperation op = asyncOperationService.getOperation(request.getRequestID());
if (op == null) {
    throw new CSCException("invalid_request", "Invalid parameter requestID");
}
switch (op.getStatus()) {
    case CREATED, PROCESSING ->
        return ResponseEntity.status(400)
            .body(new CSCErrorResponse("accepted_request",
                "The previous async request has been accepted but not yet completed"));
    case COMPLETED ->
        return ResponseEntity.ok(buildSignPollingResponse(op));
    case FAILED, EXPIRED ->
        return ResponseEntity.status(400)
            .body(new CSCErrorResponse("signing_error", "Async signing operation failed or expired"));
}
```

### Tests (Layer 3)
- **Integration** (`EidasRemoteSigningIT` or new `CSCAuthorizationIT`): `authorizeWithPinReturns200WithSAD()`, `authorizeWithOOBReturns202WithHandle()`.
- **Integration**: `authorizeCheckPendingReturns202()`, `authorizeCheckCompleteReturns200WithSAD()`.
- **Integration**: `signPollingInProgressReturns400AcceptedRequest()`, `signPollingCompletedReturns200WithSignatures()`.
- **Unit** (`CSCApiControllerTests`): `infoResponseHasLogoField()`, `infoResponseEnvelopePropertiesIsNestedArray()`.

---

## Layer 4: OAuth2

### New `POST /csc/v2/oauth2/revoke` endpoint (F13)

**`CSCOAuth2Controller`:**
```java
@PostMapping("/revoke")
public ResponseEntity<Void> revokeToken(
        @RequestParam("token") String token,
        @RequestParam(value = "token_type_hint", required = false) String tokenTypeHint) {
    oAuth2Service.revokeToken(token, tokenTypeHint);
    return ResponseEntity.noContent().build(); // 204
}
```

**`CSCOAuth2Service.revokeToken()`:**
- If `tokenTypeHint = "refresh_token"` or token found in refresh token store: remove from refresh store; also remove all access tokens issued from the same grant.
- If `tokenTypeHint = "access_token"` or token found in access token store: remove from access token store only; do not touch refresh tokens.
- If token not found in either store: silently return (per RFC 7009 §2.2 — unknown tokens are not an error).

### OAuth2 `authorize` PKCE and credential-scope params (W3)

**`CSCOAuth2Controller.authorize()`** — add request params:
```java
@RequestParam(required = false) String codeChallenge,
@RequestParam(name = "code_challenge_method", required = false) String codeChallengeMethod,
@RequestParam(required = false) String credentialID,
@RequestParam(required = false) Integer numSignatures,
@RequestParam(required = false) String hashes,
@RequestParam(required = false) String hashAlgorithmOID,
@RequestParam(name = "account_token", required = false) String accountToken,
@RequestParam(required = false) String description
```

**`CSCOAuth2Service` PKCE handling:**
- When storing an auth code, also persist `codeChallenge` and `codeChallengeMethod`.
- In `issueToken()` (authorization_code grant): if `codeChallenge` was stored, require `code_verifier` in the token request. Verify: `BASE64URL(SHA256(code_verifier)) == codeChallenge` for `S256`; `code_verifier == codeChallenge` for `plain`.

### `CSCOAuth2Service.issueToken()` — `credentialID` in response (W4)

- When the authorization was for `scope=credential` with `signatureQualifier` (no explicit `credentialID`): resolve the appropriate credential for the authenticated client (query `SigningCertificateRepository` by `clientId` and matching key algo for the qualifier) and set `credentialID` on `CSCOAuth2TokenResponse`.

### Tests (Layer 4)
- **Integration** (`CSCOAuth2IT`): `revokeAccessTokenReturns204()`, `revokeRefreshTokenReturns204()`, `revokeUnknownTokenReturns204()` (silent success per RFC 7009).
- **Unit** (`CSCOAuth2ServiceTests`): `issueTokenWithCodeVerifierS256()`, `issueTokenWithCodeVerifierPlain()`, `issueTokenWithInvalidCodeVerifierRejects()`.
- **Unit** (`CSCOAuth2ServiceTests`): `issueTokenWithSignatureQualifierResolvesCredentialID()`.

---

## Configuration Changes

Add to `application.yml`:
```yaml
app:
  csc:
    logo-url: http://localhost:9000/logo.png
```

Add to `application-test.yml`: same default (avoids null in tests).

---

## Files Changed (Summary)

| File | Change |
|------|--------|
| `dto/csc/CSCInfoResponse.java` | F1, F2, F3 (field), W11 inner class removed |
| `dto/csc/CSCTimestampResponse.java` | F7 |
| `dto/csc/CSCSignatureStatusResponse.java` | F8, W6 |
| `dto/csc/CSCSignDocumentRequest.java` | F11 |
| `dto/csc/CSCAuthorizeRequest.java` | F12 |
| `dto/csc/CSCCredentialsInfoRequest.java` | F14 |
| `dto/csc/CSCCredentialsListRequest.java` | W11 |
| `dto/csc/CSCAuthorizeStatusResponse.java` | W7 |
| `dto/csc/CSCOAuth2TokenResponse.java` | W4 |
| `dto/csc/CSCSignatureResponse.java` | W5 |
| `dto/CSCCertificateInfo.java` | W1 |
| `validation/AtLeastOneOf.java` (new) | F11 custom constraint annotation |
| `validation/AtLeastOneOfValidator.java` (new) | F11 custom constraint validator |
| `service/CSCAuthorizationService.java` | F6 |
| `service/CSCSignatureService.java` | F10, W9 |
| `service/CSCApiService.java` | W2, W10, F14 wire-up |
| `service/CSCOAuth2Service.java` | F13, W3, W4 |
| `util/OIDMapper.java` | W8 |
| `controller/CSCApiController.java` | F2 populate, F3 populate |
| `controller/CSCAuthorizationController.java` | F4, F5 |
| `controller/CSCSignatureController.java` | F9 |
| `controller/CSCOAuth2Controller.java` | F13 |
| `src/main/resources/application.yml` | logo-url config |
| `src/test/resources/application-test.yml` | logo-url config |
| Tests (unit + integration) | All of the above |

---

## Out of Scope

- Updating `xml-signing-service` and `pdf-signing-service` callers — separate effort after this ships.
- CRL/OCSP revocation status check for `cert/status` (W2) — noted in code with a comment; full revocation checking is a future enhancement.
- `credentials/sendOTP` endpoint — not currently implemented; not in scope.
