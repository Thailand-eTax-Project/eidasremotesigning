package com.wpanther.eidasremotesigning.service;

import com.wpanther.eidasremotesigning.dto.csc.*;
import com.wpanther.eidasremotesigning.entity.SigningCertificate;
import com.wpanther.eidasremotesigning.entity.TransactionAuthorization;
import com.wpanther.eidasremotesigning.exception.CertificateException;
import com.wpanther.eidasremotesigning.exception.SigningException;
import com.wpanther.eidasremotesigning.exception.SigningInProgressException;
import com.wpanther.eidasremotesigning.repository.SigningCertificateRepository;
import com.wpanther.eidasremotesigning.repository.TransactionAuthorizationRepository;
import org.springframework.security.core.context.SecurityContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Service implementing the CSC API credential authorization functionality
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CSCAuthorizationService {

    private final SigningCertificateRepository certificateRepository;
    private final TransactionAuthorizationRepository transactionRepository;
    private final SecureRandom secureRandom;

    private String currentClientId() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    // Default transaction validity period in seconds (15 minutes)
    private static final long DEFAULT_VALIDITY_PERIOD = 15 * 60;
    
    // Maximum validity period in seconds (1 hour)
    private static final long MAX_VALIDITY_PERIOD = 60 * 60;

    /**
     * Authorizes a credential for signing operations
     */
    @Transactional
    public CSCAuthorizeResponse authorizeCredential(CSCAuthorizeRequest request) {
        try {
            String clientId = currentClientId();
            String credentialId = request.getCredentialID();
            
            // Verify credential exists and belongs to client
            SigningCertificate certificate = certificateRepository.findByIdAndClientId(credentialId, clientId)
                    .orElseThrow(() -> new CertificateException("Certificate not found"));
            
            // Determine validity period (use default since validityPeriod removed from request)
            long validityPeriod = DEFAULT_VALIDITY_PERIOD;

            // Extract PIN from authData if provided
            String storedPin = null;
            if (request.getAuthData() != null) {
                storedPin = request.getAuthData().stream()
                        .filter(e -> "PIN".equals(e.getId()))
                        .map(CSCAuthorizeRequest.AuthDataEntry::getValue)
                        .findFirst()
                        .orElse(null);
            }

            if (!"AWSKMS".equals(certificate.getStorageType()) && storedPin == null) {
                throw new CertificateException(
                        "PIN is required in authData for " + certificate.getStorageType() + " credentials");
            }

            // Generate transaction ID
            String transactionId = UUID.randomUUID().toString();

            // Generate Signature Activation Data (SAD) - a secure token for signing
            String sad = generateSignatureActivationData();

            // Create expiration time
            Instant expiresAt = Instant.now().plusSeconds(validityPeriod);

            // Set up transaction authorization
            TransactionAuthorization transaction = TransactionAuthorization.builder()
                    .id(transactionId)
                    .clientId(clientId)
                    .certificateId(credentialId)
                    .sad(sad)
                    .numSignatures(request.getNumSignatures())
                    .remainingSignatures(request.getNumSignatures())
                    .description(request.getDescription())
                    .storedPin(storedPin)
                    .status("AUTHORIZATION_INITIALIZED")
                    .createdAt(Instant.now())
                    .expiresAt(expiresAt)
                    .build();

            transactionRepository.save(transaction);
            log.debug("Created transaction authorization: {}", transactionId);

            // Build and return response
            return CSCAuthorizeResponse.builder()
                    .handle(transactionId)
                    .SAD(sad)
                    .expiresIn(validityPeriod)
                    .build();
            
        } catch (CertificateException | SigningException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to authorize credential", e);
            throw new SigningException("Failed to authorize credential: " + e.getMessage(), e);
        }
    }

    /**
     * Extends the validity period of a transaction
     */
    @Transactional
    public CSCExtendTransactionResponse extendTransaction(CSCExtendTransactionRequest request) {
        try {
            String clientId = currentClientId();

            // Look up transaction by SAD (not by ID)
            TransactionAuthorization transaction = transactionRepository
                    .findBySadAndClientId(request.getSAD(), clientId)
                    .orElseThrow(() -> new SigningException("Transaction not found for provided SAD"));
            
            // Check if transaction has expired
            if (transaction.getExpiresAt().isBefore(Instant.now())) {
                throw new SigningException("Transaction has expired");
            }
            
            // Check if transaction is in a valid state
            if (!"AUTHORIZATION_INITIALIZED".equals(transaction.getStatus()) && 
                !"AUTHORIZED".equals(transaction.getStatus())) {
                throw new SigningException("Transaction cannot be extended in current state: " + transaction.getStatus());
            }
            
            // Extend the transaction by the default validity period
            Instant newExpiresAt = Instant.now().plusSeconds(DEFAULT_VALIDITY_PERIOD);
            transaction.setExpiresAt(newExpiresAt);
            
            transactionRepository.save(transaction);
            log.debug("Extended transaction authorization: {}", transaction.getId());
            
            // Calculate expires in time in seconds
            long expiresIn = DEFAULT_VALIDITY_PERIOD;
            
            return CSCExtendTransactionResponse.builder()
                    .expiresIn(expiresIn)
                    .build();
            
        } catch (Exception e) {
            log.error("Failed to extend transaction", e);
            throw new SigningException("Failed to extend transaction: " + e.getMessage(), e);
        }
    }
    
    /**
     * Gets the current status of a credential authorization
     */
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
    
    /**
     * Updates a transaction status
     */
    @Transactional
    public void updateTransactionStatus(String transactionId, String status) {
        TransactionAuthorization transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new SigningException("Transaction not found"));
        
        transaction.setStatus(status);
        transactionRepository.save(transaction);
    }
    
    /**
     * Validates a transaction for signing operation
     */
    @Transactional
    public TransactionAuthorization validateTransactionForSigning(String clientId, String transactionId, String sad) {
        TransactionAuthorization transaction = transactionRepository.findByIdAndClientId(transactionId, clientId)
                .orElseThrow(() -> new SigningException("Transaction not found"));
        
        // Check if transaction has expired
        if (transaction.getExpiresAt().isBefore(Instant.now())) {
            throw new SigningException("Transaction has expired");
        }
        
        // Check SAD if provided
        if (sad != null && !sad.equals(transaction.getSad())) {
            throw new SigningException("Invalid Signature Activation Data (SAD)");
        }
        
        // Check if transaction is in valid state
        if (!"AUTHORIZATION_INITIALIZED".equals(transaction.getStatus()) && 
            !"AUTHORIZED".equals(transaction.getStatus())) {
            throw new SigningException("Transaction is not in a valid state for signing: " + transaction.getStatus());
        }
        
        // Check remaining signatures
        if (transaction.getRemainingSignatures() != null && transaction.getRemainingSignatures() <= 0) {
            throw new SigningException("No remaining signatures allowed for this transaction");
        }
        
        // Update state if needed
        if ("AUTHORIZATION_INITIALIZED".equals(transaction.getStatus())) {
            transaction.setStatus("AUTHORIZED");
        }
        
        // Decrement remaining signatures if tracked
        if (transaction.getRemainingSignatures() != null) {
            transaction.setRemainingSignatures(transaction.getRemainingSignatures() - 1);
        }
        
        transactionRepository.save(transaction);
        return transaction;
    }
    
    /**
     * Validates a transaction for signing using SAD token lookup
     */
    @Transactional
    public TransactionAuthorization validateTransactionForSigningBySad(String clientId, String sad) {
        TransactionAuthorization transaction = transactionRepository.findBySadAndClientId(sad, clientId)
                .orElseThrow(() -> new SigningException("Transaction not found for the provided SAD"));

        // Check if transaction has expired
        if (transaction.getExpiresAt().isBefore(Instant.now())) {
            throw new SigningException("Transaction has expired");
        }

        // Check if transaction is in valid state
        if (!"AUTHORIZATION_INITIALIZED".equals(transaction.getStatus()) &&
            !"AUTHORIZED".equals(transaction.getStatus())) {
            throw new SigningException("Transaction is not in a valid state for signing: " + transaction.getStatus());
        }

        // Check remaining signatures
        if (transaction.getRemainingSignatures() != null && transaction.getRemainingSignatures() <= 0) {
            throw new SigningException("No remaining signatures allowed for this transaction");
        }

        // Update state if needed
        if ("AUTHORIZATION_INITIALIZED".equals(transaction.getStatus())) {
            transaction.setStatus("AUTHORIZED");
        }

        // Decrement remaining signatures if tracked
        if (transaction.getRemainingSignatures() != null) {
            transaction.setRemainingSignatures(transaction.getRemainingSignatures() - 1);
        }

        transactionRepository.save(transaction);
        return transaction;
    }

    /**
     * Generates a secure Signature Activation Data token
     */
    private String generateSignatureActivationData() {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }
    
}