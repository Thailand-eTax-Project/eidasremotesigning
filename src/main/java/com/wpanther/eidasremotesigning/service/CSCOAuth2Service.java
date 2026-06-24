package com.wpanther.eidasremotesigning.service;

import com.wpanther.eidasremotesigning.controller.CSCOAuth2Controller.CSCOAuth2Exception;
import com.wpanther.eidasremotesigning.dto.csc.CSCOAuth2TokenResponse;
import com.wpanther.eidasremotesigning.entity.OAuth2Client;
import com.wpanther.eidasremotesigning.repository.OAuth2ClientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service implementing OAuth2 functionality for CSC API
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CSCOAuth2Service {

    private final OAuth2ClientRepository clientRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom;
    
    // In-memory stores - in a production system these would be in a database
    private final Map<String, AuthorizationRequest> authorizationRequests = new ConcurrentHashMap<>();
    private final Map<String, TokenInfo> accessTokens = new ConcurrentHashMap<>();
    private final Map<String, String> refreshTokens = new ConcurrentHashMap<>(); // refreshToken -> accessToken
    
    // Token expiration time in seconds (1 hour)
    private static final int ACCESS_TOKEN_EXPIRATION = 3600;
    private static final int REFRESH_TOKEN_EXPIRATION = 86400; // 24 hours
    
    /**
     * Stores an authorization request for OAuth2 flow
     */
    public void storeAuthorizationRequest(String code, String clientId, String redirectUri,
                                          String scope, String state,
                                          String codeChallenge, String codeChallengeMethod,
                                          String credentialID) {
        // Validate client exists
        clientRepository.findByClientId(clientId)
                .orElseThrow(() -> new CSCOAuth2Exception("invalid_client", "Client not found"));

        // Store authorization request
        authorizationRequests.put(code, new AuthorizationRequest(
                clientId, redirectUri, scope, state,
                codeChallenge, codeChallengeMethod, credentialID,
                Instant.now()));

        // Set expiration (auth codes expire in 10 minutes)
        // NOTE: existing thread-per-auth-code pattern is retained (out of scope, future cleanup).
        // A future cleanup should replace it with ScheduledExecutorService.schedule() or a
        // TTL-aware ConcurrentHashMap wrapper.
        new Thread(() -> {
            try {
                Thread.sleep(600000); // 10 minutes
                authorizationRequests.remove(code);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }

    /**
     * Exchanges an authorization code for access and refresh tokens
     */
    public CSCOAuth2TokenResponse exchangeAuthorizationCode(String code, String redirectUri,
                                                         String clientId, String clientSecret,
                                                         String codeVerifier) {
        // Validate authorization code
        AuthorizationRequest authRequest = authorizationRequests.get(code);
        if (authRequest == null) {
            throw new CSCOAuth2Exception("invalid_grant", "Authorization code is invalid or expired");
        }

        // Validate redirect URI
        if (!authRequest.redirectUri.equals(redirectUri)) {
            throw new CSCOAuth2Exception("invalid_grant", "Redirect URI does not match");
        }

        // PKCE validation (RFC 7636 §4.6)
        if (authRequest.codeChallenge != null) {
            if (codeVerifier == null) {
                throw new CSCOAuth2Exception("invalid_grant", "code_verifier is required");
            }
            String computedChallenge = computeCodeChallenge(codeVerifier, authRequest.codeChallengeMethod);
            if (!computedChallenge.equals(authRequest.codeChallenge)) {
                throw new CSCOAuth2Exception("invalid_grant", "code_verifier does not match code_challenge");
            }
        }

        // Validate client
        OAuth2Client client = clientRepository.findByClientId(authRequest.clientId)
                .orElseThrow(() -> new CSCOAuth2Exception("invalid_client", "Client not found"));

        // Validate client credentials if provided
        if (clientId != null && clientSecret != null) {
            if (!client.getClientId().equals(clientId)) {
                throw new CSCOAuth2Exception("invalid_client", "Client ID does not match");
            }

            if (!passwordEncoder.matches(clientSecret, client.getClientSecret())) {
                throw new CSCOAuth2Exception("invalid_client", "Invalid client secret");
            }
        }

        // Generate tokens
        String accessToken = generateToken();
        String refreshToken = generateToken();

        // Store token information
        Instant expiresAt = Instant.now().plusSeconds(ACCESS_TOKEN_EXPIRATION);
        TokenInfo tokenInfo = new TokenInfo(client.getClientId(), authRequest.scope, expiresAt);
        accessTokens.put(accessToken, tokenInfo);
        refreshTokens.put(refreshToken, accessToken);

        // Remove used authorization code
        authorizationRequests.remove(code);

        // Return token response
        return CSCOAuth2TokenResponse.builder()
                .access_token(accessToken)
                .token_type("Bearer")
                .expires_in(ACCESS_TOKEN_EXPIRATION)
                .refresh_token(refreshToken)
                .scope(authRequest.scope)
                .credentialID(authRequest.credentialID)
                .build();
    }
    
    /**
     * Refreshes an access token using a refresh token
     */
    public CSCOAuth2TokenResponse refreshAccessToken(String refreshToken, String clientId, String clientSecret) {
        // Validate refresh token
        String accessToken = refreshTokens.get(refreshToken);
        if (accessToken == null) {
            throw new CSCOAuth2Exception("invalid_grant", "Refresh token is invalid or expired");
        }
        
        // Get token information
        TokenInfo tokenInfo = accessTokens.get(accessToken);
        if (tokenInfo == null) {
            refreshTokens.remove(refreshToken);
            throw new CSCOAuth2Exception("invalid_grant", "Access token not found");
        }
        
        // Validate client
        OAuth2Client client = clientRepository.findByClientId(tokenInfo.clientId)
                .orElseThrow(() -> new CSCOAuth2Exception("invalid_client", "Client not found"));
        
        // Validate client credentials if provided
        if (clientId != null && clientSecret != null) {
            if (!client.getClientId().equals(clientId)) {
                throw new CSCOAuth2Exception("invalid_client", "Client ID does not match");
            }
            
            if (!passwordEncoder.matches(clientSecret, client.getClientSecret())) {
                throw new CSCOAuth2Exception("invalid_client", "Invalid client secret");
            }
        }
        
        // Generate new tokens
        String newAccessToken = generateToken();
        String newRefreshToken = generateToken();
        
        // Store new token information
        Instant expiresAt = Instant.now().plusSeconds(ACCESS_TOKEN_EXPIRATION);
        TokenInfo newTokenInfo = new TokenInfo(client.getClientId(), tokenInfo.scope, expiresAt);
        accessTokens.put(newAccessToken, newTokenInfo);
        refreshTokens.put(newRefreshToken, newAccessToken);
        
        // Remove old tokens
        accessTokens.remove(accessToken);
        refreshTokens.remove(refreshToken);
        
        // Return token response
        return CSCOAuth2TokenResponse.builder()
                .access_token(newAccessToken)
                .token_type("Bearer")
                .expires_in(ACCESS_TOKEN_EXPIRATION)
                .refresh_token(newRefreshToken)
                .scope(tokenInfo.scope)
                .build();
    }
    
    /**
     * Grants access token using client credentials
     */
    public CSCOAuth2TokenResponse clientCredentialsGrant(String clientId, String clientSecret) {
        // Validate client
        OAuth2Client client = clientRepository.findByClientId(clientId)
                .orElseThrow(() -> new CSCOAuth2Exception("invalid_client", "Client not found"));
        
        // Validate client secret
        if (!passwordEncoder.matches(clientSecret, client.getClientSecret())) {
            throw new CSCOAuth2Exception("invalid_client", "Invalid client secret");
        }
        
        // Generate access token (no refresh token for client credentials)
        String accessToken = generateToken();
        
        // Determine scope
        String scope = String.join(" ", client.getScopes());
        
        // Store token information
        Instant expiresAt = Instant.now().plusSeconds(ACCESS_TOKEN_EXPIRATION);
        TokenInfo tokenInfo = new TokenInfo(client.getClientId(), scope, expiresAt);
        accessTokens.put(accessToken, tokenInfo);
        
        // Return token response
        return CSCOAuth2TokenResponse.builder()
                .access_token(accessToken)
                .token_type("Bearer")
                .expires_in(ACCESS_TOKEN_EXPIRATION)
                .scope(scope)
                .build();
    }
    
    /**
     * Validates an access token
     */
    public TokenInfo validateAccessToken(String accessToken) {
        TokenInfo tokenInfo = accessTokens.get(accessToken);
        if (tokenInfo == null) {
            throw new CSCOAuth2Exception("invalid_token", "Access token is invalid");
        }
        
        // Check if token has expired
        if (tokenInfo.expiresAt.isBefore(Instant.now())) {
            accessTokens.remove(accessToken);
            throw new CSCOAuth2Exception("invalid_token", "Access token has expired");
        }
        
        return tokenInfo;
    }
    
    /**
     * Generates a secure random token
     */
    private String generateToken() {
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    /**
     * Revokes an access or refresh token (RFC 7009 §2.2, CSC spec §8.4.5).
     * Silently succeeds when the token is unknown or already expired.
     */
    public void revokeToken(String token, String tokenTypeHint) {
        if (token == null) {
            log.debug("Revoke called with null token; ignoring");
            return;
        }
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

    /**
     * Computes a PKCE code challenge from the verifier per RFC 7636 §4.2.
     * Supports "plain" (verifier returned as-is) and "S256" (BASE64URL(SHA256(verifier))).
     */
    private String computeCodeChallenge(String codeVerifier, String method) {
        if ("plain".equals(method) || method == null) {
            return codeVerifier;
        }
        if ("S256".equals(method)) {
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] hash = digest.digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
                return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            } catch (NoSuchAlgorithmException e) {
                throw new CSCOAuth2Exception("server_error", "SHA-256 not available");
            }
        }
        throw new CSCOAuth2Exception("invalid_request", "Unsupported code_challenge_method: " + method);
    }
    
    /**
     * Data class for storing authorization requests
     */
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
    
    /**
     * Data class for storing token information
     */
    public static class TokenInfo {
        private final String clientId;
        private final String scope;
        private final Instant expiresAt;
        
        public TokenInfo(String clientId, String scope, Instant expiresAt) {
            this.clientId = clientId;
            this.scope = scope;
            this.expiresAt = expiresAt;
        }
        
        public String getClientId() {
            return clientId;
        }
        
        public String getScope() {
            return scope;
        }
        
        public Instant getExpiresAt() {
            return expiresAt;
        }
    }
}