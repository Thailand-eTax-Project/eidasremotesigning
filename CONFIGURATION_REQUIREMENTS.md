# Configuration Requirements - What Do You Actually Need?

## 🎯 Quick Answer

**You do NOT need to configure all three storage types.** Choose ONE based on your needs:

- 🚀 **AWS KMS** - For cloud deployment (recommended for production)
- 🔧 **PKCS#11** - For on-premise HSM
- 💻 **PKCS#12** - For development/testing (not recommended for production)

---

## ✅ Minimum Requirements to Start

### To Run the Application:

```bash
# These are the ONLY hard requirements:
✅ Java 17 or higher
✅ Maven 3.6+
✅ Nothing else!
```

**The application will start successfully without any key storage configured.**

---

## 🎛️ Configuration Matrix

### What You Need for Different Scenarios:

| Scenario | Required Configuration | Optional |
|----------|----------------------|----------|
| **Just start the app** | None | All storage types |
| **Development/Testing** | PKCS#12 (manual) | AWS KMS, PKCS#11 |
| **Cloud Production** | AWS KMS | Everything else |
| **On-Premise Production** | PKCS#11 HSM | Everything else |

---

## 📋 Detailed Breakdown

### Scenario 1: **Just Want to Run the Application**

**Required:**
```yaml
# Nothing! Default configuration works
```

**What happens:**
- ✅ Application starts on port 9000
- ✅ OAuth2 endpoints work
- ✅ Client registration works
- ✅ Database initializes (H2 in-memory)
- ❌ Cannot sign documents (no certificates configured)

**Start command:**
```bash
mvn clean package
java -jar target/eidasremotesigning-0.0.1-SNAPSHOT.jar
```

---

### Scenario 2: **Development with AWS KMS** ⭐ Recommended

**Required:**
```yaml
app:
  aws:
    kms:
      enabled: true
      region: us-east-1
      use-default-credentials: true
```

**Or via environment:**
```bash
export AWS_KMS_ENABLED=true
export AWS_REGION=us-east-1
```

**Also need:**
- AWS account with KMS access
- AWS CLI configured OR IAM role

**What you DON'T need:**
- ❌ PKCS#11 hardware
- ❌ PKCS#12 keystores
- ❌ HSM installation

**Result:**
- ✅ Full signing capability
- ✅ Production-ready security
- ✅ Easy to test

---

### Scenario 3: **On-Premise with PKCS#11 HSM**

**Required:**
```yaml
app:
  pkcs11:
    provider: SunPKCS11
    name: SoftHSM
    library-path: /usr/lib/softhsm/libsofthsm2.so
    config-file: /path/to/pkcs11.cfg
```

**Also need:**
- Hardware HSM OR SoftHSM installed
- PKCS#11 library (.so/.dll file)
- HSM initialized with keys

**What you DON'T need:**
- ❌ AWS account
- ❌ AWS KMS enabled
- ❌ PKCS#12 keystores

**Result:**
- ✅ Full signing capability
- ✅ Production-ready with HSM
- ⚠️ Requires hardware setup

---

### Scenario 4: **Quick Testing with PKCS#12**

**Required:**
- Manually create PKCS#12 keystore
- Manually insert database record
- No configuration changes needed

**Also need:**
```bash
# Create keystore
keytool -genkeypair -keyalg RSA -keysize 2048 \
  -storetype PKCS12 -keystore test.p12 -storepass changeit

# Place file on server
mkdir -p /app/keystores
cp test.p12 /app/keystores/

# Insert into database manually
```

**What you DON'T need:**
- ❌ AWS account
- ❌ HSM hardware
- ❌ Any configuration changes

**Result:**
- ✅ Can sign documents
- ⚠️ Not production-ready
- ⚠️ Manual setup required

---

## 🔧 Default Configuration Analysis

Let's check what's in your default `application.yml`:

### PKCS#11 Configuration (Lines 32-45)
```yaml
app:
  pkcs11:
    provider: SunPKCS11
    library-path: /usr/lib/softhsm/libsofthsm2.so
    config-file: /path/to/pkcs11.cfg
```

**Status:** ⚠️ **Configured but NOT REQUIRED**
- Will try to load SoftHSM
- **If not found:** Logs warning and continues
- **App still starts:** Yes

### AWS KMS Configuration (Lines 47-58)
```yaml
app:
  aws:
    kms:
      enabled: ${AWS_KMS_ENABLED:false}  # ← Defaults to FALSE
```

**Status:** ✅ **DISABLED by default**
- Only activates if `AWS_KMS_ENABLED=true`
- **If disabled:** Ignored completely
- **App still starts:** Yes

### TSP Configuration (Lines 60-62)
```yaml
app:
  tsp:
    url: http://tsa.belgium.be/connect
```

**Status:** ✅ **Optional**
- Only used if you request timestamps
- **If TSP fails:** Timestamp returns null (doesn't crash)
- **App still starts:** Yes

---

## 🚀 Quick Start Paths

### Path A: Start with Nothing (Fastest - 1 minute)

```bash
# Just run it!
mvn clean package
java -jar target/eidasremotesigning-0.0.1-SNAPSHOT.jar
```

**Result:** Application running, no signing capability yet.

---

### Path B: Start with AWS KMS (Recommended - 10 minutes)

```bash
# 1. Enable AWS KMS
export AWS_KMS_ENABLED=true
export AWS_REGION=us-east-1

# 2. Create KMS key (requires AWS CLI)
aws kms create-key --key-usage SIGN_VERIFY --key-spec RSA_2048

# 3. Start application
mvn clean package
java -jar target/eidasremotesigning-0.0.1-SNAPSHOT.jar

# 4. Associate key via API (see AWS_KMS_SETUP_GUIDE.md)
```

**Result:** Full production-ready signing capability.

---

### Path C: Start with PKCS#11 (If you have HSM - 20 minutes)

```bash
# 1. Install SoftHSM (or use your HSM)
sudo apt-get install softhsm2

# 2. Initialize token
softhsm2-util --init-token --slot 0 --label "TestToken"

# 3. Generate key in token
pkcs11-tool --module /usr/lib/softhsm/libsofthsm2.so \
  --login --keypairgen --key-type RSA:2048

# 4. Update application.yml with correct paths

# 5. Start application
mvn clean package
java -jar target/eidasremotesigning-0.0.1-SNAPSHOT.jar
```

**Result:** HSM-based signing capability.

---

## ❓ Common Questions

### Q: Will the app fail to start if I don't configure anything?

**A:** NO! The app will start successfully.

```
✅ Application starts
✅ OAuth2 works
✅ Database works
✅ REST endpoints work
❌ Cannot sign (no certificates)
```

### Q: Will the app fail if PKCS#11 library is not found?

**A:** NO! It logs a warning and continues.

```
WARN: Could not initialize PKCS#11 provider: library not found
INFO: Application started successfully on port 9000
```

### Q: Do I need all three storage types for redundancy?

**A:** NO! Choose ONE. They are alternatives, not backups.

```
Use Case → Choose One:
- Cloud deployment → AWS KMS
- On-premise → PKCS#11
- Quick test → PKCS#12
```

### Q: Can I use multiple storage types simultaneously?

**A:** YES! You can configure multiple and use different ones for different clients.

```yaml
# Enable both
app:
  aws:
    kms:
      enabled: true
  pkcs11:
    provider: SunPKCS11
```

Then:
- Client A uses AWS KMS certificates
- Client B uses PKCS#11 certificates
- Both work simultaneously

### Q: What's the easiest way to test?

**A:** Use AWS KMS if you have an AWS account (10 min setup), or just run the app and test OAuth2 endpoints.

---

## 📊 Configuration Decision Tree

```
Do you need to sign documents?
├─ NO → No configuration needed (app runs fine)
└─ YES → Choose storage type:
    ├─ Cloud deployment? → Use AWS KMS
    │   ├─ Have AWS account? → AWS KMS (10 min)
    │   └─ No AWS account? → Use PKCS#12 for testing
    │
    ├─ On-premise with HSM? → Use PKCS#11
    │   ├─ Have HSM hardware? → Configure PKCS#11
    │   └─ Testing only? → Use SoftHSM
    │
    └─ Just testing? → Use PKCS#12 (manual setup)
```

---

## 🎯 Recommendations by Environment

### Development Environment
```
Recommendation: AWS KMS (if have AWS) or Nothing
Reason: Easy to setup, no hardware needed
Time: 10 minutes
```

### Staging Environment
```
Recommendation: AWS KMS
Reason: Matches production, easy to manage
Time: 10 minutes + testing
```

### Production Environment
```
Option 1: AWS KMS (cloud deployment)
Option 2: PKCS#11 (on-premise)
NOT Recommended: PKCS#12
```

---

## 🔍 How to Check What's Required

### Check Current Configuration Status

```bash
# Start the app and check logs
mvn spring-boot:run 2>&1 | grep -i "initialized\|failed\|error"
```

**Look for:**
```
✅ "Application started successfully"
✅ "OAuth2 Authorization Server configured"
⚠️ "PKCS#11 provider initialization failed" (OK - optional)
⚠️ "AWS KMS is not enabled" (OK - optional)
```

### Verify What's Actually Loaded

```bash
# Check loaded beans
curl http://localhost:9000/actuator/beans | jq '.contexts.application.beans | keys'
```

**Look for:**
- `kmsClient` - AWS KMS enabled
- `pkcs11Provider` - PKCS#11 enabled
- Neither? That's fine - app still works

---

## ✅ Summary: What You ACTUALLY Need

### To Just Run the Application:
```
Required: Java 17, Maven
Optional: Everything else
Result: App runs, OAuth2 works, no signing yet
```

### To Run with Signing Capability (Pick ONE):

#### Option 1: AWS KMS (Easiest)
```bash
export AWS_KMS_ENABLED=true
export AWS_REGION=us-east-1
# + AWS account with KMS access
```

#### Option 2: PKCS#11 (For HSM)
```yaml
app.pkcs11.library-path: /path/to/hsm/lib.so
# + Hardware HSM or SoftHSM installed
```

#### Option 3: PKCS#12 (Testing)
```bash
# Manual keystore creation + database entry
# No configuration changes needed
```

---

## 🚦 Traffic Light Guide

### 🟢 GREEN - No Configuration Needed
- Starting the application
- Testing OAuth2 flow
- Client registration
- Exploring REST API
- Development environment setup

### 🟡 YELLOW - Minimal Configuration (5-10 min)
- AWS KMS for signing
- Basic TSP timestamp
- Database configuration

### 🔴 RED - Complex Configuration (30+ min)
- PKCS#11 HSM setup
- SoftHSM installation
- Hardware HSM integration
- Multi-region deployment

---

## 📞 Quick Reference

| Question | Answer |
|----------|--------|
| Can I start with no config? | ✅ YES |
| Will it fail without PKCS#11? | ❌ NO - continues |
| Will it fail without AWS KMS? | ❌ NO - continues |
| Do I need all three? | ❌ NO - choose ONE |
| What's easiest? | AWS KMS (10 min) |
| What's for production? | AWS KMS or PKCS#11 |
| What's for testing? | Nothing or PKCS#12 |

---

## 🎓 Bottom Line

**You need ZERO configuration to run the application.**

**You need ONE storage type to sign documents:**
- 🥇 AWS KMS (recommended, easiest)
- 🥈 PKCS#11 (if you have HSM)
- 🥉 PKCS#12 (testing only)

**Start with:**
```bash
mvn clean package && java -jar target/*.jar
```

**Then add AWS KMS when you need signing:**
```bash
export AWS_KMS_ENABLED=true
```

**That's it!** 🚀
