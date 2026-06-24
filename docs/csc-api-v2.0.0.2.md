# CSC API v2.0.0.2 — Cloud Signature Consortium API Specification

> Converted from the official CSC API v2.0.0.2 specification PDFs for AI context use.
> Technical sections only. Covers Parts 1–5 (100 pages total).

---

## Table of Contents

1. [Scope](#1-scope)
2. [Requirement Levels](#2-requirement-levels)
3. [References](#3-references)
4. [Terms and Definitions](#4-terms-and-definitions)
5. [Conventions](#5-conventions)
6. [Architectures and Use Cases](#6-architectures-and-use-cases)
7. [API Introduction](#7-api-introduction)
8. [Authentication and Authorization](#8-authentication-and-authorization)
9. [Creating a Remote Signature](#9-creating-a-remote-signature)
10. [Error Handling](#10-error-handling)
11. [The Remote Service APIs](#11-the-remote-service-apis)
12. [JSON Schema and OpenAPI Description](#12-json-schema-and-openapi-description)
13. [Interaction Among Elements and Components](#13-interaction-among-elements-and-components)
14. [Change History](#14-change-history)

---

## 1 Scope

This specification defines the Cloud Signature Consortium (CSC) API v2.0.0.2, a RESTful API for remote digital signing services conforming to eIDAS (Regulation (EU) No 910/2014). The API enables signature applications to:

- Discover remote signing credentials
- Authorize access to signing keys
- Create digital signatures (hash-based and document-based)
- Obtain timestamps

The API supports creation of CAdES, XAdES, PAdES, and JAdES signatures in accordance with ETSI standards.

---

## 2 Requirement Levels

This specification uses RFC 2119 keywords:

- **SHALL** / **SHALL NOT** — absolute requirement / prohibition
- **SHOULD** / **SHOULD NOT** — recommended / not recommended, with valid reasons to deviate
- **MAY** — optional

---

## 3 References

### Normative References

| Ref | Document |
|-----|----------|
| [1] | RFC 2119 — Key words for use in RFCs |
| [2] | RFC 3161 — Internet X.509 PKI Time-Stamp Protocol |
| [3] | RFC 3986 — URI Generic Syntax |
| [4] | RFC 4514 — LDAP String Representation of Distinguished Names |
| [5] | RFC 4627 — JSON Media Type |
| [6] | RFC 5280 — Internet X.509 PKI Certificate and CRL Profile |
| [7] | RFC 5646 — Tags for Identifying Languages |
| [8] | RFC 5280 — GeneralizedTime encoding |
| [9] | RFC 5646 — Language tags |
| [10] | RFC 5816 — ESSCertIDv2 Update |
| [11] | RFC 6749 — OAuth 2.0 Authorization Framework |
| [12] | RFC 6750 — OAuth 2.0 Bearer Token Usage |
| [13] | RFC 7009 — OAuth 2.0 Token Revocation |
| [14] | RFC 7521 — Assertion Framework for OAuth 2.0 |
| [15] | RFC 7518 — JSON Web Algorithms (JWA) |
| [16] | RFC 7519 — JSON Web Token (JWT) |
| [17] | RFC 7235 — HTTP Authentication |
| [18] | RFC 8017 — PKCS#1 v2.2 |
| [20] | OAuth 2.0 Security Best Current Practice |
| [21] | ETSI TS 119 312 — Cryptographic Suites |
| [22] | ISO 3166-1 — Country codes |
| [23] | IETF RFC 8414 — OAuth 2.0 Authorization Server Metadata |
| [24] | IETF RFC 7591 — OAuth Token Endpoint Authentication Methods |
| [25] | RFC 7636 — PKCE |
| [27] | IETF Draft draft-ietf-oauth-rar — Rich Authorization Requests |
| [28] | IETF Draft draft-ietf-oauth-par — Pushed Authorization Requests |
| [29] | ETSI EN 319 122-1 — CAdES |
| [30] | ETSI EN 319 132-1 — XAdES |
| [31] | ETSI EN 319 142-1 — PAdES |
| [33] | RFC 6960 — OCSP |

---

## 4 Terms and Definitions

| Term | Definition |
|------|------------|
| **RSCD** | Remote Signing Cryptographic Device — secure device holding signing keys |
| **RSSP** | Remote Signing Service Provider — organization managing the RSCD on behalf of signers |
| **RSCA** | Remote Signing Client Application (= Signature Application) |
| **SAD** | Signature Activation Data — token returned by credential authorization, authorizes signing |
| **SCAL1** | Sole Control Assurance Level 1 — hash not linked to SAD |
| **SCAL2** | Sole Control Assurance Level 2 — hash is linked to SAD |
| **signatureQualifier** | Symbolic identifier for the type of signature (e.g., `eu_eidas_qes`) |
| **clientData** | Arbitrary application-specific string passed through for debugging/tracking |

---

## 5 Conventions

### Expressing Algorithms

All algorithm identifiers use **OID dot-notation**. Examples:

| Algorithm | OID |
|-----------|-----|
| SHA-256 | `2.16.840.1.101.3.4.2.1` |
| SHA-384 | `2.16.840.1.101.3.4.2.2` |
| SHA-512 | `2.16.840.1.101.3.4.2.3` |
| RSA (PKCS#1 v1.5) | `1.2.840.113549.1.1.1` |
| SHA256withRSA | `1.2.840.113549.1.1.11` |
| SHA384withRSA | `1.2.840.113549.1.1.12` |
| SHA512withRSA | `1.2.840.113549.1.1.13` |
| RSASSA-PSS | `1.2.840.113549.1.1.10` |
| ECDSA with SHA-256 | `1.2.840.10045.4.3.2` |
| ECDSA with SHA-384 | `1.2.840.10045.4.3.3` |
| ECDSA with SHA-512 | `1.2.840.10045.4.3.4` |

Hash algorithms as strong or stronger than SHA-256 SHALL be used. The hash algorithm SHOULD follow the recommendations of ETSI TS 119 312.

---

## 6 Architectures and Use Cases

### 6.1 Overview

A remote signing solution consists of:

1. **Signature Application (RSCA)** — client that requests signatures
2. **Remote Signing Service Provider (RSSP)** — server hosting signing credentials
3. **Remote Signing Cryptographic Device (RSCD)** — HSM or cloud KMS holding keys
4. **Authorization Server** — may be the same as RSSP or a separate OAuth2 AS

The signature application communicates with the RSSP via the CSC API. The RSSP manages authentication and authorization of the signer before releasing a signature.

### 6.2 Use Cases

Three use cases are supported:

1. **Single hash** — sign one hash value with one credential authorization
2. **Multiple hashes in one call** — batch signing in a single `signatures/signHash` call
3. **Multiple hashes across multiple calls** — multi-signature transaction using `credentials/extendTransaction`

---

## 7 API Introduction

### 7.1 API Format

- All API methods use **JSON** (`Content-Type: application/json`)
- OAuth 2.0 endpoints use `application/x-www-form-urlencoded`
- All parameters are string unless otherwise specified
- `null` values indicate an absent optional parameter

### 7.2 Base URI

```
https://service.domain.org/{prefix}/csc/v2/
```

Example: `https://service.domain.org/csc/v2/info`

### 7.3 Transport Security

All API communication SHALL use TLS. Clients SHOULD verify server certificates.

### 7.4 clientData Parameter

Many methods accept an optional `clientData` (String) parameter — arbitrary data from the signature application for debugging or transaction correlation. WARNING: this MAY expose sensitive data to the remote service.

### 7.5 Expressing Algorithms (OIDs)

All algorithm fields (`signAlgo`, `hashAlgorithmOID`, `key/algo`) use OID strings in dotted notation. See Section 5 for the mapping table.

---

## 8 Authentication and Authorization

### 8.1 Service Authorization

Service authorization grants access to the API. Methods requiring service authorization need an access token with scope `"service"` passed in the `Authorization: Bearer` header.

Supported mechanisms:
- HTTP Basic / Digest authentication (via `auth/login`)
- OAuth 2.0 Authorization Code flow
- OAuth 2.0 Client Credentials flow
- TLS mutual authentication

### 8.2 Credential Authorization

Credential authorization grants permission to use a specific signing credential. Methods requiring credential authorization need either:

- A **SAD** (Signature Activation Data) obtained from `credentials/authorize` or `credentials/extendTransaction`
- An **access token** with scope `"credential"` (can substitute for SAD in signing methods)

**SCAL levels:**
- **SCAL1**: SAD is not bound to specific hash values
- **SCAL2**: SAD is cryptographically bound to the specific hash values to be signed (returned by `credentials/info` as `SCAL: "2"`)

### 8.3 Authentication Objects

Authentication objects describe the authentication factor types supported by a credential. They are returned in `credentials/info` under `auth/objects[]` and provided in `credentials/authorize` under `authData[]`.

#### Authentication Type Properties (common fields)

| Name | Presence | Description |
|------|----------|-------------|
| *type* | REQUIRED | The authentication type identifier string |
| *id* | REQUIRED | Unique identifier for this authentication object |
| *label* | OPTIONAL | Human-readable label to display to the user |
| *description* | OPTIONAL | Human-readable description for the user |

#### Authentication Object Properties (common fields)

| Name | Presence | Description |
|------|----------|-------------|
| *id* | REQUIRED | Matches the `id` from the authentication type |
| *value* | REQUIRED (for most types) | The concrete authentication value |

#### 8.3.1.1 Password

The authorization is based on a password (PIN) provided by the user in-band.

**Authentication type properties:**

| Name | Presence | Value | Description |
|------|----------|-------|-------------|
| *type* | REQUIRED | `"Password"` | |
| *format* | OPTIONAL | `"A"` \| `"N"` | Format: "A" = alphanumeric, "N" = numeric. If omitted, any character is allowed. |
| *label* | OPTIONAL | String | |
| *description* | OPTIONAL | String | |
| *generator* | OPTIONAL | String | Client-side algorithm to derive the password (e.g., `"totp"`) |

**Authentication object properties:**

| Name | Presence | Description |
|------|----------|-------------|
| *id* | REQUIRED | |
| *value* | REQUIRED | The password/PIN value |

**Example authentication type:**
```json
{
    "type": "Password",
    "id": "PIN",
    "format": "N",
    "label": "PIN",
    "description": "Please enter the signature PIN"
}
```

**Example authentication object:**
```json
{
    "id": "PIN",
    "value": "123456"
}
```

#### 8.3.1.2 Password, TOTP generator

Same as Password but with `"generator": "totp"`. The signature application generates the OTP using the TOTP algorithm.

**Example authentication type:**
```json
{
    "type": "Password",
    "id": "OTP",
    "format": "N",
    "generator": "totp",
    "label": "Mobile OTP",
    "description": "Please enter the 6 digit code you received by SMS"
}
```

#### 8.3.1.3 Password, out of band (PasswordOOB)

The password is obtained out-of-band (e.g., sent via SMS). An empty authentication object is sent in-band to signal to the server that out-of-band data must be acquired.

**Authentication type properties:**

| Name | Presence | Value | Description |
|------|----------|-------|-------------|
| *type* | REQUIRED | `"PasswordOOB"` | |
| *generator* | OPTIONAL | String | Client-side device/algorithm to derive the password |

**Authentication object properties:** Empty (no fields) — signals server to obtain out-of-band data.

**Example authentication type:**
```json
{
    "type": "PasswordOOB",
    "id": "PIN2",
    "label": "PIN2"
}
```

**Example authentication object:**
```json
{
    "id": "PIN2"
}
```

#### 8.3.1.4 ChallengeResponse, in-band response

The authorization is based on a challenge-response protocol where the response is created by a client-side mechanism and sent in-band via `credentials/authorize`.

The signature application must first call `credentials/getChallenge` to obtain the challenge (HTTP 200 returns the challenge; HTTP 201 means challenge sent out-of-band and the app only provides means to enter the response).

**Authentication type properties:**

| Name | Presence | Value | Description |
|------|----------|-------|-------------|
| *type* | REQUIRED | `"ChallengeResponse"` | |
| *format* | OPTIONAL | `"A"` \| `"N"` | |
| *generator* | OPTIONAL | String | |

**Authentication object properties:**

| Name | Presence | Description |
|------|----------|-------------|
| *id* | REQUIRED | |
| *value* | REQUIRED | The concrete response value |

**Example authentication type:**
```json
{
    "type": "ChallengeResponse",
    "id": "OTP",
    "label": "OTP"
}
```

**Example authentication object:**
```json
{
    "id": "OTP",
    "value": "sadf8aef"
}
```

#### 8.3.1.5 ChallengeResponse, out-of-band response

The authorization is based on a challenge-response protocol where the response is created by a client-side mechanism and sent via an out-of-band channel.

**Authentication type properties:**

| Name | Presence | Value | Description |
|------|----------|-------|-------------|
| *type* | REQUIRED | `"ChallengeResponseOOB"` | |
| *generator* | OPTIONAL | String | |

**Authentication object properties:** Empty (no data sent in band).

**Example authentication type:**
```json
{
    "type": "ChallengeResponseOOB",
    "id": "SMS",
    "label": "SMS"
}
```

**Example authentication object:**
```json
{
    "id": "SMS"
}
```

An empty authentication object is required to indicate to the server that some out-of-band data must be acquired for this authorization.

---

### 8.4 OAuth 2.0 Authorization

OAuth 2.0 is the RECOMMENDED mechanism for service authorization and credential authorization. The signature application uses the RSSP's authorization server for user authentication and access authorization.

**Supported grant types** (per RFC 6749):
- Authorization Code
- Client Credentials
- Refresh Token

The implicit grant SHALL NOT be used (security flaws).

**Defined scopes:**
- `"service"` — for service authorization
- `"credential"` — for credential authorization (can substitute for SAD)

An access token with scope `"credential"` also covers service authorization for `credentials/info`, `signatures/signHash`, `signatures/signDoc` for the corresponding credential.

**Note:** In course of authorizing "credential" scope, the authorization server authenticates the client and conveys the client identity in the access token (equivalent to service authorization).

**OAuth2 discovery:** The `info` method returns either `oauth2` (base URI) or `oauth2Issuer` (IETF RFC 8414 discovery) to locate OAuth2 endpoints.

#### 8.4.1 Restricted Access to Authorization Servers

To restrict access to the authorization server, the `account_token` parameter (a JWT per RFC 7519) is added to `oauth2/authorize`.

**JWT structure:**

```
account_token = base64UrlEncode(<JWT_Header>) + "." +
                base64UrlEncode(<JWT_Payload>) + "." +
                base64UrlEncode(<JWT_Signature>)
```

**JWT_Header:**
```json
{
    "typ": "JWT",
    "alg": "HS256"
}
```

**JWT_Payload:**
```json
{
    "sub": <Account_ID>,           // Account ID
    "iat": <Unix_Epoch_Time>,      // Issued At Time
    "jti": <Token_Unique_Identifier>, // JWT ID
    "iss": <Signature_Application_Name>, // Issuer (optional)
    "azp": <OAuth2_client_id>     // Authorized presenter
}
```

**JWT_Signature:**
```
HMACSHA256(
    base64UrlEncode(<JWT_Header>) + "." + base64UrlEncode(<JWT_Payload>),
    SHA256(<OAuth2_client_secret>)
)
```

**Parameters:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *typ* | REQUIRED | String JWT | Header parameter per RFC 7519 section 5.1 |
| *alg* | REQUIRED | String HS256 | HMAC using SHA-256 per RFC 7518 section 3.1 |
| *sub* | REQUIRED | String | Account ID allowing RSSP to identify the account |
| *iat* | REQUIRED | Number | Unix Epoch time when issued; determines JWT age |
| *jti* | REQUIRED | String | Unique identifier protecting from replay attacks |
| *iss* | OPTIONAL | String | Name of the issuer (signature application name) |
| *azp* | REQUIRED | String | The OAuth2 `client_id` of the signature application |

**Implementation notes:**
- RSSP SHALL securely share `client_id` and `client_secret` with the signature application
- JWT signature uses HMAC with SHA256 of the OAuth2 `client_secret`
- Signature application SHOULD pre-register Account IDs with the RSSP

#### 8.4.2 oauth2/authorize

The OAuth 2.0 authorization endpoint. Processes Authorization Code flow per RFC 6749 section 1.3.1.

Can operate in two modes:
1. **Classical** — all parameters in the authorization request
2. **Pushed authorization** — parameters pushed first via `oauth2/pushed_authorize`, then referenced by `request_uri`

**Note 14:** `oauth2/authorize` is designed as an unauthenticated endpoint. Providers SHOULD protect it from abuse.

**Input — parameters defined in OAuth 2.0:**

| Parameter | Presence | Value | Defined by | Description |
|-----------|----------|-------|------------|-------------|
| *response_type* | REQUIRED | String | RFC 6749 | SHALL be `"code"` |
| *client_id* | REQUIRED | String | RFC 6749 | |
| *redirect_uri* | REQUIRED Conditional | String | RFC 6749 | URL for redirect after authorization; must match pre-registered value |
| *scope* | OPTIONAL | String | RFC 6749 | `"service"` or `"credential"`. Defaults to `"service"` if omitted |
| *authorization_details* | OPTIONAL | String | IETF Draft-ietf-oauth-rar | Authorization details type `"credential"` for credential authorization |
| *code_challenge* | REQUIRED | String | RFC 7636 | Cryptographic nonce for PKCE |
| *code_challenge_method* | OPTIONAL | String | RFC 7636 | Defaults to `plain`; RECOMMENDED: `S256` |
| *state* | OPTIONAL | String | RFC 6749 | |
| *request_uri* | REQUIRED Conditional | String | IETF Draft-ietf-oauth-par | URI of pushed authorization request; only with `client_id`, no other params |

**Input — parameters defined in this specification:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *lang* | OPTIONAL | String | Preferred language per RFC 5646 |
| *credentialID* | REQUIRED Conditional | String | Credential to authorize; only with scope `"credential"` |
| *signatureQualifier* | REQUIRED Conditional | String | Signature type identifier; only with scope `"credential"` and no `credentialID` |
| *numSignatures* | REQUIRED Conditional | Number | Number of signatures to authorize; only with scope `"credential"` |
| *hashes* | REQUIRED Conditional | String | Base64url-encoded hash values. Required if SCAL=2; otherwise optional. Comma-separated for multiple. |
| *hashAlgorithmOID* | REQUIRED Conditional | String | OID of hash algorithm |
| *description* | OPTIONAL | String | Free-form description of authorization transaction (max 500 chars) |
| *account_token* | OPTIONAL | String | JWT for restricted authorization server access |
| *clientData* | OPTIONAL | String | Arbitrary client data |

**Authorization details type "credential":**

| Field | Presence | Value | Description |
|-------|----------|-------|-------------|
| *type* | REQUIRED | String | Must be `"credential"` |
| *credentialID* | REQUIRED Conditional | String | The credential identifier |
| *signatureQualifier* | REQUIRED Conditional | String | Signature type identifier |
| *documentDigests* | REQUIRED | JSON array | Array of `{hash, label}` objects for each document to be signed |
| *hashAlgorithmOID* | REQUIRED | String | OID of hash algorithm for the hashes in documentDigests |
| *locations* | OPTIONAL | JSON array | Locations where the issued access token shall be used (per RAR spec) |

**Output:** HTTP 302 redirect to `redirect_uri` with:

| Attribute | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *code* | REQUIRED | String | Authorization code; single-use, short-lived |
| *state* | REQUIRED Conditional | String | Returned if `state` was in request |
| *error* | REQUIRED Conditional | String | Error code if authorization failed |
| *error_description* | OPTIONAL | String | Human-readable error detail |
| *error_uri* | OPTIONAL | String | URI with error information |

**Error codes:** `invalid_request`, `access_denied`, `unsupported_response_type`, `invalid_scope`, `server_error`, `temporarily_unavailable`

**Sample Request (Service authorization):**
```
GET https://www.domain.org/oauth2/authorize?
  response_type=code&
  client_id=<OAuth2_client_id>&
  redirect_uri=<OAuth2_redirect_uri>&
  scope=service&
  code_challenge=K2-ltc83acc4h0c9w6ESC_rEMTJ3bww-uCHaoeK1t8U&
  code_challenge_method=S256&
  lang=en-US&
  state=12345678
```

**Sample Response:**
```
HTTP/1.1 302 Found
Location: <OAuth2_redirect_uri>?code=FhkXf9P269L8g&state=12345678
```

**Sample Request (Credential authorization):**
```
GET https://www.domain.org/oauth2/authorize?
  response_type=code&
  client_id=<OAuth2_client_id>&
  redirect_uri=<OAuth2_redirect_uri>&
  scope=credential&
  code_challenge=K2-ltc83acc4h0c9w6ESC_rEMTJ3bww-uCHaoeK1t8U&
  code_challenge_method=S256&
  credentialID=GX0112348&
  numSignatures=1&
  hashes=MTIzNDU2Nzg5MHF3ZXJ0enVpb3Bhc2RmZhqa2zDtnl4&
  hashAlgorithmOID=2.16.840.1.101.3.4.2.1&state=12345678
```

**Sample Request (Credential authorization with authorization_details):**
```
GET https://www.domain.org/oauth2/authorize?
  response_type=code&
  client_id=<OAuth2_client_id>&
  redirect_uri=<OAuth2_redirect_uri>&
  code_challenge=K2-ltc83acc4h0c9w6ESC_rEMTJ3bww-uCHaoeK1t8U&
  code_challenge_method=S256&
  &state=12345678
  &authorization_details=<URL-encoded JSON below>
```

Decoded `authorization_details`:
```json
[
  {
    "type": "credential",
    "signatureQualifier": "eu_eidas_qes",
    "documentDigests": [
      {
        "hash": "sTOgwOm+474gFj0q0x1iSNspKqbcse4IeiqlDg/HWuI=",
        "label": "Example Contract"
      },
      {
        "hash": "HZQzZmMAIWekfGH0/ZKW1nsdt0xg3H6bZYztgsMTLw0=",
        "label": "Example Terms of Service"
      }
    ],
    "hashAlgorithmOID": "2.16.840.1.101.3.4.2.1"
  }
]
```

#### 8.4.3 oauth2/pushed_authorize

The OAuth 2.0 pushed authorization endpoint per IETF Draft draft-ietf-oauth-par. Clients push authorization request parameters directly to the authorization server via an authenticated POST request, receiving a `request_uri` for use with `oauth2/authorize`.

The application sends the same parameters as `oauth2/authorize` (except `request_uri`) via HTTP POST. Client authenticates using the same mechanism as `oauth2/token`.

**Sample Pushed Authorization Request (Service authorization):**
```
POST oauth2/pushed_authorize HTTP/1.1
Host: www.domain.org
Content-Type: application/x-www-form-urlencoded
Authorization: Basic czZCaGRSa3F0Mzo3RmpmcDBaQnIxS3REUmJuZlZkbUl3

response_type=code&
client_id=<OAuth2_client_id>&
redirect_uri=<OAuth2_redirect_uri>&
scope=service&
code_challenge=K2-ltc83acc4h0c9w6ESC_rEMTJ3bww-uCHaoeK1t8U&
code_challenge_method=S256&
lang=en-US&
state=12345678
```

**Sample Pushed Authorization Response:**
```
HTTP/1.1 201 Created
Cache-Control: no-cache, no-store
Content-Type: application/json

{
    "request_uri": "urn:example:bwc4JK-ESC0w8acc191e-Y1LTC2",
    "expires_in": 90
}
```

**Sample Authorization Request (with request_uri):**
```
GET /authorize?client_id=<OAuth2_client_id>
        &request_uri=urn%3Aexample%3Abwc4JK-ESC0w8acc191e-Y1LTC2 HTTP/1.1
    Host: as.example.com
```

#### 8.4.4 oauth2/token

The OAuth 2.0 token endpoint. Obtains an access token using:
- Authorization Code grant
- Client Credentials grant (service authorization only)
- Refresh Token grant (service authorization only)

Parameters SHALL be passed in the request entity-body using `application/x-www-form-urlencoded` with UTF-8 encoding.

**Input — parameters defined in OAuth 2.0:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *grant_type* | REQUIRED | String `authorization_code` \| `client_credentials` \| `refresh_token` | The grant type |
| *code* | REQUIRED Conditional | String | Authorization code; only with `grant_type=authorization_code` |
| *refresh_token* | REQUIRED Conditional | String | Long-lived refresh token; only with `grant_type=refresh_token` and scope `"service"` |
| *client_id* | REQUIRED | String | |
| *client_secret* | REQUIRED Conditional | String | Client secret; if not using Authorization header |
| *client_assertion* | REQUIRED Conditional | String | Assertion for client authentication |
| *client_assertion_type* | REQUIRED Conditional | String | Assertion format URI |
| *redirect_uri* | REQUIRED Conditional | String | Must match value in authorization request |
| *authorization_details* | REQUIRED Conditional | String | Must be present if used in authorization request |

**Input — defined in this specification:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *clientData* | OPTIONAL | String | |

**Output — defined in OAuth 2.0:**

| Attribute | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *access_token* | REQUIRED | String | Short-lived access token; passed as `Authorization: Bearer` |
| *refresh_token* | OPTIONAL | String | Long-lived refresh token; only when scope is `"service"` |
| *token_type* | REQUIRED | String | Default `"Bearer"` |
| *expires_in* | OPTIONAL | Number | Lifetime in seconds; default 3600 (1 hour) |

**Output — defined in this specification:**

| Attribute | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *credentialID* | OPTIONAL | String | Credential ID resolved by AS; present when scope `"credential"` and `signatureQualifier` was used |

**Error cases:**

| Error Case | Status Code | Error | Error Description |
|------------|-------------|-------|-------------------|
| Missing `client_id` | 400 | invalid_request | Missing parameter client_id |
| Missing `grant_type` | 400 | invalid_request | Missing parameter grant_type |
| Invalid `grant_type` | 400 | invalid_request | Invalid parameter grant_type |
| Missing `code` | 400 | invalid_request | Missing parameter code |
| Missing `refresh_token` | 400 | invalid_request | Missing parameter refresh_token |
| Invalid `client_id` | 400 | invalid_request | Invalid parameter client_id |
| Invalid `code` | 400 | invalid_grant | Invalid parameter code |
| `redirect_uri` mismatch | 400 | invalid_grant | redirect_uri parameter does not match |
| Invalid `refresh_token` | 400 | invalid_grant | Invalid parameter refresh_token |
| Refresh token expired | 400 | invalid_grant | Refresh token expired |
| Authorization code invalid/expired | 400 | invalid_grant | Authorization code is invalid or expired |
| Missing `client_secret` and no auth header | 400 / 401 | invalid_request | Client authorization required |
| Invalid `client_secret` | 400 | invalid_request | Invalid parameter client_secret |

**Sample Request (Authorization code flow):**
```
POST oauth2/token HTTP/1.1
Host: www.domain.org
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code&
code=FhkXf9P269L8g&
client_id=<OAuth2_client_id>&
client_secret=<OAuth2_client_secret>&
redirect_uri=<OAuth2_redirect_uri>
```

**Sample Response (service scope):**
```json
{
    "access_token": "4/CKN69L8gdSYp5_pwH3XlFQZ3ndFhkXf9P2_TiHRG-bA",
    "refresh_token": "_TiHRG-bAH3XlFQZ3ndFhkXf9P24/CKN69L8gdSYp5_pw",
    "token_type": "Bearer",
    "expires_in": 3600
}
```

**Sample Response (credential scope):**
```json
{
    "access_token": "3XlFQZ3ndFhkXf9P24/CKN69L8gdSYp5H3XlFQZ3ndFhkXf9P2",
    "token_type": "Bearer",
    "expires_in": 300
}
```

**Sample Response (credential scope with signatureQualifier and AS selected credential):**
```json
{
    "access_token": "3XlFQZ3ndFhkXf9P24/CKN69L8gdSYp5H3XlFQZ3ndFhkXf9P2",
    "token_type": "Bearer",
    "expires_in": 300,
    "credentialID": "GX0112348"
}
```

**Sample Response (credential authorization details with AS selected credential):**
```json
{
    "access_token": "3XlFQZ3ndFhkXf9P24/CKN69L8gdSYp5H3XlFQZ3ndFhkXf9P2",
    "token_type": "Bearer",
    "expires_in": 300,
    "authorization_details": [
      {
        "type": "credential",
        "credentialID": "GX0112348",
        "documentDigests": [
          {
            "hash": "sTOgwOm+474gFj0q0x1iSNspKqbcse4IeiqlDg/HWuI=",
            "label": "Example Contract"
          },
          {
            "hash": "HZQzZmMAIWekfGH0/ZKW1nsdt0xg3H6bZYztgsMTLw0=",
            "label": "Example Terms of Service"
          }
        ],
        "hashAlgorithmOID": "2.16.840.1.101.3.4.2.1"
      }
    ]
}
```

#### 8.4.5 oauth2/revoke

Revoke an access token or refresh token per RFC 7009.

- If `refresh_token`: authorization server SHALL invalidate the refresh token AND SHOULD invalidate all access tokens based on the same grant
- If `access_token`: authorization server SHALL invalidate the access token and SHALL NOT revoke existing refresh tokens

**Input — defined in OAuth 2.0:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *token* | REQUIRED | String | Token to revoke |
| *token_type_hint* | OPTIONAL | String `access_token` \| `refresh_token` | Hint about token type |
| *client_id* | REQUIRED Conditional | String | If no Authorization header |
| *client_secret* | REQUIRED Conditional | String | |
| *client_assertion* | REQUIRED Conditional | String | |
| *client_assertion_type* | REQUIRED Conditional | String | |

**Input — defined in this specification:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *clientData* | OPTIONAL | String | |

**Output:** HTTP 204 No Content (no output values)

**Sample Request:**
```
POST /oauth2/revoke HTTP/1.1
Host: www.domain.org
Content-Type: application/x-www-form-urlencoded

token=_TiHRG-bA-H3XlFQZ3ndFhkXf9P24/FCKN69L8gdSYp5_pw&
token_type_hint=refresh_token&
client_id=<OAuth2_client_id>&
client_secret=<OAuth2_client_secret>&
clientData=12345678
```

**Sample Response:** `HTTP/1.1 204 No Content`

---

### 8.5 Authentication and Authorization for Electronic Seals

#### 8.5.1 Introduction

Electronic seals use certificates of a legal person. They are often created in automated processes for a large number of documents. The CSC API supports two authorization levels:
1. Authorization to access the API
2. Authorization to use the signing credential for seal creation

#### 8.5.2 Service Authorization for Electronic Seals

Methods that avoid human interaction:

- **8.5.2.1 Login/password** — HTTP Basic or Digest authentication; login/password may be linked to signature application or certificate owner
- **8.5.2.2 OAuth with client credentials** — client credentials grant grants access to signing application
- **8.5.2.3 Mutual TLS** — signing server configured to require client certificates; client SHA certificate authenticates the client application/user

#### 8.5.3 Credential Authorization for Electronic Seals

Three strategies for fully automated credential authorization:

1. Automatable authentication means (e.g., PIN)
2. Access token is already sufficient (no additional action needed)
3. Create a SAD for a high but limited number of signatures (SAD creation can be non-fully automated)

---

## 9 Creating a Remote Signature

Strong authentication SHOULD be invoked for each remote signature. Multi-signature sessions may use a single authentication covering multiple signatures.

**Three supported use cases:**

1. Remote signature of a **single hash**
2. Remote signature of **multiple hashes in a single `signHash` call** (batch signing)
3. Remote signature of **multiple hashes across multiple `signHash` calls** within one signing session (use `credentials/extendTransaction` for each additional call)

A RSSP SHALL support at least use case 1. Whether to support use cases 2 and 3 is decided by the RSSP (`multisign` output of `credentials/info` provides the maximum).

For multi-signature transactions, the authorization mechanism SHALL explicitly specify the total number of authorized signatures, and the RSSP SHALL prevent exceeding that number.

---

## 10 Error Handling

### 10.1 HTTP Status Codes

Errors use standard HTTP status codes. The remote service SHALL support:

| Status Code | Description |
|-------------|-------------|
| 200 OK | Successful API request |
| 204 No Content | Successful request with no content returned |
| 302 Found | OAuth 2.0 redirect |
| 400 Bad Request | Unsupported, invalid, or missing required parameters |
| 401 Unauthorized | Bad or expired authorization token |
| 429 Too Many Requests | Rate limiting |
| 500 Internal Server Error | Unexpected condition |
| 501 Not Implemented | Unimplemented method requested |
| 503 Service Unavailable | Temporary overloading or maintenance |

Status codes 429 and 50x apply to the remote service overall and are not specific to any API method.

### 10.2 Error Messages

When an error occurs, the remote service SHALL return the HTTP status code AND a JSON body:

```json
{
    "error": "invalid_request",
    "error_description": "The access token is not valid"
}
```

`error_description` is OPTIONAL but highly RECOMMENDED.

**Table 3 — Predefined Common Error Messages:**

| Error | Error Description |
|-------|-------------------|
| `invalid_request` | Missing required parameter, invalid value, parameter repeated, or otherwise malformed |
| `unauthorized_client` | Client is not authorized to use this method |
| `access_denied` | User, authorization server, or remote service denied the request |
| `unsupported_response_type` | Authorization server does not support obtaining an authorization code using this method |
| `invalid_scope` | Requested scope is invalid, unknown, or malformed |
| `server_error` | Authorization server encountered an unexpected condition |
| `temporarily_unavailable` | Server temporarily unable to handle the request |
| `expired_token` | Access or refresh token is expired or has been revoked |
| `invalid_token` | Token provided is not a valid OAuth access or refresh token |

---

## 11 The Remote Service APIs

**Note:** The `info` method SHALL be implemented. All other methods are OPTIONAL.

**Table 4 — API Methods Summary:**

| API Method | Description |
|------------|-------------|
| `info` | Returns information on the remote service and implemented API methods |
| `auth/login` | Authorize the remote service with HTTP Basic or Digest authentication |
| `auth/revoke` | Revoke the service access token or refresh token |
| `credentials/list` | Returns the list of credentials associated to a user |
| `credentials/info` | Returns information on a credential, its certificate, and authorization mechanisms |
| `credentials/authorize` | Authorize access to the credential for signing |
| `credentials/extendTransaction` | Extend the validity of a multi-signature transaction |
| `credentials/sendOTP` | Start the online OTP mechanism associated to a credential |
| `signatures/signHash` | Calculate a raw digital signature from one or more hash values |
| `signatures/signDoc` | Creates one or more AdES signatures for documents or document digests |
| `signatures/timestamp` | Return a time stamp token for the input hash value |
| `oauth2/authorize*` | Initiate an OAuth 2.0 authorization flow |
| `oauth2/token*` | Obtain an OAuth 2.0 access token or refresh token |
| `oauth2/revoke*` | Revoke an OAuth 2.0 access token or refresh token |

*OAuth2 endpoints are managed by the OAuth2 authorization server, not regular CSC API methods.

---

### 11.1 info

**Description:** Returns information about the remote service and the list of API methods it supports. This method SHALL be implemented by any conforming remote service.

**HTTP method:** `POST /csc/v2/info`

**Input:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *lang* | OPTIONAL | String | Preferred language per RFC 5646 |

**Output:**

| Attribute | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *specs* | REQUIRED | String | Spec version: `"2.0.0.0"` |
| *name* | REQUIRED | String | Commercial name of the remote service (max 255 chars) |
| *logo* | REQUIRED | String | URI to logo image (JPEG or PNG, max 256×256 px) |
| *region* | REQUIRED | String | ISO 3166-1 Alpha-2 country code |
| *lang* | REQUIRED | String | Language of responses per RFC 5646 |
| *description* | REQUIRED | String | Free-form description (max 255 chars) |
| *authType* | REQUIRED | Array of String | Supported service authorization types: `"external"`, `"TLS"`, `"basic"`, `"digest"`, `"oauth2code"`, `"oauth2client"` |
| *oauth2* | REQUIRED Conditional | String | Base URI of OAuth 2.0 authorization server; present if authType includes `"oauth2code"` or `"oauth2client"`, and `oauth2Issuer` is not present |
| *oauth2Issuer* | REQUIRED Conditional | String | Issuer URL of OAuth 2.0 AS (per RFC 8414); present if authType includes OAuth2 and `oauth2` is not present |
| *asynchronousOperationMode* | OPTIONAL | Boolean | `true` if async signing is supported |
| *methods* | REQUIRED | Array of String | Names of all implemented API methods |
| *validationInfo* | OPTIONAL | Boolean | `true` if `validationInfo` response in `signDoc` is supported in non-mandatory cases |
| *signAlgorithms* | REQUIRED | JSON Object | Signature algorithms supported by the RSSP |
| *signature_formats* | REQUIRED | JSON Object | Signature formats supported by the RSSP |
| *conformance_levels* | REQUIRED | Array of String | Signature conformance levels supported |

**signAlgorithms object:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *algos* | REQUIRED | Array of String | List of signature algorithm OIDs supported |
| *algoParams* | REQUIRED Conditional | Array of String | List of signature parameter OIDs |

**signature_formats object:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *formats* | REQUIRED | Array of String | Signature formats: `"C"`, `"X"`, `"P"`, `"J"` |
| *envelope_properties* | REQUIRED Conditional | Array of Array of String | Per-format envelope properties; array length must match `formats` |

**Sample Request:**
```
POST /csc/v2/info HTTP/1.1
Host: service.domain.org
Content-Type: application/json

{}
```

**Sample Response:**
```json
{
    "specs": "2.0.0.0",
    "name": "ACME Trust Services",
    "logo": "https://service.domain.org/images/logo.png",
    "region": "IT",
    "lang": "en-US",
    "description": "An efficient remote signature service",
    "authType": ["basic", "oauth2code"],
    "oauth2": "https://www.domain.org/",
    "methods": ["auth/login", "auth/revoke", "credentials/list",
        "credentials/info", "credentials/authorize",
        "credentials/sendOTP",
        "signatures/signHash"],
    "signAlgorithms": {
      "algos": ["1.2.840.10045.4.3.2", "1.2.840.113549.1.1.1", "1.2.840.113549.1.1.10"]
    },
    "signature_formats": {
      "formats": ["C", "X", "P"],
      "envelope_properties": [["Detached", "Attached", "Parallel"],
                              ["Enveloped", "Enveloping", "Detached"],
                              ["Certification", "Revision"]]
    },
    "conformance_levels": ["Ades-B-B", "Ades-B-T"]
}
```

---

### 11.2 auth/login

**Description:** Obtain a service access token using HTTP Basic or HTTP Digest authentication (RFC 7235). The user's ID and password are passed in the `Authorization` header.

**Note 23:** HTTP Basic Authentication is unsafe and SHOULD NOT be used. The RECOMMENDED mechanism is OAuth 2.0. This method may be deprecated in future releases.

**HTTP method:** `POST /csc/v2/auth/login`

**Input:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *refresh_token* | REQUIRED Conditional | String | Long-lived refresh token from a previous call; alternative to Authorization header |
| *rememberMe* | OPTIONAL | Boolean | If `true`, a `refresh_token` will be returned for re-authentication |
| *clientData* | OPTIONAL | String | |

**Output:**

| Attribute | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *access_token* | REQUIRED | String | Short-lived service access token; used as `Authorization: Bearer` |
| *refresh_token* | OPTIONAL Conditional | String | Long-lived token; returned only if `rememberMe=true` and supported |
| *expires_in* | OPTIONAL | Number | Lifetime in seconds; default 3600 |

**Error cases:**

| Error Case | Status Code | Error | Error Description |
|------------|-------------|-------|-------------------|
| Malformed Authorization header | 401 | invalid_request | Malformed authentication parameter |
| Not in `username:password` format | 400 | invalid_request | Malformed username-password |
| Invalid refresh_token format | 400 | invalid_request | Invalid string parameter: refresh_token |
| Invalid refresh_token value | 400 | invalid_request | Invalid refresh_token |
| Authentication error | 400 | authentication_error | An error occurred during authentication process |

**Sample Request:**
```
POST /csc/v2/auth/login HTTP/1.1
Host: service.domain.org
Authorization: Basic Y2xpZW50OnNlY3JldA==
Content-Type: application/json

{
    "rememberMe": true
}
```

**Sample Response:**
```json
{
    "access_token": "4/CKN69L8gdSYp5_pwH3XlFQZ3ndFhkXf9P2_TiHRG-bA",
    "refresh_token": "_TiHRG-bAH3XlFQZ3ndFhkXf9P24/CKN69L8gdSYp5_pw",
    "expires_in": 3600
}
```

---

### 11.3 auth/revoke

**Description:** Revoke a service access token or refresh token (aligned with OAuth 2.0 revocation per RFC 7009).

- If `refresh_token`: invalidate the refresh token AND all existing access tokens from the same grant
- If `access_token`: invalidate the access token; SHALL NOT revoke refresh tokens

**HTTP method:** `POST /csc/v2/auth/revoke`

**Input:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *token* | REQUIRED | String | Token to revoke |
| *token_type_hint* | OPTIONAL | String `access_token` \| `refresh_token` | Hint about token type |
| *clientData* | OPTIONAL | String | |

**Output:** HTTP 204 No Content

**Sample Request:**
```
POST /csc/v2/auth/revoke HTTP/1.1
Host: service.domain.org
Authorization: Bearer 4/CKN69L8gdSYp5_pwH3XlFQZ3ndFhkXf9P2_TiHRG-bA
Content-Type: application/json

{
    "token": "_TiHRG-bA-H3XlFQZ3ndFhkXf9P24/CKN69L8gdSYp5_pw",
    "token_type_hint": "refresh_token",
    "clientData": "12345678"
}
```

**Sample Response:** `HTTP/1.1 204 No Content`

---

### 11.4 credentials/list

**Description:** Returns the list of credentials associated with a user identifier. Optionally returns credential info, certificate chain, and/or authorization mechanism information.

If the user is authenticated directly by the RSSP, `userID` is implicit and SHALL NOT be specified.

**HTTP method:** `POST /csc/v2/credentials/list`

**Input:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *userID* | REQUIRED Conditional | String | Not present if user-specific service authorization; SHALL NOT be allowed to get credentials of a different user |
| *credentialInfo* | OPTIONAL | Boolean | Return main certificate info; default `false` |
| *certificates* | OPTIONAL Conditional | String `none` \| `single` \| `chain` | Which certificates to return; only if `credentialInfo=true`; default `"single"` |
| *certInfo* | OPTIONAL Conditional | Boolean | Return cert details; only if `credentialInfo=true`; default `false` |
| *authInfo* | OPTIONAL Conditional | Boolean | Return auth mechanism info; only if `credentialInfo=true`; default `false` |
| *onlyValid* | OPTIONAL Conditional | Boolean | Return only credentials usable for signing; default `false` |
| *lang* | OPTIONAL | String | |
| *clientData* | OPTIONAL | String | |

**Output:**

| Attribute | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *credentialIDs* | REQUIRED | Array of String | One or more credential IDs |
| *credentialInfos* | OPTIONAL Conditional | Array of CredentialInfo Object | Full credential info; only if `credentialInfo=true` |
| *onlyValid* | REQUIRED Conditional | Boolean | `true` if `onlyValid` was `true` and RSSP supports this feature |

**CredentialInfo Object attributes:**

| Attribute | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *credentialID* | REQUIRED | String | Credential identifier |
| *description* | OPTIONAL | String | Free-form description (max 255 chars) |
| *signatureQualifier* | OPTIONAL | String | Signature type qualifier (see `signatures/signDoc`) |
| *key/status* | REQUIRED | String `enabled` \| `disabled` | Status of the signing key |
| *key/algo* | REQUIRED | Array of String | OIDs of supported key algorithms |
| *key/len* | REQUIRED | Number | Cryptographic key length in bits |
| *key/curve* | REQUIRED Conditional | String | OID of ECDSA curve; only if `keyAlgo` is ECDSA |
| *cert/status* | OPTIONAL | String `valid` \| `expired` \| `revoked` \| `suspended` | Certificate validity status |
| *cert/certificates* | REQUIRED Conditional | Array of String | Base64-encoded X.509v3 certificates; see `certificates` parameter |
| *cert/issuerDN* | REQUIRED Conditional | String | Issuer DN (UTF-8 per RFC 4514); when `certInfo=true` |
| *cert/serialNumber* | REQUIRED Conditional | String | Hex-encoded serial number; when `certInfo=true` |
| *cert/subjectDN* | REQUIRED Conditional | String | Subject DN (UTF-8 per RFC 4514); when `certInfo=true` |
| *cert/validFrom* | REQUIRED Conditional | String | Validity start in GeneralizedTime format `YYYYMMDDHHmmssZ`; when `certInfo=true` |
| *cert/validTo* | REQUIRED Conditional | String | Validity end in GeneralizedTime format `YYYYMMDDHHmmssZ`; when `certInfo=true` |
| *auth/mode* | REQUIRED | String `explicit` \| `oauth2code` | Authorization mode |
| *auth/expression* | OPTIONAL Conditional | String | Expression combining auth objects (operators: AND, OR, XOR, (, )); only if `auth/mode=explicit` |
| *auth/objects* | REQUIRED Conditional | Array of authentication object types | Available auth object types; only if `auth/mode=explicit` |
| *SCAL* | OPTIONAL | String `1` \| `2` | SCAL level; default `"1"` |
| *multisign* | REQUIRED | Number ≥ 1 | Maximum signatures per authorization |
| *lang* | OPTIONAL | String | |

**Error cases:**

| Error Case | Status Code | Error | Error Description |
|------------|-------------|-------|-------------------|
| Malformed Authorization header | 400 | invalid_request | Malformed authorization header |
| Non-null `userID` in user-specific auth | 400 | invalid_request | userID parameter MUST be null |
| Invalid `userID` format | 400 | invalid_request | Invalid parameter userID |
| Invalid `certificates` parameter | 400 | invalid_request | Invalid parameter certificates |

**Sample Request:**
```
POST /csc/v2/credentials/list HTTP/1.1
Host: service.domain.org
Authorization: Bearer 4/CKN69L8gdSYp5_pwH3XlFQZ3ndFhkXf9P2_TiHRG-bA
Content-Type: application/json

{
    "credentialInfo": true,
    "certificates": "chain",
    "certInfo": true,
    "authInfo": true
}
```

**Sample Response:**
```json
{
    "credentialIDs": [ "GX0112348", "HX0224685" ],
    "credentialInfos": [
        {
            "credentialID": "GX0112348",
            "key": {
                "status": "enabled",
                "algo": [ "1.2.840.113549.1.1.11", "1.2.840.113549.1.1.10" ],
                "len": 2048
            },
            "cert": {
                "status": "valid",
                "certificates": [
                    "<Base64-encoded_X.509_end_entity_certificate>",
                    "<Base64-encoded_X.509_intermediate_CA_certificate>",
                    "<Base64-encoded_X.509_root_CA_certificate>"
                ],
                "issuerDN": "<X.500_issuer_DN_printable_string>",
                "serialNumber": "5AAC41CD8FA22B953640",
                "subjectDN": "<X.500_subject_DN_printable_string>",
                "validFrom": "20200101100000Z",
                "validTo": "20230101095959Z"
            },
            "auth": {
                "mode": "explicit",
                "expression": "PIN AND OTP",
                "objects": [
                    {
                        "type": "Password",
                        "id": "PIN",
                        "format": "N",
                        "label": "PIN",
                        "description": "Please enter the signature PIN"
                    },
                    {
                        "type": "Password",
                        "id": "OTP",
                        "format": "N",
                        "generator": "totp",
                        "label": "Mobile OTP",
                        "description": "Please enter the 6 digit code you received by SMS"
                    }
                ]
            },
            "multisign": 5,
            "lang": "en-US"
        }
    ]
}
```

---

### 11.5 credentials/info

**Description:** Retrieves the credential. Can also return the signing certificate, certificate chain, certificate details, and/or authorization mechanism information.

**HTTP method:** `POST /csc/v2/credentials/info`

**Input:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *credentialID* | REQUIRED | String | Unique credential identifier |
| *certificates* | OPTIONAL | String `none` \| `single` \| `chain` | Per `credentials/list` |
| *certInfo* | OPTIONAL | Boolean | Per `credentials/list` |
| *authInfo* | OPTIONAL | Boolean | Per `credentials/list` |
| *lang* | OPTIONAL | String | Per `info` |
| *clientData* | OPTIONAL | String | |

**Output:** Same attributes as the CredentialInfo Object in `credentials/list` (all fields, without `credentialID` at the top level).

| Attribute | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *description* | OPTIONAL | String | |
| *signatureQualifier* | OPTIONAL | String | |
| *key/status* | REQUIRED | String `enabled` \| `disabled` | |
| *key/algo* | REQUIRED | Array of String | OIDs of supported key algorithms |
| *key/len* | REQUIRED | Number | Key length in bits |
| *key/curve* | REQUIRED Conditional | String | ECDSA curve OID |
| *cert/status* | OPTIONAL | String `valid` \| `expired` \| `revoked` \| `suspended` | |
| *cert/certificates* | REQUIRED Conditional | Array of String | Base64-encoded certificates |
| *cert/issuerDN* | REQUIRED Conditional | String | |
| *cert/serialNumber* | REQUIRED Conditional | String | |
| *cert/subjectDN* | REQUIRED Conditional | String | |
| *cert/validFrom* | REQUIRED Conditional | String | `YYYYMMDDHHmmssZ` |
| *cert/validTo* | REQUIRED Conditional | String | `YYYYMMDDHHmmssZ` |
| *auth/mode* | REQUIRED | String `explicit` \| `oauth2code` | |
| *auth/expression* | OPTIONAL Conditional | String | Only if `auth/mode=explicit` |
| *auth/objects* | REQUIRED Conditional | Array of authentication object types | Only if `auth/mode=explicit` |
| *SCAL* | OPTIONAL | String `1` \| `2` | Default `"1"` |
| *multisign* | REQUIRED | Number ≥ 1 | |
| *lang* | OPTIONAL | String | |

**Error cases:**

| Error Case | Status Code | Error | Error Description |
|------------|-------------|-------|-------------------|
| Malformed Authorization header | 400 | invalid_request | Malformed authorization header |
| Missing/not String `credentialID` | 400 | invalid_request | Missing (or invalid type) string parameter credentialID |
| Invalid `credentialID` | 400 | invalid_request | Invalid parameter credentialID |
| Invalid `certificates` parameter | 400 | invalid_request | Invalid parameter certificates |

**Sample Request:**
```
POST /csc/v2/credentials/info HTTP/1.1
Host: service.domain.org
Authorization: Bearer 4/CKN69L8gdSYp5_pwH3XlFQZ3ndFhkXf9P2_TiHRG-bA
Content-Type: application/json

{
    "credentialID": "GX0112348",
    "certificates": "chain",
    "certInfo": true,
    "authInfo": true
}
```

**Sample Response:**
```json
{
    "key": {
        "status": "enabled",
        "algo": [
            "1.2.840.113549.1.1.1",
            "0.4.0.127.0.7.1.1.4.1.3"
        ],
        "len": 2048
    },
    "cert": {
        "status": "valid",
        "certificates": [
            "<Base64-encoded_X.509_end_entity_certificate>",
            "<Base64-encoded_X.509_intermediate_CA_certificate>",
            "<Base64-encoded_X.509_root_CA_certificate>"
        ],
        "issuerDN": "<X.500_issuer_DN_printable_string>",
        "serialNumber": "5AAC41CD8FA22B953640",
        "subjectDN": "<X.500_subject_DN_printable_string>",
        "validFrom": "20180101100000Z",
        "validTo": "20190101095959Z"
    },
    "auth": {
        "mode": "explicit",
        "expression": "PIN AND OTP",
        "objects": {
            {
                "type": "Password",
                "id": "PIN",
                "format": "N",
                "label": "PIN",
                "description": "Please enter the signature PIN"
            },
            {
                "type": "Password",
                "id": "OTP",
                "format": "N",
                "generator": "totp",
                "label": "Mobile OTP",
                "description": "Please enter the 6 digit code you received by SMS"
            }
        }
    },
    "multisign": 5,
    "lang": "en-US"
}
```

---

### 11.6 credentials/authorize

**Description:** Authorize access to the credential for remote signing. Returns the Signature Activation Data (SAD) required for `signatures/signHash` or `signatures/signDoc`.

This method SHALL be used for `"explicit"` authorization mode and when no authentication objects are required. SHALL NOT be used for `"oauth2code"` mode — use OAuth 2.0 mechanisms instead.

`numSignatures` SHALL indicate the total number of signatures to authorize. For multi-signature transactions where `signHash` is called multiple times, call `credentials/extendTransaction` to obtain a new SAD before the current one expires.

**HTTP method:** `POST /csc/v2/credentials/authorize`

**Input:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *credentialID* | REQUIRED | String | The credential identifier |
| *numSignatures* | REQUIRED | Number | Total number of signatures to authorize |
| *hashes* | REQUIRED Conditional | Array of String | Base64-encoded hash values to bind to the SAD. Required if `SCAL=2`; optional if `SCAL=1` |
| *hashAlgorithmOID* | REQUIRED Conditional | String | OID of hash algorithm |
| *authData* | REQUIRED Conditional | Array of authentication objects | Auth objects per `credentials/info`; required when `auth/mode=explicit` |
| *description* | OPTIONAL | String | Free-form description (max 500 chars) |
| *clientData* | OPTIONAL | String | |

**Output (HTTP 200):**

| Attribute | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *SAD* | REQUIRED | String | Signature Activation Data; used in `signatures/signHash` |
| *expiresIn* | OPTIONAL | Number | SAD lifetime in seconds; default 3600 |

**Output (HTTP 202 — authorization still underway):**

| Attribute | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *handle* | REQUIRED | String | Opaque handle to poll authorization state via `credentials/authorizeCheck` |

**Error cases:**

| Error Case | Status Code | Error | Error Description |
|------------|-------------|-------|-------------------|
| Malformed Authorization header | 400 | invalid_request | Malformed authorization header |
| Missing/not String `credentialID` | 400 | invalid_request | Missing (or invalid type) string parameter credentialID |
| Invalid `credentialID` | 400 | invalid_request | Invalid parameter credentialID |
| Signing key disabled | 400 | invalid_request | The credential identified by credentialID is disabled |
| Missing/not integer `numSignatures` | 400 | invalid_request | Missing (or invalid type) integer parameter numSignatures |
| `numSignatures` < 1 | 400 | invalid_request | Invalid value for parameter numSignatures |
| `numSignatures` > `multisign` | 400 | invalid_request | Numbers of signatures is too high |
| Invalid authentication data | 400 | invalid_authentication_data | The authentication data is invalid |
| Credential locked | 400 | invalid_request | Credential locked |

**Note 29:** If wrong authentication data is provided several times, the RSSP MAY lock the credential.

**Sample Request:**
```
POST /csc/v2/credentials/authorize HTTP/1.1
Host: service.domain.org
Content-Type: application/json
Authorization: Bearer 4/CKN69L8gdSYp5_pwH3XlFQZ3ndFhkXf9P2_TiHRG-bA

{
    "credentialID": "GX0112348",
    "numSignatures": 2,
    "hashes": [
        "sTOgwOm+474gFj0q0x1iSNspKqbcse4IeiqlDg/HWuI=",
        "c1RPZ3dPbSs0NzRnRmowcTB4MWlTTnNwS3FiY3NlNEllaXFsRGcvSFd1ST0="
    ],
    "hashAlgorithmOID": "2.16.840.1.101.3.4.2.1",
    "authData": [
        {
            "id": "PIN",
            "value": "123456"
        },
        {
            "id": "OTP",
            "value": "738496"
        }
    ],
    "clientData": "12345678"
}
```

**Sample Response (200 — SAD issued immediately):**
```json
{
    "SAD": "_TiHRG-bAH3XlFQZ3ndFhkXf9P24/CKN69L8gdSYp5_pw"
}
```

**Sample Response (202 — authorization underway):**
```json
{
    "handle": "878287f37b2bv293bv2bv237bv297bvbv"
}
```

---

### 11.7 credentials/authorizeCheck

**Description:** After a `credentials/authorize` with HTTP 202, use the returned `handle` to poll the authorization state.

**HTTP method:** `POST /csc/v2/credentials/authorizeCheck`

**Input:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *handle* | REQUIRED | String | The handle value returned from `credentials/authorize` |

**Output (HTTP 200):**

| Attribute | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *SAD* | REQUIRED | String | Signature Activation Data |
| *expiresIn* | OPTIONAL | Number | SAD lifetime in seconds; default 3600 |

**Output (HTTP 202 — still underway):**

| Attribute | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *handle* | REQUIRED | String | Opaque handle for next poll |

**Error cases:**

| Error Case | Status Code | Error | Error Description |
|------------|-------------|-------|-------------------|
| Malformed Authorization header | 400 | invalid_request | Malformed authorization header |
| Invalid `handle` | 400 | invalid_request | Invalid parameter handle |
| Invalid authentication data | 400 | invalid_authentication_data | The authentication data is invalid |
| Credential locked | 400 | invalid_request | Credential locked |

**Sample Request:**
```
POST /csc/v2/credentials/authorizeCheck HTTP/1.1
Host: service.domain.org
Content-Type: application/json
Authorization: Bearer 4/CKN69L8gdSYp5_pwH3XlFQZ3ndFhkXf9P2_TiHRG-bA

{
    "handle": "878287f37b2bv293bv2bv237bv297bvbv"
}
```

**Sample Response (200):**
```json
{
    "SAD": "_TiHRG-bAH3XlFQZ3ndFhkXf9P24/CKN69L8gdSYp5_pw"
}
```

**Sample Response (202 — still pending):**
```json
{
    "handle": "878287f37b2bv293bv2bv237bv297bvbv"
}
```

---

### 11.8 credentials/getChallenge

**Description:** Get a challenge for the referenced authentication object (used for ChallengeResponse authentication types).

**HTTP method:** `POST /csc/v2/credentials/getChallenge`

**Input:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *credentialID* | REQUIRED | String | The credential identifier |
| *authObjectID* | REQUIRED | String | The ID of the authentication object needing a challenge |

**Output (HTTP 200):**

| Attribute | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *challenge* | REQUIRED | String | The authentication object challenge |

**Output (HTTP 204):** Challenge sent by out-of-band means; no output values returned.

**Error cases:**

| Error Case | Status Code | Error | Error Description |
|------------|-------------|-------|-------------------|
| Malformed Authorization header | 400 | invalid_request | Malformed authorization header |
| Invalid `credentialID` | 400 | invalid_request | Invalid parameter credentialID |
| Invalid `authObjectID` | 400 | invalid_request | Invalid parameter authObjectID |

**Sample Request (in-band challenge):**
```
POST /csc/v2/credentials/getChallenge HTTP/1.1
Host: service.domain.org
Content-Type: application/json
Authorization: Bearer 4/CKN69L8gdSYp5_pwH3XlFQZ3ndFhkXf9P2_TiHRG-bA

{
    "credentialID": "GX0112348",
    "authObjectID": "fallback question"
}
```

**Sample Response (in-band):**
```json
{
    "challenge": "What's your mother's birth name?"
}
```

**Sample Request (out-of-band):**
```
POST /csc/v2/credentials/getChallenge HTTP/1.1
...
{
    "credentialID": "GX0112348",
    "authObjectID": "OTP"
}
```

**Sample Response (out-of-band):** `HTTP/1.1 204 OK`

---

### 11.9 credentials/extendTransaction

**Description:** Extends the validity of a multi-signature transaction by obtaining a new SAD. Used when `signatures/signHash` is called multiple times with a single credential authorization, or to renew a SAD before it expires.

The RSSP SHALL invalidate the SAD when the authorized number of signatures has been created.

**HTTP method:** `POST /csc/v2/credentials/extendTransaction`

**Input:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *credentialID* | REQUIRED | String | The credential identifier |
| *hashes* | REQUIRED Conditional | Array of String | New hash values to bind to the new SAD; required if `SCAL=2` |
| *hashAlgorithmOID* | REQUIRED Conditional | String | OID of hash algorithm |
| *SAD* | REQUIRED | String | Current unexpired SAD |
| *clientData* | OPTIONAL | String | |

**Note 31:** Used for applying multiple signatures to a PDF document. Since PDF uses nested signatures, hashes for subsequent signatures can only be calculated after the previous signature is created.

**Output:**

| Attribute | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *SAD* | REQUIRED | String | New SAD for the next signature |
| *expiresIn* | OPTIONAL | Number | Lifetime in seconds; default 3600 |

**Sample Request:**
```
POST /csc/v2/credentials/extendTransaction HTTP/1.1
Host: service.domain.org
Content-Type: application/json
Authorization: Bearer 4/CKN69L8gdSYp5_pwH3XlFQZ3ndFhkXf9P2_TiHRG-bA

{
    "credentialID": "GX0112348",
    "hashes": [
        "WlTTnNwS3FiY3NlNEllaXFsRGcvSFd1ST0="
    ],
    "hashAlgorithmOID": "2.16.840.1.101.3.4.2.1",
    "SAD": "_TiHRG-bAH3XlFQZ3ndFhkXf9P24/CKN69L8gdSYp5_pw",
    "clientData": "12345678"
}
```

**Sample Response:**
```json
{
    "SAD": "1/UsHDJ98349h9fgh9348hKKHDkHWVkl/8hsAW5usc8_5="
}
```

---

### 11.10 signatures/signHash

**Description:** Calculate the remote digital signature of one or more hash values.

Requires service and credential authorization. The signing application MUST pass an access token with scope `"service"` or `"credential"` in the `Authorization` header.

- If `auth/mode=explicit`: MUST pass SAD in the `SAD` request parameter
- If `auth/mode=oauth2code` with service scope: MUST pass access token with scope `"credential"` as SAD parameter
- If `auth/mode=oauth2code` with credential scope: SAD is not required in parameters

**HTTP method:** `POST /csc/v2/signatures/signHash`

**Input:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *credentialID* | REQUIRED | String | The credential identifier |
| *SAD* | REQUIRED Conditional | String | SAD from credential authorization; not needed if access token scope is `"credential"` for this credential |
| *hashes* | REQUIRED | Array of String | One or more Base64-encoded raw message digests to sign |
| *hashAlgorithmOID* | REQUIRED Conditional | String | OID of hash algorithm; SHALL be omitted/ignored if implicitly specified by `signAlgo`. Required when `signAlgo=1.2.840.113549.1.1.1` (RSA) |
| *signAlgo* | REQUIRED | String | OID of signature algorithm; must be one of the values from `key/algo` in `credentials/info` |
| *signAlgoParams* | REQUIRED Conditional | String | Base64-encoded DER-encoded ASN.1 signature parameters (e.g., for RSASSA-PSS per RFC 8017) |
| *operationMode* | OPTIONAL | String `"S"` \| `"A"` | `"A"` = asynchronous, `"S"` = synchronous (default) |
| *validity_period* | OPTIONAL Conditional | Integer | Max milliseconds to keep result available; only for async (`operationMode="A"`) |
| *response_uri* | OPTIONAL Conditional | String | URI for server notification on completion; only for async |
| *clientData* | OPTIONAL | String | |

**Output (synchronous, operationMode="S" or omitted):**

| Attribute | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *signatures* | REQUIRED Conditional | Array of String | Base64-encoded signed hashes; same order as input `hashes`; present when `operationMode` is not `"A"` |

**Output (asynchronous, operationMode="A"):**

| Attribute | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *responseID* | REQUIRED Conditional | String | Server-generated unique value identifying the async response; present when `operationMode="A"` |

**Error cases:**

| Error Case | Status Code | Error | Error Description |
|------------|-------------|-------|-------------------|
| Malformed Authorization header | 400 | invalid_request | Malformed authorization header |
| Missing/not String `SAD` | 400 | invalid_request | Missing (or invalid type) string parameter SAD |
| Invalid `SAD` | 400 | invalid_request | Invalid parameter SAD |
| Missing/not String `credentialID` | 400 | invalid_request | Missing (or invalid type) string parameter credentialID |
| Invalid `credentialID` | 400 | invalid_request | Invalid parameter credentialID |
| Missing/not Array `hash` | 400 | invalid_request | Missing (or invalid type) array parameter hash |
| Empty hash array | 400 | invalid_request | Empty hash array |
| Invalid Base64 hash element | 400 | invalid_request | Invalid Base64 hash string parameter |
| Unauthorized hash | 400 | invalid_request | Hash is not authorized by the SAD |
| Missing/not String `signAlgo` | 400 | invalid_request | Missing (or invalid type) string parameter signAlgo |
| Missing/not String `signAlgoParams` | 400 | invalid_request | Missing (or invalid type) string parameter signAlgoParams |
| Missing `hashAlgorithmOID` when required | 400 | invalid_request | Missing (or invalid type) string parameter hashAlgorithmOID |
| Invalid `hashAlgorithmOID` | 400 | invalid_request | Invalid parameter hashAlgorithmOID |
| Invalid `signAlgo` | 400 | invalid_request | Invalid parameter signAlgo |
| Invalid `operationMode` | 400 | invalid_request | Invalid parameter operationMode |
| Invalid `validity_period` | 400 | invalid_request | Invalid parameter validity_period |
| Out of bounds `validity_period` | 400 | invalid_request | Out of bounds parameter validity_period |
| Invalid `response_uri` | 400 | invalid_request | Invalid parameter response_uri |
| Invalid `hashes` element length | 400 | invalid_request | Invalid digest value length |
| Invalid OTP used to generate SAD | 400 | invalid_otp | The OTP is invalid |
| Expired SAD | 400 | invalid_request | SAD expired |
| Expired credential | 400 | invalid_request | Signing certificate is expired |

**Sample Request (synchronous):**
```
POST /csc/v2/signatures/signHash HTTP/1.1
Host: service.domain.org
Content-Type: application/json
Authorization: Bearer 4/CKN69L8gdSYp5_pwH3XlFQZ3ndFhkXf9P2_TiHRG-bA

{
    "credentialID": "GX0112348",
    "SAD": "_TiHRG-bAH3XlFQZ3ndFhkXf9P24/CKN69L8gdSYp5_pw",
    "hashes": [
        "sTOgwOm+474gFj0q0x1iSNspKqbcse4IeiqlDg/HWuI=",
        "c1RPZ3dPbSs0NzRnRmowcTB4MWlTTnNwS3FiY3NlNEllaXFsRGcvSFd1ST0="
    ],
    "hashAlgorithmOID": "2.16.840.1.101.3.4.2.1",
    "signAlgo": "1.2.840.113549.1.1.1",
    "clientData": "12345678"
}
```

**Sample Response (synchronous):**
```json
{
    "signatures": [
        "KedJuTob5gtvYx9qM3k3gm7kbLBwVbEQRl26S2tmXjqNND7MRGtoew==",
        "Idhef7xzgtvYx9qM3k3gm7kbLBwVbE98239S2tm8hUh85KKsfdowel=="
    ]
}
```

**Sample Response (asynchronous):**
```json
{
    "responseID": "158112-652341-khj"
}
```

---

### 11.11 signatures/signDoc

**Description:** Create one or more AdES signatures. Either documents or document digests (SDRs) SHALL be provided. An AdES signature will be created for each input.

Requires service and credential authorization as defined in `signatures/signHash`.

**HTTP method:** `POST /csc/v2/signatures/signDoc`

**Input:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *credentialID* | REQUIRED Conditional | String | Credential identifier. At least one of `credentialID` or `signatureQualifier` SHALL be present |
| *signatureQualifier* | REQUIRED Conditional | String | Signature type identifier. At least one of `credentialID` or `signatureQualifier` SHALL be present |
| *SAD* | REQUIRED Conditional | String | SAD; not needed if access token scope is `"credential"` |
| *documentDigests* | REQUIRED Conditional | JSON Array | Array of document digest objects. Either this or `documents` MUST be present |
| *documents* | REQUIRED Conditional | JSON Array | Array of document objects. Either this or `documentDigests` MUST be present |
| *operationMode* | OPTIONAL | String `"S"` \| `"A"` | Operation mode; default `"S"` |
| *validity_period* | OPTIONAL Conditional | Integer | Milliseconds to keep result; async only |
| *response_uri* | OPTIONAL Conditional | String | Notification URI; async only |
| *clientData* | OPTIONAL | String | |
| *returnValidationInfo* | OPTIONAL | Boolean | If `true`, include `validationInfo` in response; default `false` |

**Predefined signatureQualifier values:**

| Identifier | Description |
|------------|-------------|
| `eu_eidas_qes` | Qualified electronic signature under eIDAS |
| `eu_eidas_aes` | Advanced electronic signature under eIDAS |
| `eu_eidas_aesqc` | Advanced electronic signature with qualified certificate under eIDAS |
| `eu_eidas_qeseal` | Qualified electronic seal under eIDAS |
| `eu_eidas_aeseal` | Advanced electronic seal under eIDAS |
| `eu_eidas_aesealqc` | Advanced electronic seal with qualified certificate under eIDAS |
| `za_ecta_aes` | Advanced electronic signature per South African ECT Act |
| `za_ecta_oes` | Ordinary electronic signature per South African ECT Act |

**signatureQualifier naming convention:** `X_Y_Z` where X = ISO 3166-1 Alpha-2 country code, Y = short name of legislation, Z = short name of signature type.

**documentDigests array — each entry contains:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *hashes* | REQUIRED Conditional | Array of String | Base64-encoded hash values of the documents. If hashes were provided in credential authorization, RSSP SHALL verify each hash matches one from the authorization |
| *hashAlgorithmOID* | REQUIRED Conditional | String | OID of hashing algorithm; may be omitted if implicitly specified by `signAlgo` |
| *signature_format* | REQUIRED | String | `"C"` = CAdES, `"X"` = XAdES, `"P"` = PAdES, `"J"` = JAdES |
| *conformance_level* | OPTIONAL | String | See conformance level values below; default `AdES-B-B` |
| *signAlgo* | REQUIRED | String | OID of signature algorithm |
| *signAlgoParams* | REQUIRED Conditional | String | Base64-encoded DER-encoded ASN.1 signature parameters |
| *signed_props* | OPTIONAL | Array of attribute | List of signed attributes/properties |
| *signed_envelope_property* | OPTIONAL Conditional | String | Envelope property (see values below) |

**documents array — each entry contains:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *document* | REQUIRED | String | Base64-encoded document content to be signed. If hashes were provided in authorization, RSSP SHALL verify the document hash matches |
| *signature_format* | REQUIRED | String | `"C"`, `"X"`, `"P"`, or `"J"` |
| *conformance_level* | OPTIONAL | String | Conformance level |
| *signAlgo* | REQUIRED | String | OID of signature algorithm |
| *signAlgoParams* | REQUIRED Conditional | String | |
| *signed_props* | OPTIONAL | Array of attribute | |
| *signed_envelope_property* | OPTIONAL Conditional | String | |

**conformance_level values:**

| Value | Description |
|-------|-------------|
| `Ades-B-B` | Baseline 191x2 level B signature |
| `Ades-B-T` | Baseline 191x2 level T signature |
| `Ades-B-LT` | Baseline 191x2 level LT signature |
| `Ades-B-LTA` | Baseline 191x2 level LTA signature |
| `Ades-B` | Baseline ETSI level B signature |
| `Ades-T` | Baseline ETSI level T signature |
| `Ades-LT` | Baseline ETSI level LT signature |
| `Ades-LTA` | Baseline ETSI level LTA signature |

**signed_envelope_property values by format:**

| Format | Allowed Values | Default |
|--------|---------------|---------|
| CAdES | Detached, Attached, Parallel | Attached |
| PAdES | Certification, Revision | Certification |
| XAdES | Enveloped, Enveloping, Detached | Enveloped |
| JAdES | Detached, Attached, Parallel | Attached |

**signed_props (attribute object):**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *attribute_name* | REQUIRED | String | Name or OID of the attribute/property to include in the signature |
| *attribute_value* | REQUIRED Conditional | String | Value for the attribute; if not defined, signing server SHALL calculate it |

**Known attribute_name values:**

| attribute_name | attribute_value |
|----------------|-----------------|
| `commitment-type-indication` | Base64-encoding of the attribute per ETSI EN 319 122-1 clause 5.2.3 |
| `content-hints` | Base64-encoding per ETSI EN 319 122-1 clause 5.2.4.1 |
| `mime-type` | Base64-encoding per ETSI EN 319 122-1 clause 5.2.2.2 |
| `signer-location` | Base64-encoding per ETSI EN 319 122-1 clause 5.2.5 |
| `content-time-stamp` | Base64-encoding per ETSI EN 319 122-1 clause 5.2.8 |
| `signer-attributes-v2` | Base64-encoding per ETSI EN 319 122-1 clause 5.2.6.1 |
| `signature-policy-identifier` | Base64-encoding per ETSI EN 319 122-1 clause 5.2.9.1 |
| `content-reference` | Base64-encoding per ETSI EN 319 122-1 clause 5.2.11 |
| `content-identifier` | Base64-encoding per ETSI EN 319 122-1 clause 5.2.12 |
| `Location` | Base64-encoding per ETSI EN 319 142-1 clause 5.3 |
| `Reason` | Base64-encoding per ETSI EN 319 142-1 clause 5.3 |
| `Name` | Base64-encoding per ETSI EN 319 142-1 clause 5.3 |
| `ContactInfo` | Base64-encoding per ETSI EN 319 142-1 clause 5.3 |
| `SignerRoleV2` | Base64-encoding per ETSI EN 319 132-1 clause 5.2.6 |
| `CommitmentTypeIndication` | Base64-encoding per ETSI EN 319 132-1 clause 5.2.3 |
| `SignatureProductionPlaceV2` | Base64-encoding per ETSI EN 319 132-1 clause 5.2.5 |
| `AllDataObjectsTimeStamp` | Base64-encoding per ETSI EN 319 132-1 clause 5.2.8.1 |
| `IndividualDataObjectsTimeStamp` | Base64-encoding per ETSI EN 319 132-1 clause 5.2.8.2 |
| `SignaturePolicyIdentifier` | Base64-encoding per ETSI EN 319 132-1 clause 5.2.9 |

**Output:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *DocumentWithSignature* | REQUIRED Conditional | Array of String | Base64-encoded documents with enveloped signatures; present when enveloped signatures requested and `operationMode` is not `"A"` |
| *SignatureObject* | REQUIRED Conditional | Array of String | Base64-encoded detached signatures; present when detached signatures requested and `operationMode` is not `"A"` |
| *responseID* | REQUIRED Conditional | String | Server-generated unique ID for async polling; present when `operationMode="A"` |
| *validationInfo* | REQUIRED Conditional | JSON Object | Validation data; present if `returnValidationInfo=true` |

**validationInfo object:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *ocsp* | REQUIRED Conditional | Array of String | Base64-encoded DER-encoded OCSP responses; included if at least one OCSP response is needed to validate the signature and timestamps |
| *crl* | REQUIRED Conditional | Array of String | Base64-encoded DER-encoded CRL `CertificateList` structures per RFC 5280; included if at least one CRL is needed |
| *certificates* | REQUIRED Conditional | Array of String | Base64-encoded X.509v3 certificates needed to validate the signature and timestamps but not yet included in the signature |

**Error cases:**

| Error Case | Status Code | Error | Error Description |
|------------|-------------|-------|-------------------|
| Malformed Authorization header | 400 | invalid_request | Malformed authorization header |
| Missing/not String `SAD` | 400 | invalid_request | Missing (or invalid type) string parameter SAD |
| Invalid `SAD` | 400 | invalid_request | Invalid parameter SAD |
| Missing/not String `credentialID` | 400 | invalid_request | Missing (or invalid type) string parameter credentialID |
| Invalid `credentialID` | 400 | invalid_request | Invalid parameter credentialID |
| Invalid object `documentDigests` | 400 | invalid_request | Invalid object parameter documentDigests |
| Invalid array `documents` | 400 | invalid_request | Invalid array parameter documents |
| Empty `documentDigests` and `documents` | 400 | invalid_request | Empty documentDigests and documents objects |
| Both `documentDigests` and `documents` passed | 400 | invalid_request | Both documentDigests and documents parameters passed |
| Invalid Base64 hashes element | 400 | invalid_request | Invalid Base64 hashes string parameter |
| Invalid Base64 documents element | 400 | invalid_request | Invalid Base64 documents string parameter |
| Unauthorized documentDigests or documents | 400 | invalid_request | documentDigests or documents are not authorized by the SAD |
| Missing/not String `signAlgo` | 400 | invalid_request | Missing (or invalid type) string parameter signAlgo |
| Missing/not String `signAlgoParams` | 400 | invalid_request | Missing (or invalid type) string parameter signAlgoParams |
| `hashAlgorithmOID` contradicts `signAlgo` | 400 | invalid_request | String parameter hashAlgorithmOID contradicts with signAlgo parameter |
| Invalid `hashAlgorithmOID` | 400 | invalid_request | Invalid parameter hashAlgorithmOID |
| Invalid `signAlgo` | 400 | invalid_request | Invalid parameter signAlgo |
| Invalid `signature_format` | 400 | invalid_request | Invalid parameter signature_format |
| Missing `signature_format` when `documents` used | 400 | invalid_request | Missing (or invalid type) string parameter signature_format |
| Invalid `conformance_level` | 400 | invalid_request | Invalid parameter conformance_level |
| Invalid `signed_envelope_property` | 400 | invalid_request | Invalid parameter signed_envelope_property |
| Invalid `signed_props` | 400 | invalid_request | Invalid parameter signed_props (list of invalid attributes) |
| Invalid `operationMode` | 400 | invalid_request | Invalid parameter operationMode |
| Invalid `validity_period` | 400 | invalid_request | Invalid parameter validity_period |
| Out of bounds `validity_period` | 400 | invalid_request | Out of bounds parameter validity_period |
| Invalid `response_uri` | 400 | invalid_request | Invalid parameter response_uri |
| Invalid `hashes` element length | 400 | invalid_request | Invalid digest value length |
| Expired SAD | 400 | invalid_request | SAD expired |
| Expired credential | 400 | invalid_request | Signing certificate is expired |
| Document/documentDigest does not match authorized hash | 403 | invalid_hash | Document or documentDigest does not match authorized hash |

**Sample Request (documentDigests):**
```
POST /csc/v2/signatures/signDoc HTTP/1.1
Host: service.domain.org
Content-Type: application/json
Authorization: Bearer 4/CKN69L8gdSYp5_pwH3XlFQZ3ndFhkXf9P2_TiHRG-bA

{
    "credentialID": "GX0112348",
    "SAD": "_TiHRG-bAH3XlFQZ3ndFhkXf9P24/CKN69L8gdSYp5_pw",
    "documentDigests": [
        {
            "hashes": "sTOgwOm+474gFj0q0x1iSNspKqbcse4IeiqlDg/HWuI=",
            "hashAlgorithmOID": "2.16.840.1.101.3.4.2.1",
            "signature_format": "P",
            "conformance_level": "AdES-B-T",
            "signAlgo": "1.2.840.113549.1.1.1"
        },
        {
            "hashes": "HZQzZmMAIWekfGH0/ZKW1nsdt0xg3H6bZYztgsMTLw0=",
            "hashAlgorithmOID": "2.16.840.1.101.3.4.2.1",
            "signature_format": "C",
            "conformance_level": "AdES-B-B",
            "signAlgo": "1.2.840.113549.1.1.1"
        }
    ],
    "documents": [
        {
            "document": "Q2VydGlmaWNhdGVZXJpYWxOdW1iZXI...",
            "signature_format": "P",
            "conformance_level": "AdES-B-T",
            "signAlgo": "1.2.840.113549.1.1.1"
        }
    ],
    "clientData": "12345678"
}
```

**Sample Response (synchronous with documentDigests):**
```json
{
    "DocumentWithSignature": [
        "MILuLgYJKoZIhvcNAQcCoILuHz... ehEeR5ZRi5+WV5T1FpO",
        "MIL4IAYJKoZIhvcNAQcCoIL4...YavvBxkVwJ3dFD9KbCi1qW3TxTI="
    ],
    "SignatureObject": [
        "MIAGCSqAMIACAQExDzANBglghkgBZQMEAgEFADCABgkqhkiG...Ss4rEsQV4AAAAAAAA==",
        "MIAGCSqGSIb3DQEHAqCAMIACAQExDzANBglghkgBZQMEAghki...W7pP1ZJFKuF2YAAAAAA"
    ]
}
```

**Sample Response (with validationInfo):**
```json
{
    "SignatureObject": [
        "MIAGCSqAMIACAQExDzANBglghkgBZQMEAgEFADCABgkqhkiG...Ss4rEsQV4AAAAAAAA=="
    ],
    "validationInfo": {
        "ocsp": ["MIIJg...jSc="],
        "crl": ["MIIC4...X7M="],
        "certificates": ["<Base64-encoded_X.509_certificate>"]
    }
}
```

---

### 11.12 signatures/signPolling

**Description:** Request the server to return the responses for previously sent asynchronous signature requests.

If the user is authenticated directly by the RSSP then `userID` is implicit and SHALL NOT be specified.

**HTTP method:** `POST /csc/v2/signatures/signPolling`

**Input:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *requestID* | REQUIRED | String | Server-generated ID uniquely identifying the asynchronous signature request (returned as `responseID`) |
| *userID* | REQUIRED Conditional | String | Not needed if user-specific service authorization |
| *clientData* | OPTIONAL | String | |

**Output:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *signatures* | REQUIRED Conditional | Array of String | Signed hashes from `signHash`; present when digital signature value(s) creation has been completed |
| *DocumentWithSignature* | REQUIRED Conditional | Array of String | From `signDoc`; present when signature creation has been completed |
| *SignatureObject* | REQUIRED Conditional | Array of String | From `signDoc`; present when signature creation has been completed |

**Error cases:**

| Error Case | Status Code | Error | Error Description |
|------------|-------------|-------|-------------------|
| Previous async request still processing | 202 | accepted_request | The previous async request has been accepted but not yet completed |
| Malformed Authorization header | 400 | invalid_request | Malformed authorization header |
| Missing/not String `requestID` | 400 | invalid_request | Missing (or invalid type) string parameter requestID |
| Invalid `requestID` | 400 | invalid_request | Invalid parameter requestID |
| Non-null `userID` in user-specific auth | 400 | invalid_request | userID parameter SHALL be null |
| Invalid `userID` format | 400 | invalid_request | Invalid parameter "userID" |
| Invalid `clientData` format | 400 | invalid_request | Invalid parameter clientData |

**Sample Request:**
```
POST /csc/v2/signatures/signPolling HTTP/1.1
Host: service.domain.org
Content-Type: application/json
Authorization: Bearer 4/CKN69L8gdSYp5_pwH3XlFQZ3ndFhkXf9P2_TiHRG-bA

{
    "requestID": "158112-652341-khj",
    "clientData": "12345678"
}
```

**Sample Response:**
```json
{
    "signatures": [
        "KedJuTob5gtvYx9qM3k3gm7kbLBwVbEQRl26S2tmXjqNND7MRGtoew==",
        "Idhef7xzgtvYx9qM3k3gm7kbLBwVbE98239S2tm8hUh85KKsfdowel=="
    ]
}
```

---

### 11.13 signatures/timestamp

**Description:** Generate a time-stamp token for the input hash value. The time-stamp token can be generated directly by the RSSP or by a connected Time Stamping Authority. This facilitates long-term validation digital signatures and supports billing operations.

**HTTP method:** `POST /csc/v2/signatures/timestamp`

**Input:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *hash* | REQUIRED | String | Base64-encoded hash value to be time-stamped; used as `MessageImprint.hashedMessage` per RFC 3161 |
| *hashAlgo* | REQUIRED | String | OID of the hash algorithm; used as `MessageImprint.hashAlgorithm` per RFC 3161 |
| *nonce* | OPTIONAL | String | Large random number (hex-encoded string); if included, SHALL be included in the time-stamp token |
| *clientData* | OPTIONAL | String | |

**Output:**

| Parameter | Presence | Value | Description |
|-----------|----------|-------|-------------|
| *timestamp* | REQUIRED | String | Base64-encoded time-stamp token per RFC 3161 (updated by RFC 5816) |

**Error cases:**

| Error Case | Status Code | Error | Error Description |
|------------|-------------|-------|-------------------|
| Malformed Authorization header | 400 | invalid_request | Malformed authorization header |
| Missing/not String `hash` | 400 | invalid_request | Missing (or invalid type) string parameter hash |
| Empty hash | 400 | invalid_request | Empty hash parameter |
| Invalid `hash` length | 400 | invalid_request | Invalid digest value length |
| Invalid Base64 `hash` | 400 | invalid_request | Invalid Base64 hash string parameter |
| Invalid `hashAlgo` | 400 | invalid_request | Invalid parameter hashAlgo |
| Invalid/non-numeric `nonce` | 400 | invalid_request | Invalid parameter nonce |

**Sample Request:**
```
POST /csc/v2/signatures/timestamp HTTP/1.1
Host: service.domain.org
Content-Type: application/json
Authorization: Bearer 4/CKN69L8gdSYp5_pwH3XlFQZ3ndFhkXf9P2_TiHRG-bA

{
    "hash": "sTOgwOm+474gFj0q0x1iSNspKqbcse4IeiqlDg/HWuI=",
    "hashAlgo": "2.16.840.1.101.3.4.2.1",
    "clientData": "12345678"
}
```

**Sample Response:**
```json
{
    "timestamp": "MGwCAQEGCsGAQQB2UBCATAxMA0GCWCGSAFlAwQCAQUAWQCAQUABCCrqnrjH0VxXyQQ1fnFJRx1jjrviTs7/GjKghr2AmluQIIVs5D8OUB4p4YDzIwMTQxMTE5MTEzMjM5WjADAgEBAAgkAnWn2SSIWlXk="
}
```

---

## 12 JSON Schema and OpenAPI Description

A JSON Schema for this specification is available from the Cloud Signature Consortium website. It defines all CSC API parameters and input/output objects.

JSON Schema objects defined:
- `input-info` / `output-info`
- `input-auth-login` / `output-auth-login`
- `input-auth-revoke`
- `input-credentials-list` / `output-credentials-list`
- `input-credentials-info` / `output-credentials-info`
- `input-credentials-authorize` / `output-credentials-authorize`
- `input-credentials-extendTransaction` / `output-credentials-extendTransaction`
- `input-credentials-sendOTP`
- `input-signatures-signhash` / `output-signatures-signhash`
- `input-signatures-timestamp` / `output-signatures-timestamp`

An OpenAPI 3.0 description file is also provided, containing:
1. General API information (version, contact, license)
2. RESTful path URLs and server URL access points
3. Authorization schemas for the CSC API
4. Description of every method including input objects and HTTP responses

The OpenAPI description can be used to auto-generate CSC-compliant server interfaces or client stubs.

---

## 13 Interaction Among Elements and Components

The following sections describe sequence diagrams for the most common operations. Note: sample requests/responses in diagrams are partial representations.

### 13.1 Remote Signing Service Authorization using Basic Authentication

Flow:
1. User provides login information (username/password) to the Signature Application
2. Signature Application → `POST auth/login` with `Authorization: Basic ...` → Remote Service
3. Remote Service returns `{"access_token": "4/CKN69L8gdSYp5bA"}`
4. Application uses token to access protected resources
5. On session close: `POST auth/revoke {"token": "4/CKN69L8gdSYp5bA"}` → token revoked

### 13.2 Remote Signing Service Authorization using OAuth2 Authorization Code Flow

Flow:
1. Signature Application → `GET oauth2/authorize?scope=service&redirect_uri=...` → Authorization Service
2. User logs in and consents
3. Authorization Service → `redirect_uri?code=FhkXf9P269L8g` → Signature Application
4. Signature Application → `POST oauth2/token (grant_type=authorization_code&code=FhkXf9P269L8g)` → returns access token
5. Application uses token for protected resources
6. On close: `POST oauth2/revoke (token=...)` → token revoked

### 13.3 Create a Remote Signature with a Credential Protected by a PIN

Flow:
1. User provides PIN to Signature Application
2. Application → `POST credentials/authorize {"credentialID": "GX0112348", "authData": [{"id": "PIN", "value": "12345678"}]}`
3. Service verifies, returns SAD
4. Application → `POST signatures/signHash {"hash": [...], "SAD": "..."}`
5. Service returns signatures

### 13.4 Create a Remote Signature with a Credential Protected by an "Online" OTP (SMS)

Flow:
1. Application → `POST credentials/getChallenge {"credentialID": "GX0112348", "authObjectID": "OTP"}`
2. Service sends OTP via SMS
3. User enters OTP
4. Application → `POST credentials/authorize {"credentialID": ..., "authData": [{"id": "OTP", "value": "947012"}]}`
5. Service returns SAD
6. Application → `POST signatures/signHash` → returns signature

### 13.5 Create a Remote Signature with a Credential Protected by a Mobile App

Flow:
1. Application → `POST credentials/authorize {"credentialID": ..., "authData": [{"id": "mobile"}]}`
2. Service returns `{"handle": "878287f37b2bv..."}` (HTTP 202)
3. Application polls: `POST credentials/authorizeCheck {"handle": "..."}` (loop while HTTP 202)
4. User receives push notification; user authorizes on mobile
5. Next `authorizeCheck` returns SAD
6. Application → `POST signatures/signHash` → returns signature

### 13.6 Create a Remote Signature with PIN and Online OTP

Flow:
1. Request OTP via `getChallenge`
2. User enters both PIN and OTP
3. `POST credentials/authorize` with `authData: [{"id": "PIN", "value": "12345678"}, {"id": "OTP", "value": "947012"}]`
4. Get SAD → `POST signatures/signHash` → return signature

### 13.7 Create a Remote Signature with OAuth2 Authorization Code Flow

Flow:
1. Application → `GET oauth2/authorize?scope=credential&credentialID=GX0112348`
2. User authorizes
3. Authorization code → `POST oauth2/token` → access token with credential scope
4. Application uses token with scope "credential" as SAD: `POST signatures/signHash {"SAD": "<credential_access_token>"}`
5. Returns signature

### 13.8 Create a Remote Signature with Credential and Signature Qualifier

Flow:
1. Application → `GET oauth2/authorize?scope=credential&signatureQualifier=eu_eidas_qes`
2. AS selects appropriate credential; user authorizes
3. Token returned with `credentialID` included
4. Application → `POST signatures/signDoc {"signatureQualifier": "eu_eidas", "documentDigests": [...]}` using the credential access token

### 13.9 Create a Remote Signature with OAuth2 and Pushed and Rich Authorization Request

Flow:
1. Application → `POST oauth2/pushed_authorize` with `authorization_details` (Rich Authorization Request)
2. Returns `request_uri`
3. Application → `GET oauth2/authorize?request_uri=...`
4. User logs in and consents
5. Exchange code for access token
6. Application → `POST signatures/signDoc` using the access token

### 13.10 Create a Remote Signature with RSSP-Managed Authorization

Flow:
1. Application → `POST credentials/authorize {"credentialID": "GX0112348", "authData": []}` (empty authData)
2. Service authorizes credential use automatically
3. Service returns SAD
4. Application → `POST signatures/signHash` → returns signature

### 13.11 Create Multiple Remote Signatures from a List of Hash Values

Flow:
1. Application → `POST credentials/authorize {"credentialID": ..., "numSignatures": 2, "authData": [{"id": "PIN", "value": "12345678"}]}`
2. Returns SAD
3. Application → `POST signatures/signHash {"hashes": [hash1, hash2, ...], "SAD": "..."}` (multiple hashes in one call)
4. Returns multiple signatures

### 13.12 Create a Remote Multi-Signatures Transaction with a PDF Document

For PDFs that require multiple signatures (PDF uses nested signatures, so each hash depends on the previous signature):

Flow:
1. Application → `POST credentials/authorize {"credentialID": ..., "numSignatures": 2, "hashes": [initialHash], "authData": [PIN]}`
2. Returns SAD 1
3. Application → `POST signatures/signHash {"hash": [hash1], "SAD": SAD_1}` → returns Signature 1
4. Application calculates new hash (including Signature 1)
5. Application → `POST credentials/extendTransaction {"credentialID": ..., "hashes": [hash2], "SAD": SAD_1}`
6. Returns SAD 2
7. Application → `POST signatures/signHash {"hash": [hash2], "SAD": SAD_2}` → returns Signature 2
8. Signed PDF document complete

---

## 14 Change History

### 14.1 Changes Since Version 1.0.4.0

- **Certificate info in credentials/list** — Now allowed to provide detailed certificate information directly in `credentials/list`
- **Asymmetric signing** — Added asynchronous call support (previously proposed in ETSI TS 119 432)
- **Signing of documents** — Added `signatures/signDoc` to create an AdES signature on a hash or full document; supports different signature formats for different documents within one call; also allows requesting JAdES signatures
- **Pushed and Rich Authorization Requests (PAR/RAR)** — Added `oauth2/pushed_authorize` endpoint and support for `authorization_details` parameter
- **Credential-only OAuth authorization** — Allow using only the credential OAuth authorization (without separate service authorization) for signing
- **Electronic seals chapter** — Added section 8.5 on CSC protocol usage for creating electronic seals
- **PAdES revocation info** — When creating a PAdES signature based on document hash, provide revocation information for inclusion in the final signed document
- **Only-valid credentials filter** — Allow requesting only credentials that can be used for signing in `credentials/list`
- **OID-based algorithm expression** — Added explanation for expressing algorithms via OIDs
- **Signature qualifier without credential ID** — Allow requesting signature authorization via OAuth for a specific signature type without specifying the credential ID (useful for short-lived credentials)
- **Explicit credential authorization flexibility** — Explicit credential authorization allows combining different authorization types; makes implicit credential authorization expressible as part of explicit authorization
- **Hash algorithm required** — Each time hash values are provided, the hash algorithm OID must also be provided

---

*End of CSC API v2.0.0.2 Specification*
