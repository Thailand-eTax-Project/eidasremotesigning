# PKCS#12 Keystores Analysis

## Current Implementation Status

### ✅ What's Implemented

The project **supports PKCS#12 keystores** but in a **limited way**. Here's what's currently available:

#### 1. **Database Schema Support**
The `signing_certificates` table includes fields for PKCS#12:
```sql
storage_type VARCHAR(10)      -- Can be "PKCS11", "PKCS12", or "AWSKMS"
keystore_path VARCHAR(500)    -- Path to .p12/.pfx file
keystore_password VARCHAR(255) -- Password to decrypt keystore
certificate_alias VARCHAR(255) -- Alias within the keystore
```

#### 2. **Entity Support**
[SigningCertificate.java](src/main/java/com/wpanther/eidasremotesigning/entity/SigningCertificate.java:34-40) includes:
```java
private String keystorePath;      // Only for PKCS12
private String keystorePassword;  // Only for PKCS12
```

#### 3. **Service Layer Support**
[SigningCertificateService.java](src/main/java/com/wpanther/eidasremotesigning/service/SigningCertificateService.java) has methods:

**Loading Certificates:**
```java
public X509Certificate loadCertificateFromKeystore(SigningCertificate cert) throws Exception {
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (FileInputStream fis = new FileInputStream(cert.getKeystorePath())) {
        keyStore.load(fis, cert.getKeystorePassword().toCharArray());
        return (X509Certificate) keyStore.getCertificate(cert.getCertificateAlias());
    }
}
```

**Getting Private Keys:**
```java
// In getPrivateKey() method - supports PKCS12
KeyStore keyStore = KeyStore.getInstance("PKCS12");
try (FileInputStream fis = new FileInputStream(cert.getKeystorePath())) {
    keyStore.load(fis, cert.getKeystorePassword().toCharArray());
    PrivateKey privateKey = (PrivateKey) keyStore.getKey(
        cert.getCertificateAlias(),
        cert.getKeystorePassword().toCharArray());
    return privateKey;
}
```

#### 4. **Signing Operations**
[CSCSignatureService.java](src/main/java/com/wpanther/eidasremotesigning/service/CSCSignatureService.java:221-240) supports PKCS#12:
```java
if ("PKCS11".equals(certEntity.getStorageType())) {
    signature = Signature.getInstance(signatureAlgorithm, certEntity.getProviderName());
} else if ("AWSKMS".equals(certEntity.getStorageType())) {
    // KMS signing
} else {
    // PKCS#12 uses default provider
    signature = Signature.getInstance(signatureAlgorithm);
}
```

---

## ❌ What's Missing

### Critical Gap: No API to Upload/Create PKCS#12 Certificates

The project can **USE** PKCS#12 keystores but has **NO WAY TO CREATE THEM** via the API.

Current situation:
- ✅ Can read from existing PKCS#12 files
- ✅ Can sign with PKCS#12 keys
- ❌ **Cannot upload PKCS#12 files via API**
- ❌ **Cannot create PKCS#12 keystores programmatically**
- ❌ **No REST endpoint for PKCS#12 management**

### Comparison with Other Storage Types

| Feature | PKCS#11 | PKCS#12 | AWS KMS |
|---------|---------|---------|---------|
| **List Keys** | ✅ `/api/v1/certificates/pkcs11/list` | ❌ No endpoint | ✅ `/api/v1/certificates/aws-kms/keys` |
| **Associate** | ✅ `/api/v1/certificates/pkcs11/associate` | ❌ No endpoint | ✅ `/api/v1/certificates/aws-kms/associate` |
| **Use in Signing** | ✅ Works | ✅ Works | ✅ Works |
| **Certificate Loading** | ✅ From HSM | ✅ From file | ✅ From database |
| **Private Key Access** | ✅ From HSM | ✅ From file | ✅ Via KMS API |

---

## 🔍 How PKCS#12 Currently Works

### Scenario: You manually created a PKCS#12 file

If you manually:
1. Generated a keystore: `keytool -genkeypair -alias mykey -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore mykeystore.p12`
2. Placed it on the server: `/app/keystores/client123/mykeystore.p12`
3. Manually inserted into database:
```sql
INSERT INTO signing_certificates VALUES (
    'cert-123',
    'My PKCS12 Cert',
    'PKCS12',
    'mykey',
    '/app/keystores/client123/mykeystore.p12',
    'keystorePassword',
    NULL,  -- provider_name (not needed for PKCS12)
    NULL,  -- slot_id (not needed for PKCS12)
    NULL,  -- kms_key_id
    NULL,  -- aws_region
    NULL,  -- certificate_data
    TRUE,
    'client-id',
    NOW(),
    NULL
);
```

Then **signing would work**:
```bash
curl -X POST http://localhost:9000/csc/v2/signatures/signDocument \
  -H "Authorization: Bearer TOKEN" \
  -d '{
    "clientId": "client-id",
    "credentialID": "cert-123",
    "documentDigest": "BASE64_HASH",
    "hashAlgo": "SHA-256"
  }'
```

---

## 💡 Proposed Solution

You need to implement PKCS#12 management endpoints. Here are two approaches:

### Approach 1: Upload Existing PKCS#12 Files (Recommended for Testing)

Create an endpoint to upload `.p12`/`.pfx` files:

```java
@PostMapping("/api/v1/certificates/pkcs12/upload")
public ResponseEntity<CertificateDetailResponse> uploadPKCS12(
    @RequestParam("file") MultipartFile file,
    @RequestParam("password") String password,
    @RequestParam("alias") String alias,
    @RequestParam(required = false) String description
) {
    // 1. Save file to disk
    // 2. Validate keystore and password
    // 3. Extract certificate info
    // 4. Create SigningCertificate entity
    // 5. Return response
}
```

**Pros:**
- ✅ Simple implementation
- ✅ Works with existing keystores
- ✅ Good for development/testing

**Cons:**
- ⚠️ Security concern: stores password in database
- ⚠️ File management overhead
- ⚠️ Not suitable for production

### Approach 2: Generate PKCS#12 On-the-Fly (Better for Production)

Create an endpoint to generate new keypairs:

```java
@PostMapping("/api/v1/certificates/pkcs12/generate")
public ResponseEntity<CertificateDetailResponse> generatePKCS12(
    @RequestBody PKCS12GenerateRequest request
) {
    // 1. Generate RSA/EC keypair
    // 2. Create self-signed certificate or CSR
    // 3. Store in PKCS12 keystore
    // 4. Save to filesystem
    // 5. Create database entry
    // 6. Return certificate info
}
```

**Pros:**
- ✅ No need to upload files
- ✅ Can integrate with CA for real certificates
- ✅ Better for automated workflows

**Cons:**
- ⚠️ More complex implementation
- ⚠️ Still has file storage concerns
- ⚠️ Password management challenges

### Approach 3: Hybrid - Use AWS Secrets Manager for PKCS#12 (Best for Production)

Store PKCS#12 files and passwords in AWS Secrets Manager:

```java
@PostMapping("/api/v1/certificates/pkcs12/create")
public ResponseEntity<CertificateDetailResponse> createPKCS12WithSecretsManager(
    @RequestBody PKCS12CreateRequest request
) {
    // 1. Generate keypair
    // 2. Create PKCS12 keystore in memory
    // 3. Store in AWS Secrets Manager
    // 4. Store reference in database (no password!)
    // 5. Return certificate info
}
```

**Pros:**
- ✅ Secure password management
- ✅ No local file storage
- ✅ Encrypted at rest
- ✅ IAM-controlled access
- ✅ Automatic rotation support

**Cons:**
- ⚠️ Requires AWS infrastructure
- ⚠️ Additional cost (~$0.40/secret/month)

---

## 🔐 Security Considerations

### Current Issues with PKCS#12 Implementation

1. **Password Storage** - Currently stored in plain text in database
   ```java
   private String keystorePassword;  // ⚠️ SECURITY RISK
   ```

2. **File System Access** - Anyone with filesystem access can copy keystores

3. **No Encryption at Rest** - Keystores are encrypted by password, but password is in database

### Recommendations

#### For Development/Testing:
- ✅ Current implementation is acceptable
- ✅ Use strong passwords
- ✅ Restrict database access

#### For Production:
- ⚠️ **DO NOT use PKCS#12** - Use PKCS#11 HSM or AWS KMS instead
- If you must use PKCS#12:
  - Encrypt passwords before storing (e.g., using Jasypt)
  - Use AWS Secrets Manager or HashiCorp Vault
  - Implement file-level encryption
  - Use separate storage volumes with encryption
  - Implement strict access controls

---

## 📊 Storage Type Comparison for Production

| Aspect | PKCS#11 HSM | AWS KMS | PKCS#12 |
|--------|-------------|---------|---------|
| **Security** | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐ |
| **Keys Extractable** | ❌ No | ❌ No | ✅ Yes (bad!) |
| **Compliance** | ✅ eIDAS, FIPS 140-2 Level 3+ | ✅ FIPS 140-2 Level 2 | ❌ Not qualified |
| **Cost** | $$$ (hardware) | $$ (pay-per-use) | $ (free, but risky) |
| **Ease of Setup** | Hard | Medium | Easy |
| **Production Ready** | ✅ Yes | ✅ Yes | ❌ No |
| **Audit Trail** | Limited | ✅ CloudTrail | ❌ Minimal |

---

## 🎯 Recommendations

### For Your Project:

1. **Development/Testing Environment:**
   - Keep PKCS#12 support as-is
   - Manually create keystores for testing
   - Document the manual process

2. **Staging Environment:**
   - Implement PKCS#12 upload endpoint
   - Add password encryption
   - Test with AWS Secrets Manager

3. **Production Environment:**
   - **Primary: Use AWS KMS** (already implemented!)
   - **Backup: Use PKCS#11 HSM** (already implemented!)
   - **Avoid: PKCS#12** unless absolutely necessary

---

## 📝 Implementation Checklist

If you want to add full PKCS#12 API support:

- [ ] Create `PKCS12UploadRequest` DTO
- [ ] Create `PKCS12GenerateRequest` DTO
- [ ] Add `uploadPKCS12()` method to `SigningCertificateService`
- [ ] Add `generatePKCS12()` method to `SigningCertificateService`
- [ ] Create REST controller endpoints
- [ ] Add file upload handling (MultipartFile)
- [ ] Implement keystore validation
- [ ] Add password encryption (Jasypt or AWS Secrets Manager)
- [ ] Implement file cleanup on certificate deletion
- [ ] Add unit tests
- [ ] Add integration tests
- [ ] Update documentation

---

## 🔗 Current Workflow (Manual PKCS#12)

Since there's no API, here's how to use PKCS#12 currently:

### Step 1: Generate Keystore
```bash
keytool -genkeypair \
  -alias signing-key \
  -keyalg RSA \
  -keysize 2048 \
  -storetype PKCS12 \
  -keystore signing.p12 \
  -storepass changeit \
  -validity 365 \
  -dname "CN=Test Signer, O=Test Org, C=US"
```

### Step 2: Place on Server
```bash
mkdir -p /app/keystores/client123
cp signing.p12 /app/keystores/client123/
chmod 600 /app/keystores/client123/signing.p12
```

### Step 3: Insert into Database
```sql
INSERT INTO signing_certificates (
    id, description, storage_type, certificate_alias,
    keystore_path, keystore_password, active, client_id, created_at
) VALUES (
    'pkcs12-cert-1',
    'Test PKCS12 Certificate',
    'PKCS12',
    'signing-key',
    '/app/keystores/client123/signing.p12',
    'changeit',
    TRUE,
    'your-client-id',
    NOW()
);
```

### Step 4: Use in Signing
```bash
curl -X POST http://localhost:9000/csc/v2/signatures/signDocument \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "your-client-id",
    "credentialID": "pkcs12-cert-1",
    "documentDigest": "BASE64_HASH",
    "hashAlgo": "SHA-256"
  }'
```

---

## 📚 Conclusion

### Summary:
- ✅ **PKCS#12 support EXISTS** in the codebase
- ✅ **Signing with PKCS#12 WORKS**
- ❌ **No API for PKCS#12 management**
- ⚠️ **Security concerns for production**

### Best Practice:
**Use AWS KMS for production** (already implemented!). PKCS#12 is best kept for:
- Local development
- Testing
- Legacy migration scenarios
- Quick prototypes

The AWS KMS integration you now have is **far superior** for production use.
