# Timestamp Authority (TSP) Configuration Analysis

## 📋 Overview

Your eIDAS Remote Signing Service includes **RFC 3161 compliant timestamping** functionality for creating trusted timestamps that prove document existence at a specific point in time.

---

## 🔧 Current Configuration

### Location
**File:** `src/main/resources/application.yml`

```yaml
app:
  tsp:
    url: ${TSP_URL:http://tsa.belgium.be/connect}
```

### Current TSP Server
- **URL:** `http://tsa.belgium.be/connect`
- **Provider:** Belgium Federal Government Time Stamping Authority
- **Protocol:** RFC 3161 (Time-Stamp Protocol)
- **Status:** ⚠️ **HTTP (not HTTPS)** - Consider using HTTPS in production

---

## 🎯 How Timestamping Works

### Architecture

```
┌─────────────────────────────────────────────────┐
│ Your Application                                │
│                                                 │
│  ┌────────────────────────────────────────┐   │
│  │ CSCSignatureService                    │   │
│  │                                         │   │
│  │  createTimestamp()                     │   │
│  │  createTimestampData()                 │   │
│  └──────────────┬──────────────────────────┘   │
│                 │                               │
│  ┌──────────────▼──────────────────────────┐   │
│  │ EU DSS OnlineTSPSource                  │   │
│  │ - Builds TSP request                    │   │
│  │ - Sends to TSP server                   │   │
│  │ - Parses TSP response                   │   │
│  └──────────────┬──────────────────────────┘   │
└─────────────────┼───────────────────────────────┘
                  │
                  │ HTTP/HTTPS
                  │
      ┌───────────▼────────────┐
      │                        │
      │ Time Stamp Authority   │
      │ (tsa.belgium.be)       │
      │                        │
      │ - Receives hash        │
      │ - Signs with TSA cert  │
      │ - Returns token        │
      └────────────────────────┘
```

### Workflow

1. **Application calculates document hash** (SHA-256/384/512)
2. **Sends hash to TSP server** via RFC 3161 protocol
3. **TSP server adds timestamp** and signs with its certificate
4. **Returns timestamp token** (ASN.1 encoded)
5. **Application includes token** in signature response

---

## 📝 Implementation Details

### Code Location
**File:** [CSCSignatureService.java:333-420](src/main/java/com/wpanther/eidasremotesigning/service/CSCSignatureService.java)

### Key Methods

#### 1. **createTimestamp()** - Public API Endpoint
```java
public CSCTimestampResponse createTimestamp(CSCTimestampRequest request) {
    // 1. Extract digest from request
    // 2. Create TSP source (OnlineTSPSource)
    // 3. Get timestamp token
    // 4. Return response with token
}
```

**API Endpoint:** `POST /csc/v2/signatures/timestamp`

#### 2. **createTimestampData()** - Internal Helper
```java
private Map<String, Object> createTimestampData(byte[] digest, String hashAlgo) {
    // Used internally when signing with serverTimestamp=true
    // Returns timestamp data to include in signature response
}
```

### Supported Hash Algorithms

| Algorithm | Status | RFC Support |
|-----------|--------|-------------|
| SHA-256 | ✅ Supported | Yes |
| SHA-384 | ✅ Supported | Yes |
| SHA-512 | ✅ Supported | Yes |
| SHA-1 | ❌ Not supported | Deprecated |
| MD5 | ❌ Not supported | Insecure |

---

## 🌐 Available TSP Servers

### Production-Ready Options

#### 1. **Belgium TSA** (Current)
```yaml
app.tsp.url: http://tsa.belgium.be/connect
```
- ✅ Free
- ✅ Public
- ⚠️ HTTP only
- 🌍 European

#### 2. **Freetsaserver** (Recommended for Production)
```yaml
app.tsp.url: https://freetsa.org/tsr
```
- ✅ Free
- ✅ HTTPS
- ✅ RFC 3161 compliant
- 🌍 Global

#### 3. **DigiCert TSA** (Commercial)
```yaml
app.tsp.url: https://timestamp.digicert.com
```
- 💰 Commercial
- ✅ HTTPS
- ✅ High reliability
- ✅ SLA guaranteed

#### 4. **Sectigo TSA** (Commercial)
```yaml
app.tsp.url: http://timestamp.sectigo.com
```
- 💰 Commercial
- ✅ Widely trusted
- ⚠️ HTTP (also has HTTPS endpoint)

#### 5. **GlobalSign TSA** (Commercial)
```yaml
app.tsp.url: http://timestamp.globalsign.com/scripts/timstamp.dll
```
- 💰 Commercial
- ✅ Enterprise-grade
- ⚠️ HTTP

---

## 🔐 Security Considerations

### Current Setup Issues

⚠️ **Using HTTP instead of HTTPS**
```yaml
url: http://tsa.belgium.be/connect  # ⚠️ Not encrypted in transit
```

**Risks:**
- Man-in-the-middle attacks
- Token tampering (though signature would fail)
- Request/response visibility

**Recommendation:** Use HTTPS-based TSP

### Token Validation

✅ **Good:** The returned timestamp token is **cryptographically signed** by the TSA
- Even if intercepted, cannot be forged
- Signature verification ensures integrity

⚠️ **However:** The request/response is visible in HTTP

---

## 📊 Configuration Options

### Option 1: Environment Variable (Recommended)
```bash
export TSP_URL=https://freetsa.org/tsr
```

### Option 2: System Property
```bash
java -jar app.jar -Dapp.tsp.url=https://freetsa.org/tsr
```

### Option 3: application.yml
```yaml
app:
  tsp:
    url: https://freetsa.org/tsr
```

### Option 4: Production application.yml
```yaml
app:
  tsp:
    url: ${TSP_URL:https://freetsa.org/tsr}
    # Optional: Add timeout settings
    timeout: 10000  # 10 seconds
    retry-attempts: 3
```

---

## 🧪 Testing Timestamp Service

### Test TSP Connectivity

```bash
# Test if TSP server is reachable
curl -I http://tsa.belgium.be/connect

# Expected: HTTP 200 or 405 (Method Not Allowed for GET)
```

### Test via API

```bash
# 1. Get OAuth2 token
TOKEN="your_access_token"

# 2. Create test hash
TEST_HASH=$(echo -n "Hello World" | sha256sum | awk '{print $1}')
HASH_BASE64=$(echo -n $TEST_HASH | xxd -r -p | base64)

# 3. Request timestamp
curl -X POST http://localhost:9000/csc/v2/signatures/timestamp \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{
    \"clientId\": \"your_client_id\",
    \"documentDigest\": \"$HASH_BASE64\",
    \"hashAlgo\": \"SHA-256\"
  }"
```

**Expected Response:**
```json
{
  "timestampToken": "MIIGRgYJKoZIhvcNAQcCoII...",
  "timestampDigest": "base64_hash",
  "timestampGenerationTime": 1730000000000
}
```

---

## 🔍 Timestamp Token Structure

The returned token is an **ASN.1 encoded RFC 3161 TimeStampToken**:

```
TimeStampToken ::= SEQUENCE {
  statusInfo      PKIStatusInfo,
  timeStampToken  TimeStampToken OPTIONAL
}

TimeStampToken ::= ContentInfo {
  contentType = id-signedData,
  content = SignedData {
    version = 3,
    digestAlgorithms = {sha256},
    encapContentInfo = {
      eContentType = id-ct-TSTInfo,
      eContent = TSTInfo {
        version = 1,
        policy = TSA policy OID,
        messageImprint = hash,
        serialNumber = unique ID,
        genTime = timestamp,
        accuracy = accuracy info
      }
    },
    certificates = [TSA certificate],
    signerInfos = [TSA signature]
  }
}
```

---

## 🚀 Usage in Signing Operations

### Automatic Timestamp (Option 1)

Request timestamp during signing:

```bash
curl -X POST http://localhost:9000/csc/v2/signatures/signDocument \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "your_client_id",
    "credentialID": "your_credential_id",
    "documentDigest": "base64_hash",
    "hashAlgo": "SHA-256",
    "signatureOptions": {
      "serverTimestamp": "true"
    }
  }'
```

**Response includes timestamp:**
```json
{
  "transactionID": "tx-123",
  "signatureAlgorithm": "SHA256withRSA",
  "timestampData": {
    "timestamp": "MIIGRgYJKoZI...",
    "timestampGenerationTime": 1730000000000
  }
}
```

### Manual Timestamp (Option 2)

Request timestamp separately:

```bash
curl -X POST http://localhost:9000/csc/v2/signatures/timestamp \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "clientId": "your_client_id",
    "documentDigest": "base64_hash",
    "hashAlgo": "SHA-256"
  }'
```

---

## ⚙️ Advanced Configuration

### Custom Timeout and Retry

If you need custom timeout/retry logic, extend the configuration:

```java
@Configuration
public class TSPConfig {

    @Value("${app.tsp.url}")
    private String tspUrl;

    @Value("${app.tsp.timeout:10000}")
    private int timeout;

    @Bean
    public OnlineTSPSource tspSource() {
        OnlineTSPSource tspSource = new OnlineTSPSource(tspUrl);
        // Configure timeout if needed
        return tspSource;
    }
}
```

### Multiple TSP Servers (Fallback)

For high availability:

```yaml
app:
  tsp:
    primary-url: https://freetsa.org/tsr
    fallback-urls:
      - http://tsa.belgium.be/connect
      - https://timestamp.digicert.com
```

---

## 📚 Standards and Compliance

### RFC 3161 Compliance
✅ Your implementation follows RFC 3161 (Time-Stamp Protocol)

### eIDAS Compliance
✅ Timestamps are part of eIDAS advanced signatures (AdES)

### Long-Term Validation
- Timestamps enable **long-term signature validation** (LTV)
- Required for PAdES-LTA and XAdES-LTA formats
- Proves signature existed before certificate expiration

---

## 🔄 Recommended Configuration Changes

### For Development
```yaml
app:
  tsp:
    url: ${TSP_URL:http://tsa.belgium.be/connect}  # Current - OK for dev
```

### For Production
```yaml
app:
  tsp:
    url: ${TSP_URL:https://freetsa.org/tsr}  # HTTPS, free, reliable
    # Or use commercial TSA for SLA
```

### Environment-Specific

```bash
# Development
export TSP_URL=http://tsa.belgium.be/connect

# Staging
export TSP_URL=https://freetsa.org/tsr

# Production
export TSP_URL=https://timestamp.digicert.com
```

---

## 🛠️ Troubleshooting

### Error: "Failed to create timestamp"

**Possible Causes:**
1. TSP server is down
2. Network connectivity issues
3. Firewall blocking TSP port
4. Invalid hash algorithm

**Solutions:**
```bash
# Test TSP connectivity
curl -v http://tsa.belgium.be/connect

# Check application logs
tail -f logs/application.log | grep -i timestamp

# Try alternative TSP server
export TSP_URL=https://freetsa.org/tsr
```

### Error: "Connection timeout"

**Solution:**
```yaml
# Increase timeout (if configurable)
app:
  tsp:
    url: https://freetsa.org/tsr
    timeout: 30000  # 30 seconds
```

---

## 📊 Performance Considerations

### Typical Response Times

| TSP Server | Average Latency |
|------------|-----------------|
| Belgium TSA | 200-500ms |
| FreeTSA | 300-800ms |
| DigiCert | 100-300ms |
| GlobalSign | 150-400ms |

### Impact on Signing Operations

When `serverTimestamp: true`:
- Adds **200-800ms** to signing operation
- Depends on TSP server location and load
- Consider **async timestamping** for high-volume operations

---

## ✅ Recommendations Summary

1. **Switch to HTTPS TSP** for production:
   ```yaml
   url: https://freetsa.org/tsr
   ```

2. **Add monitoring** for TSP failures

3. **Implement fallback** to multiple TSP servers

4. **Consider commercial TSA** for SLA requirements

5. **Test TSP connectivity** during deployment

6. **Cache timestamp policies** if using multiple TSAs

---

## 🔗 References

- [RFC 3161 - Time-Stamp Protocol](https://www.ietf.org/rfc/rfc3161.txt)
- [Belgium TSA](https://tsa.belgium.be/)
- [FreeTSA](https://freetsa.org/)
- [EU DSS Documentation](https://ec.europa.eu/digital-building-blocks/DSS/webapp-demo/doc/dss-documentation.html)

---

## 🎓 Summary

**Current Configuration:**
- ✅ TSP is **configured and working**
- ⚠️ Using **HTTP** (consider switching to HTTPS)
- ✅ Supports SHA-256/384/512
- ✅ RFC 3161 compliant
- ✅ Integrated with signing operations

**Key Points:**
- Timestamp Authority URL: `http://tsa.belgium.be/connect`
- Configurable via: Environment variable `TSP_URL`
- Used for: Long-term signature validation
- Change requires: Only configuration update (no code changes)
