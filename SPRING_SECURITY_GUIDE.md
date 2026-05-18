# Spring Security Implementation Guide

## 📋 Overview

This project uses **Spring Security** with **OAuth2 Authorization Server** to provide secure authentication and authorization for the eIDAS Remote Signing Service.

---

## 🏗️ Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    Spring Security Layers                    │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌────────────────────────────────────────────────────┐   │
│  │  Layer 1: OAuth2 Authorization Server              │   │
│  │  - Client registration                             │   │
│  │  - Token generation (JWT)                          │   │
│  │  - OAuth2 endpoints (/oauth2/token)                │   │
│  │  Order: @Order(1) - Highest priority               │   │
│  └────────────────────────────────────────────────────┘   │
│                                                              │
│  ┌────────────────────────────────────────────────────┐   │
│  │  Layer 2: OAuth2 Resource Server (CSC API)        │   │
│  │  - Validates JWT tokens                            │   │
│  │  - Protects /csc/v2/** endpoints                   │   │
│  │  - Extracts client_id from JWT                     │   │
│  │  Order: @Order(2)                                  │   │
│  └────────────────────────────────────────────────────┘   │
│                                                              │
│  ┌────────────────────────────────────────────────────┐   │
│  │  Layer 3: Default Security (Web/Admin)            │   │
│  │  - Form login for admin                            │   │
│  │  - Public client registration                      │   │
│  │  - H2 console access                               │   │
│  │  Order: @Order(3) - Lowest priority                │   │
│  └────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

---

## 🔐 Security Configuration Breakdown

### **File:** [AuthorizationServerConfig.java](src/main/java/com/wpanther/eidasremotesigning/config/AuthorizationServerConfig.java)

---

## 1️⃣ **OAuth2 Authorization Server** (Order 1)

### **Purpose:**
Acts as an **OAuth2 server** that issues JWT tokens for client credentials flow.

### **Code:**
```java
@Bean
@Order(1)
public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) {
    OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
        new OAuth2AuthorizationServerConfigurer();

    RequestMatcher authorizationServerEndpointsMatcher =
        authorizationServerConfigurer.getEndpointsMatcher();

    return http
        .securityMatcher(authorizationServerEndpointsMatcher)
        .with(authorizationServerConfigurer, security -> {})
        .exceptionHandling(exceptions -> exceptions
            .authenticationEntryPoint(new LoginUrlAuthenticationEntryPoint("/login")))
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
        .build();
}
```

### **What It Does:**
- ✅ Handles OAuth2 token endpoint: `/oauth2/token`
- ✅ Issues JWT tokens for authenticated clients
- ✅ Validates client credentials (client_id + client_secret)
- ✅ Highest priority (@Order(1)) - checked first

### **Endpoints Protected:**
| Endpoint | Access | Purpose |
|----------|--------|---------|
| `/oauth2/token` | Client credentials | Get access token |
| `/oauth2/authorize` | Form login | Authorization code flow |
| `/oauth2/jwks` | Public | Public keys for JWT validation |
| `/.well-known/oauth-authorization-server` | Public | Discovery endpoint |

---

## 2️⃣ **Resource Server for CSC API** (Order 2)

### **Purpose:**
Protects the **CSC API endpoints** (`/csc/v2/**`) and validates JWT tokens.

### **Code:**
```java
@Bean
@Order(2)
public SecurityFilterChain resourceServerSecurityFilterChain(HttpSecurity http) {
    http
        .securityMatcher("/csc/v2/**")
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/csc/v2/info").permitAll()
            .requestMatchers("/csc/v2/oauth2/**").permitAll()
            .anyRequest().authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
        .csrf(csrf -> csrf.disable());

    return http.build();
}
```

### **What It Does:**
- ✅ Requires valid JWT token for most endpoints
- ✅ Extracts `client_id` from JWT claims
- ✅ Allows public access to `/csc/v2/info`
- ✅ CSRF disabled (API endpoints use stateless tokens)

### **Access Control:**
| Endpoint | Access | Authentication |
|----------|--------|----------------|
| `/csc/v2/info` | ✅ Public | None |
| `/csc/v2/oauth2/**` | ✅ Public | None |
| `/csc/v2/credentials/list` | 🔒 Protected | JWT required |
| `/csc/v2/signatures/signHash` | 🔒 Protected | JWT required |
| `/csc/v2/signatures/signDocument` | 🔒 Protected | JWT required |

---

## 3️⃣ **Default Security (Web/Admin)** (Order 3)

### **Purpose:**
Handles **web-based** authentication for admin users and public endpoints.

### **Code:**
```java
@Bean
@Order(3)
public SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) {
    http
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/client-registration", "/h2-console/**").permitAll()
            .anyRequest().authenticated())
        .csrf(csrf -> csrf
            .ignoringRequestMatchers("/client-registration", "/h2-console/**")
            .ignoringRequestMatchers("/csc/v2/**")
            .ignoringRequestMatchers("/oauth2/**"))
        .formLogin(Customizer.withDefaults());

    return http.build();
}
```

### **What It Does:**
- ✅ Form-based login for admin interface
- ✅ Public client registration endpoint
- ✅ H2 console access (development)
- ✅ CSRF protection for web forms

### **Access Control:**
| Endpoint | Access | Authentication |
|----------|--------|----------------|
| `/client-registration` | ✅ Public | None |
| `/h2-console/**` | ✅ Public | None (dev only) |
| `/api/v1/**` | 🔒 Protected | JWT or Form login |
| `/actuator/**` | 🔒 Protected | JWT or Form login |

---

## 🔑 Key Components

### **1. JWT Token Generation**

**JWK Source (Lines 108-120):**
```java
@Bean
public JWKSource<SecurityContext> jwkSource() {
    KeyPair keyPair = generateRsaKey();  // Generates RSA 2048-bit key
    RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
    RSAPrivateKey privateKey = (RSAPrivateKey) keyPair.getPrivate();

    RSAKey rsaKey = new RSAKey.Builder(publicKey)
        .privateKey(privateKey)
        .keyID(UUID.randomUUID().toString())
        .build();

    JWKSet jwkSet = new JWKSet(rsaKey);
    return new ImmutableJWKSet<>(jwkSet);
}
```

**What It Does:**
- Generates an RSA keypair on application startup
- Private key signs JWT tokens
- Public key validates JWT tokens
- ⚠️ **Note:** Keys regenerate on restart (tokens become invalid)

---

### **2. User Details Service**

**Admin User (Lines 89-100):**
```java
@Bean
public UserDetailsService userDetailsService() {
    var userDetailsManager = new InMemoryUserDetailsManager();

    userDetailsManager.createUser(
        User.withUsername("admin")
            .password(passwordEncoder().encode("admin"))
            .roles("ADMIN")
            .build());

    return userDetailsManager;
}
```

**What It Does:**
- Creates an in-memory admin user
- Username: `admin`
- Password: `admin`
- Role: `ADMIN`
- Used for form-based login
- ⚠️ **Production:** Replace with database-backed users

---

### **3. Password Encoder**

**BCrypt (Lines 102-105):**
```java
@Bean
public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
}
```

**What It Does:**
- Hashes passwords using BCrypt
- Used for admin user authentication
- Not used for OAuth2 client secrets (handled separately)

---

## 🔄 Authentication Flow

### **Client Credentials Flow (Most Common)**

```
┌──────────────┐                ┌──────────────┐                ┌──────────────┐
│              │   1. POST      │              │   2. Validate  │              │
│   Client     │   /oauth2/token│   OAuth2     │   credentials  │   Database   │
│  Application │───────────────>│   Server     │───────────────>│              │
│              │   + client_id  │              │                │  oauth2_     │
│              │   + secret     │              │                │  clients     │
└──────────────┘                └──────────────┘                └──────────────┘
                                       │
                                       │ 3. Generate JWT
                                       ▼
                                ┌──────────────┐
                                │  JWT Token   │
                                │  Payload:    │
                                │  - client_id │
                                │  - scope     │
                                │  - exp       │
                                └──────────────┘
                                       │
                                       │ 4. Return token
                                       ▼
┌──────────────┐                ┌──────────────┐
│              │   5. Use token │              │   6. Validate
│   Client     │   /csc/v2/**   │   Resource   │   JWT signature
│  Application │───────────────>│   Server     │   Extract claims
│              │   Bearer TOKEN │              │
└──────────────┘                └──────────────┘
                                       │
                                       │ 7. Execute request
                                       ▼
                                ┌──────────────┐
                                │   Service    │
                                │   Layer      │
                                │              │
                                └──────────────┘
```

---

## 🛡️ How Security Works in Practice

### **Example 1: Register a Client**

**Request:**
```bash
curl -X POST http://localhost:9000/client-registration \
  -H "Content-Type: application/json" \
  -d '{
    "clientName": "My Application",
    "scopes": ["signing"],
    "grantTypes": ["client_credentials"]
  }'
```

**Security Check:**
- ✅ Matches Order(3) filter chain
- ✅ `/client-registration` is **permitAll()**
- ✅ No authentication required
- ✅ CSRF disabled for this endpoint

**Response:**
```json
{
  "clientId": "abc123...",
  "clientSecret": "secret456...",
  "clientName": "My Application"
}
```

---

### **Example 2: Get OAuth2 Token**

**Request:**
```bash
curl -X POST http://localhost:9000/oauth2/token \
  -H "Authorization: Basic $(echo -n 'abc123:secret456' | base64)" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials&scope=signing"
```

**Security Check:**
- ✅ Matches Order(1) filter chain (OAuth2 server)
- ✅ Validates `client_id` and `client_secret`
- ✅ Checks credentials against database
- ✅ Generates JWT token signed with private key

**Response:**
```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "scope": "signing"
}
```

---

### **Example 3: Call Protected API**

**Request:**
```bash
curl -X POST http://localhost:9000/csc/v2/signatures/signDocument \
  -H "Authorization: Bearer eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..." \
  -H "Content-Type: application/json" \
  -d '{
    "clientId": "abc123",
    "credentialID": "cert-123",
    "documentDigest": "hash...",
    "hashAlgo": "SHA-256"
  }'
```

**Security Check:**
- ✅ Matches Order(2) filter chain (Resource server)
- ✅ Extracts Bearer token from Authorization header
- ✅ Validates JWT signature using public key
- ✅ Checks token expiration
- ✅ Extracts `client_id` from JWT claims
- ✅ Passes to service layer

**JWT Payload Example:**
```json
{
  "sub": "abc123",
  "aud": ["abc123"],
  "scope": ["signing"],
  "iss": "http://localhost:9000",
  "exp": 1730123456,
  "iat": 1730119856
}
```

---

## 🔍 How to Extract User/Client Information

### **In Service Layer:**

**File:** [SigningCertificateService.java:369-383](src/main/java/com/wpanther/eidasremotesigning/service/SigningCertificateService.java#L369)

```java
private String getCurrentClientId() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

    if (authentication instanceof JwtAuthenticationToken) {
        JwtAuthenticationToken jwtAuth = (JwtAuthenticationToken) authentication;
        return jwtAuth.getName();  // Returns client_id
    }

    if (authentication != null && authentication.getPrincipal() != null) {
        return authentication.getName();
    }

    throw new CertificateException("Unable to determine client ID from security context");
}
```

**What It Does:**
- Extracts authenticated client_id from JWT token
- Used throughout services to ensure data isolation
- Each client can only access their own certificates

---

## 📊 Endpoint Security Matrix

| Endpoint | Filter Chain | Auth Required | Auth Type | CSRF |
|----------|-------------|---------------|-----------|------|
| `/oauth2/token` | Order(1) | ✅ Yes | Client credentials | ❌ Disabled |
| `/oauth2/authorize` | Order(1) | ✅ Yes | Form login | ✅ Enabled |
| `/csc/v2/info` | Order(2) | ❌ No | Public | ❌ Disabled |
| `/csc/v2/signatures/*` | Order(2) | ✅ Yes | JWT Bearer | ❌ Disabled |
| `/client-registration` | Order(3) | ❌ No | Public | ❌ Disabled |
| `/h2-console/**` | Order(3) | ❌ No | Public | ❌ Disabled |
| `/api/v1/**` | Order(3) | ✅ Yes | JWT or Form | ✅ Enabled |
| `/login` | Order(3) | ❌ No | Form | ✅ Enabled |

---

## 🔧 Configuration Customization

### **Change Admin Password:**

```java
@Bean
public UserDetailsService userDetailsService() {
    var userDetailsManager = new InMemoryUserDetailsManager();

    userDetailsManager.createUser(
        User.withUsername("admin")
            .password(passwordEncoder().encode("your-secure-password"))  // ← Change
            .roles("ADMIN")
            .build());

    return userDetailsManager;
}
```

### **Add Multiple Admin Users:**

```java
@Bean
public UserDetailsService userDetailsService() {
    var userDetailsManager = new InMemoryUserDetailsManager();

    userDetailsManager.createUser(
        User.withUsername("admin").password(passwordEncoder().encode("admin")).roles("ADMIN").build());

    userDetailsManager.createUser(
        User.withUsername("operator").password(passwordEncoder().encode("pass")).roles("OPERATOR").build());

    return userDetailsManager;
}
```

### **Change Token Expiration:**

Add to configuration:
```java
@Bean
public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer() {
    return context -> {
        context.getClaims().expiresAt(Instant.now().plusSeconds(7200)); // 2 hours
    };
}
```

### **Add Custom Claims to JWT:**

```java
@Bean
public OAuth2TokenCustomizer<JwtEncodingContext> tokenCustomizer() {
    return context -> {
        if (context.getTokenType().equals(OAuth2TokenType.ACCESS_TOKEN)) {
            context.getClaims()
                .claim("custom_claim", "custom_value")
                .claim("organization", "ACME Corp");
        }
    };
}
```

---

## 🛠️ Common Tasks

### **Task 1: Add a New Protected Endpoint**

```java
// Add to existing filter chain
@Bean
@Order(2)
public SecurityFilterChain resourceServerSecurityFilterChain(HttpSecurity http) {
    http
        .securityMatcher("/csc/v2/**", "/api/v2/**")  // ← Add new path
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/csc/v2/info").permitAll()
            .requestMatchers("/api/v2/public/**").permitAll()  // ← New public endpoint
            .anyRequest().authenticated())
        .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
        .csrf(csrf -> csrf.disable());

    return http.build();
}
```

### **Task 2: Require Specific Scope**

```java
// In controller
@PreAuthorize("hasAuthority('SCOPE_signing')")
@PostMapping("/csc/v2/signatures/signDocument")
public ResponseEntity<?> signDocument(...) {
    // Only accessible with 'signing' scope
}
```

### **Task 3: Get Client ID in Controller**

```java
@RestController
public class MyController {

    @PostMapping("/my-endpoint")
    public ResponseEntity<?> myEndpoint(Authentication authentication) {
        String clientId = authentication.getName();  // Gets client_id from JWT

        // Or more detailed:
        if (authentication instanceof JwtAuthenticationToken jwt) {
            String clientId = jwt.getToken().getSubject();
            Map<String, Object> claims = jwt.getToken().getClaims();
        }

        return ResponseEntity.ok("Client: " + clientId);
    }
}
```

---

## 🐛 Troubleshooting

### **Issue 1: "401 Unauthorized" on API Calls**

**Cause:** Invalid or expired JWT token

**Solution:**
```bash
# Get a fresh token
curl -X POST http://localhost:9000/oauth2/token \
  -H "Authorization: Basic $(echo -n 'client_id:client_secret' | base64)" \
  -d "grant_type=client_credentials&scope=signing"
```

### **Issue 2: "403 Forbidden" with Valid Token**

**Cause:** Missing required scope

**Solution:**
- Check client has `signing` scope in database
- Verify scope in JWT token: https://jwt.io/

### **Issue 3: Tokens Invalid After Restart**

**Cause:** RSA keypair regenerated on startup

**Solution:** For production, persist keys:
```java
@Bean
public JWKSource<SecurityContext> jwkSource() {
    // Load keys from file or database instead of generating
    return loadPersistedKeys();
}
```

### **Issue 4: CSRF Token Missing**

**Cause:** CSRF enabled for API endpoints

**Solution:** Already disabled for `/csc/v2/**` and `/oauth2/**`

---

## 🎯 Security Best Practices

### **✅ DO:**
1. Use HTTPS in production
2. Store client secrets encrypted
3. Persist JWT signing keys
4. Implement token refresh
5. Add rate limiting
6. Log authentication failures
7. Use strong passwords for admin users

### **❌ DON'T:**
1. Commit client secrets to git
2. Use default admin password in production
3. Expose H2 console in production
4. Disable CSRF for form-based endpoints
5. Share JWT tokens between clients
6. Use long-lived tokens

---

## 📚 Summary

### **Three-Layer Security:**
1. **OAuth2 Authorization Server** - Issues JWT tokens
2. **OAuth2 Resource Server** - Validates JWT for API calls
3. **Default Web Security** - Form login for admin

### **Key Points:**
- ✅ JWT-based authentication for API
- ✅ Client credentials OAuth2 flow
- ✅ Stateless (no sessions)
- ✅ RSA-signed tokens
- ✅ Scope-based authorization
- ✅ Multi-tenant via client_id isolation

### **Authentication Methods:**
- **API Clients:** JWT Bearer tokens (client credentials flow)
- **Admin Users:** Form-based login (username/password)
- **Public Endpoints:** No authentication

---

## 🔗 Related Files

- [AuthorizationServerConfig.java](src/main/java/com/wpanther/eidasremotesigning/config/AuthorizationServerConfig.java) - Main security config
- [SigningCertificateService.java](src/main/java/com/wpanther/eidasremotesigning/service/SigningCertificateService.java) - Client ID extraction
- [ClientRegistrationController.java](src/main/java/com/wpanther/eidasremotesigning/controller/ClientRegistrationController.java) - Public registration
- [pom.xml](pom.xml) - Spring Security dependencies

---

**Your Spring Security setup provides enterprise-grade OAuth2 authentication with JWT tokens for the eIDAS Remote Signing Service!** 🔐
