package com.wpanther.eidasremotesigning.service;

import com.wpanther.eidasremotesigning.dto.DigestSigningRequest;
import com.wpanther.eidasremotesigning.dto.csc.*;
import com.wpanther.eidasremotesigning.entity.AsyncOperation;
import com.wpanther.eidasremotesigning.entity.SigningCertificate;
import com.wpanther.eidasremotesigning.entity.SigningLog;
import com.wpanther.eidasremotesigning.entity.TransactionAuthorization;
import com.wpanther.eidasremotesigning.exception.SigningException;
import com.wpanther.eidasremotesigning.repository.AsyncOperationRepository;
import com.wpanther.eidasremotesigning.repository.SigningCertificateRepository;
import com.wpanther.eidasremotesigning.repository.SigningLogRepository;
import com.wpanther.eidasremotesigning.util.CSCConstants;
import com.wpanther.eidasremotesigning.util.DocumentFormatUtil;
import com.wpanther.eidasremotesigning.util.OIDMapper;
import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.enumerations.SignatureAlgorithm;
import eu.europa.esig.dss.enumerations.SignatureLevel;
import eu.europa.esig.dss.enumerations.SignaturePackaging;
import eu.europa.esig.dss.model.DSSDocument;
import eu.europa.esig.dss.model.InMemoryDocument;
import eu.europa.esig.dss.model.SignatureValue;
import eu.europa.esig.dss.model.TimestampBinary;
import eu.europa.esig.dss.model.ToBeSigned;
import eu.europa.esig.dss.model.x509.CertificateToken;
import eu.europa.esig.dss.pades.PAdESSignatureParameters;
import eu.europa.esig.dss.pades.signature.PAdESService;
import eu.europa.esig.dss.service.tsp.OnlineTSPSource;
import eu.europa.esig.dss.validation.CommonCertificateVerifier;
import eu.europa.esig.dss.xades.XAdESSignatureParameters;
import eu.europa.esig.dss.xades.signature.XAdESService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import lombok.Builder;
import lombok.Data;

/**
 * Service implementing advanced CSC API signature operations
 */
@Service
@Slf4j
public class CSCSignatureService {

    private final SigningCertificateRepository certificateRepository;
    private final SigningCertificateService certificateService;
    private final SigningLogService signingLogService;
    private final SigningLogRepository signingLogRepository;
    private final CSCAuthorizationService cscAuthorizationService;
    private final EIDASComplianceService eidasComplianceService;
    private final AsyncOperationRepository asyncOperationRepository;
    private final AsyncOperationService asyncOperationService;
    private final DocumentFormatUtil documentFormatUtil;

    private final Executor asyncExecutor;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AWSKMSService awskmsService;

    private String tspUrl;

    private int operationExpiryMinutes;

    // Cache of ongoing asynchronous signing operations
    private final Map<String, SigningOperation> ongoingOperations = new ConcurrentHashMap<>();

    public CSCSignatureService(SigningCertificateRepository certificateRepository,
                                 SigningCertificateService certificateService,
                                 SigningLogService signingLogService,
                                 SigningLogRepository signingLogRepository,
                                 CSCAuthorizationService cscAuthorizationService,
                                 EIDASComplianceService eidasComplianceService,
                                 AsyncOperationRepository asyncOperationRepository,
                                 AsyncOperationService asyncOperationService,
                                 DocumentFormatUtil documentFormatUtil,
                                 @Qualifier("asyncSigningExecutor") Executor asyncExecutor,
                                 @org.springframework.beans.factory.annotation.Autowired(required = false) AWSKMSService awskmsService,
                                 @Value("${app.tsp.url:http://tsa.belgium.be/connect}") String tspUrl,
                                 @Value("${app.async.operation-expiry-minutes:30}") int operationExpiryMinutes) {
        this.certificateRepository = certificateRepository;
        this.certificateService = certificateService;
        this.signingLogService = signingLogService;
        this.signingLogRepository = signingLogRepository;
        this.cscAuthorizationService = cscAuthorizationService;
        this.eidasComplianceService = eidasComplianceService;
        this.asyncOperationRepository = asyncOperationRepository;
        this.asyncOperationService = asyncOperationService;
        this.documentFormatUtil = documentFormatUtil;
        this.asyncExecutor = asyncExecutor;
        this.awskmsService = awskmsService;
        this.tspUrl = tspUrl;
        this.operationExpiryMinutes = operationExpiryMinutes;
    }
    
    /**
     * Sign a complete document instead of just a hash
     * Supports both synchronous and asynchronous modes
     */
    @Transactional
    public CSCSignDocumentResponse signDocument(CSCSignDocumentRequest request) {
        // Check if async mode is requested
        if (CSCConstants.OPERATION_MODE_ASYNC.equals(request.getOperationMode())) {
            return signDocumentAsync(request);
        }

        // Execute synchronously for backward compatibility
        return executeSignDocument(request);
    }

    /**
     * Handle asynchronous signDocument request
     * Creates an async operation and returns operationID immediately
     */
    private CSCSignDocumentResponse signDocumentAsync(CSCSignDocumentRequest request) {
        // Create async operation
        AsyncOperation operation = asyncOperationService.createOperation(
                request.getClientId(),
                AsyncOperationService.TYPE_SIGN_DOCUMENT,
                operationExpiryMinutes
        );

        // Submit async task
        CompletableFuture.runAsync(() -> executeAsyncSignDocument(operation.getId(), request), asyncExecutor);

        // Return immediately with operationID
        return CSCSignDocumentResponse.builder()
                .operationID(operation.getId())
                .build();
    }

    /**
     * Execute signDocument asynchronously in background thread
     * Updates AsyncOperation with result or error
     */
    @Async("asyncSigningExecutor")
    void executeAsyncSignDocument(String operationId, CSCSignDocumentRequest request) {
        try {
            CSCSignDocumentResponse result = executeSignDocument(request);
            asyncOperationService.updateOperationSuccess(operationId, result);
            log.info("Async signDocument completed successfully: operationId={}", operationId);
        } catch (Exception e) {
            asyncOperationService.updateOperationFailure(operationId, e.getMessage());
            log.error("Async signDocument failed: operationId={}", operationId, e);
        }
    }

    /**
     * Core logic for signing a document
     * Shared by both sync and async execution paths
     */
    private CSCSignDocumentResponse executeSignDocument(CSCSignDocumentRequest request) {
        try{
            String clientId = request.getClientId();
            String credentialId = request.getCredentialID();
            String pin = extractPinFromRequest(request);
            String transactionId = UUID.randomUUID().toString();

            // Check if we have a SAD or PIN
            if (request.getSAD() == null && pin == null) {
                throw new SigningException("Either PIN or SAD is required for signing operations");
            }

            // If SAD is provided, validate transaction using SAD lookup
            TransactionAuthorization transaction = null;
            if (request.getSAD() != null) {
                transaction = cscAuthorizationService.validateTransactionForSigningBySad(
                        clientId, request.getSAD());

                // Make sure the credential IDs match
                if (!transaction.getCertificateId().equals(credentialId)) {
                    throw new SigningException("Credential ID does not match authorized transaction");
                }
            }

            // Find the certificate (with client ownership check)
            SigningCertificate certEntity = certificateRepository.findByIdAndClientId(credentialId, clientId)
                    .orElseThrow(() -> new SigningException("Certificate not found"));

            // Verify certificate is active
            if (!certEntity.isActive()) {
                throw new SigningException("Certificate is not active");
            }

            // Get the certificate
            X509Certificate certificate;
            PrivateKey privateKey = null;  // Will be null for AWS KMS

            if (pin != null) {
                // Load using PIN
                certificate = certificateService.getCertificateWithX509(credentialId, pin)
                        .getX509Certificate();

                // Only load private key if not AWS KMS
                if (!"AWSKMS".equals(certEntity.getStorageType())) {
                    privateKey = certificateService.getPrivateKey(credentialId, pin);
                }
            } else {
                // We should have a transaction with SAD already validated
                if (transaction == null) {
                    throw new SigningException("Internal error: No transaction with valid SAD");
                }

                // For non-KMS, we need the PIN
                if (!"AWSKMS".equals(certEntity.getStorageType())) {
                    if (request.getCredentials() == null ||
                        request.getCredentials().getPin() == null ||
                        request.getCredentials().getPin().getValue() == null) {
                        throw new SigningException("PIN is required for signing with PKCS#11 token");
                    }

                    String tokenPin = request.getCredentials().getPin().getValue();
                    certificate = certificateService.getCertificateWithX509(credentialId, tokenPin)
                            .getX509Certificate();
                    privateKey = certificateService.getPrivateKey(credentialId, tokenPin);
                } else {
                    // For AWS KMS, no PIN needed
                    certificate = certificateService.getCertificateWithX509(credentialId, null)
                            .getX509Certificate();
                }
            }

            // Validate hash algorithm — translate OID to JCA name
            String hashAlgo = OIDMapper.toJcaHashAlgo(request.getHashAlgo());

            // Determine signature type from document format (default XAdES)
            DigestSigningRequest.SignatureType signatureType = DigestSigningRequest.SignatureType.XADES;

            // Create digest signing request for eIDAS compliance validation
            DigestSigningRequest validationRequest = DigestSigningRequest.builder()
                    .certificateId(credentialId)
                    .digestValue(request.getDocumentDigest())
                    .digestAlgorithm(hashAlgo)
                    .signatureType(signatureType)
                    .build();

            // Verify eIDAS compliance
            eidasComplianceService.validateEIDASCompliance(validationRequest, certificate);

            // Determine signature algorithm (handle AWS KMS where privateKey is null)
            String keyAlgoForSig;
            if ("AWSKMS".equals(certEntity.getStorageType())) {
                if (awskmsService == null) {
                    throw new SigningException("AWS KMS is not enabled or configured");
                }
                keyAlgoForSig = awskmsService.getKeyAlgorithmType(certEntity.getKmsKeyId());
            } else {
                keyAlgoForSig = privateKey.getAlgorithm();
            }
            String signatureAlgorithm = OIDMapper.deriveJcaSigAlgo(keyAlgoForSig, hashAlgo);

            // For document signing, we need to check if the document is provided or just the digest
            boolean isDocumentProvided = request.getDocument() != null && !request.getDocument().isEmpty();
            byte[] documentBytes = null;
            byte[] digestBytes = null;

            if (isDocumentProvided) {
                // Decode document
                documentBytes = Base64.getDecoder().decode(request.getDocument());

                // Validate document format
                String mimeType = documentFormatUtil.detectMimeType(documentBytes);
                if ("application/pdf".equals(mimeType)) {
                    if (!documentFormatUtil.validatePdfDocument(documentBytes)) {
                        throw new SigningException("Invalid PDF document structure");
                    }
                } else if ("application/xml".equals(mimeType)) {
                    if (!documentFormatUtil.validateXmlDocument(documentBytes)) {
                        throw new SigningException("Invalid XML document structure");
                    }
                }

                // Calculate digest for verification
                MessageDigest digest = MessageDigest.getInstance(hashAlgo);
                digestBytes = digest.digest(documentBytes);

                // Compare with provided digest if available
                if (request.getDocumentDigest() != null) {
                    byte[] providedDigest = Base64.getDecoder().decode(request.getDocumentDigest());
                    if (!MessageDigest.isEqual(digestBytes, providedDigest)) {
                        throw new SigningException("Document digest does not match the calculated digest");
                    }
                }
            } else {
                // Use provided digest
                if (request.getDocumentDigest() == null) {
                    throw new SigningException("Either document or documentDigest must be provided");
                }
                digestBytes = Base64.getDecoder().decode(request.getDocumentDigest());
            }

            // Signed document result (for DSS integration)
            String signedDocumentBase64 = null;

            if (isDocumentProvided) {
                // Use EU DSS library for proper PAdES/XAdES document signing
                String mimeType = documentFormatUtil.detectMimeType(documentBytes);

                if ("application/pdf".equals(mimeType) || signatureType == DigestSigningRequest.SignatureType.PADES) {
                    // PAdES signing
                    signedDocumentBase64 = signDocumentWithPAdES(documentBytes, certificate, certEntity,
                            privateKey, hashAlgo, signatureAlgorithm, keyAlgoForSig);
                } else {
                    // XAdES signing
                    signedDocumentBase64 = signDocumentWithXAdES(documentBytes, certificate, certEntity,
                            privateKey, hashAlgo, signatureAlgorithm, keyAlgoForSig);
                }
            } else {
                // Digest-only: sign the digest bytes and return raw signature
                byte[] signatureBytes = signRawBytes(digestBytes, certEntity, privateKey,
                        signatureAlgorithm, hashAlgo, keyAlgoForSig, true);
                signedDocumentBase64 = Base64.getEncoder().encodeToString(signatureBytes);
            }

            // Log the successful signing operation
            signingLogService.logSuccessfulSigning(validationRequest, signatureAlgorithm);

            // Return response with signed document
            return CSCSignDocumentResponse.builder()
                    .transactionID(transactionId)
                    .signedDocument(signedDocumentBase64)
                    .signedDocumentDigest(Base64.getEncoder().encodeToString(digestBytes))
                    .signatureAlgorithm(OIDMapper.toOidSigAlgo(signatureAlgorithm))
                    .build();

        } catch (SigningException se) {
            throw se;
        } catch (Exception e) {
            log.error("Error in signDocument", e);
            throw new SigningException("Failed to sign document: " + e.getMessage(), e);
        }
    }

    /**
     * Signs a PDF document using EU DSS PAdES
     */
    private String signDocumentWithPAdES(byte[] documentBytes, X509Certificate certificate,
            SigningCertificate certEntity, PrivateKey privateKey, String hashAlgo,
            String signatureAlgorithm, String keyAlgoForSig) throws Exception {

        PAdESSignatureParameters params = new PAdESSignatureParameters();
        params.setSignatureLevel(SignatureLevel.PAdES_BASELINE_B);
        params.setDigestAlgorithm(mapHashAlgorithm(hashAlgo));
        params.setSigningCertificate(new CertificateToken(certificate));

        CommonCertificateVerifier verifier = new CommonCertificateVerifier();
        PAdESService padesService = new PAdESService(verifier);

        InMemoryDocument documentToSign = new InMemoryDocument(documentBytes);
        ToBeSigned dataToSign = padesService.getDataToSign(documentToSign, params);

        // Sign the ToBeSigned bytes using the appropriate backend
        byte[] dssSignatureBytes = signRawBytes(dataToSign.getBytes(), certEntity, privateKey,
                signatureAlgorithm, hashAlgo, keyAlgoForSig, false);

        SignatureValue signatureValue = new SignatureValue(
                SignatureAlgorithm.getAlgorithm(params.getEncryptionAlgorithm(), params.getDigestAlgorithm()),
                dssSignatureBytes);

        DSSDocument signedDoc = padesService.signDocument(documentToSign, params, signatureValue);

        return Base64.getEncoder().encodeToString(toByteArray(signedDoc));
    }

    /**
     * Signs an XML document using EU DSS XAdES
     */
    private String signDocumentWithXAdES(byte[] documentBytes, X509Certificate certificate,
            SigningCertificate certEntity, PrivateKey privateKey, String hashAlgo,
            String signatureAlgorithm, String keyAlgoForSig) throws Exception {

        XAdESSignatureParameters params = new XAdESSignatureParameters();
        params.setSignatureLevel(SignatureLevel.XAdES_BASELINE_B);
        params.setSignaturePackaging(SignaturePackaging.ENVELOPED);
        params.setDigestAlgorithm(mapHashAlgorithm(hashAlgo));
        params.setSigningCertificate(new CertificateToken(certificate));

        CommonCertificateVerifier verifier = new CommonCertificateVerifier();
        XAdESService xadesService = new XAdESService(verifier);

        InMemoryDocument documentToSign = new InMemoryDocument(documentBytes);
        ToBeSigned dataToSign = xadesService.getDataToSign(documentToSign, params);

        // Sign the ToBeSigned bytes using the appropriate backend
        byte[] dssSignatureBytes = signRawBytes(dataToSign.getBytes(), certEntity, privateKey,
                signatureAlgorithm, hashAlgo, keyAlgoForSig, false);

        SignatureValue signatureValue = new SignatureValue(
                SignatureAlgorithm.getAlgorithm(params.getEncryptionAlgorithm(), params.getDigestAlgorithm()),
                dssSignatureBytes);

        DSSDocument signedDoc = xadesService.signDocument(documentToSign, params, signatureValue);

        return Base64.getEncoder().encodeToString(toByteArray(signedDoc));
    }

    /**
     * Signs raw bytes using the appropriate backend (AWSKMS, PKCS11, or BCFKS)
     * @param data The bytes to sign
     * @param certEntity The certificate entity with storage type info
     * @param privateKey The private key (null for AWSKMS)
     * @param signatureAlgorithm The JCA signature algorithm name
     * @param hashAlgo The hash algorithm
     * @param keyAlgoForSig The key algorithm type (RSA/EC)
     * @param isDigest true if data is a pre-computed digest, false if raw data
     */
    private byte[] signRawBytes(byte[] data, SigningCertificate certEntity, PrivateKey privateKey,
            String signatureAlgorithm, String hashAlgo, String keyAlgoForSig, boolean isDigest) throws Exception {

        if ("AWSKMS".equals(certEntity.getStorageType())) {
            if (awskmsService == null) {
                throw new SigningException("AWS KMS is not enabled or configured");
            }
            if (isDigest) {
                return awskmsService.signDigest(certEntity.getKmsKeyId(), data, hashAlgo, keyAlgoForSig);
            } else {
                return awskmsService.signData(certEntity.getKmsKeyId(), data, hashAlgo, keyAlgoForSig);
            }
        } else {
            // For PKCS#11 and BCFKS, use Java cryptography with the appropriate provider
            Signature signature;
            if ("PKCS11".equals(certEntity.getStorageType())) {
                signature = Signature.getInstance(signatureAlgorithm, certEntity.getProviderName());
            } else {
                // BCFKS: use BCFIPS provider for FIPS-compliant signing
                signature = Signature.getInstance(signatureAlgorithm, "BCFIPS");
            }
            signature.initSign(privateKey);
            signature.update(data);
            return signature.sign();
        }
    }

    /**
     * Converts a DSSDocument to a byte array
     */
    private byte[] toByteArray(DSSDocument document) throws Exception {
        try (InputStream is = document.openStream();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            return baos.toByteArray();
        }
    }
    
    /**
     * Get the status of an asynchronous signing operation
     * Uses AsyncOperation entity instead of SigningLog
     */
    @Transactional(readOnly = true)
    public CSCSignatureStatusResponse getSignatureStatus(CSCSignatureStatusRequest request) {
        try {
            String clientId = request.getClientId();
            String operationId = request.getTransactionID();

            // Get operation (checks cache first, then DB)
            AsyncOperation operation = asyncOperationService
                    .getOperation(operationId, clientId)
                    .orElseThrow(() -> new SigningException("Operation not found"));

            // Build base response
            CSCSignatureStatusResponse.CSCSignatureStatusResponseBuilder builder =
                    CSCSignatureStatusResponse.builder()
                            .status(operation.getStatus());

            // Add error message if failed
            if (AsyncOperationService.STATUS_FAILED.equals(operation.getStatus())) {
                builder.errorMessage(operation.getErrorMessage());
            }

            // Add results if completed
            if (AsyncOperationService.STATUS_COMPLETED.equals(operation.getStatus()) &&
                    operation.getResultData() != null) {

                // Deserialize based on operation type
                if (AsyncOperationService.TYPE_SIGN_HASH.equals(operation.getOperationType())) {
                    CSCSignatureResponse result = asyncOperationService.deserializeResult(
                            operation.getResultData(),
                            CSCSignatureResponse.class
                    );
                    builder.signatures(result.getSignatures())
                            .signatureAlgorithm(result.getSignatureAlgorithm());

                } else if (AsyncOperationService.TYPE_SIGN_DOCUMENT.equals(operation.getOperationType())) {
                    CSCSignDocumentResponse result = asyncOperationService.deserializeResult(
                            operation.getResultData(),
                            CSCSignDocumentResponse.class
                    );
                    builder.signedDocument(result.getSignedDocument())
                            .signedDocumentDigest(result.getSignedDocumentDigest())
                            .transactionID(result.getTransactionID())
                            .signatureAlgorithm(result.getSignatureAlgorithm());
                }
            }

            return builder.build();

        } catch (SigningException se) {
            throw se;
        } catch (Exception e) {
            log.error("Error in getSignatureStatus", e);
            throw new SigningException("Failed to get signature status: " + e.getMessage(), e);
        }
    }
    
    /**
     * Create a timestamp for a document or hash
     */
    @Transactional
    public CSCTimestampResponse createTimestamp(CSCTimestampRequest request) {
        try {
            // Translate hash algorithm OID to JCA name
            String hashAlgo = OIDMapper.toJcaHashAlgo(request.getHashAlgo());
            
            byte[] digestBytes;
            
            // Either document or digest must be provided
            if (request.getDocumentDigest() != null) {
                // Use provided digest
                digestBytes = Base64.getDecoder().decode(request.getDocumentDigest());
            } else if (request.getDocument() != null) {
                // Calculate digest from document
                byte[] documentBytes = Base64.getDecoder().decode(request.getDocument());
                MessageDigest digest = MessageDigest.getInstance(hashAlgo);
                digestBytes = digest.digest(documentBytes);
            } else {
                throw new SigningException("Either document or documentDigest must be provided");
            }
            
            // Create TSP source
            OnlineTSPSource tspSource = new OnlineTSPSource(tspUrl);
            
            // Create a DSS document from the digest
            DigestAlgorithm digestAlgorithm = mapHashAlgorithm(hashAlgo);
            
            // Get timestamp token using the correct method
            TimestampBinary timeStampToken = 
                    tspSource.getTimeStampResponse(digestAlgorithm, digestBytes);
            
            byte[] timestampTokenBytes = timeStampToken.getBytes();
            
            String timestampToken = Base64.getEncoder().encodeToString(timestampTokenBytes);
            String timestampDigest = Base64.getEncoder().encodeToString(digestBytes);
            
            // Return response
            return CSCTimestampResponse.builder()
                    .timestampToken(timestampToken)
                    .timestampDigest(timestampDigest)
                    .timestampGenerationTime(Instant.now().toEpochMilli())
                    .build();
            
        } catch (Exception e) {
            log.error("Error in createTimestamp", e);
            throw new SigningException("Failed to create timestamp: " + e.getMessage(), e);
        }
    }
    


    /**
     * Validate a signature against a certificate
     */
    public CSCVerifyResponse validateSignature(CSCVerifyRequest request) {
        try {
            // Decode certificate from Base64 DER
            byte[] certBytes = Base64.getDecoder().decode(request.getCertificate());
            java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
            X509Certificate certificate = (X509Certificate) cf.generateCertificate(
                    new java.io.ByteArrayInputStream(certBytes));

            // Decode signature from Base64
            byte[] signatureBytes = Base64.getDecoder().decode(request.getSignature());

            // Compute or decode document digest
            byte[] digestBytes;
            String hashAlgo = OIDMapper.toJcaHashAlgo(request.getHashAlgo());
            if (request.getDocumentDigest() != null && !request.getDocumentDigest().isBlank()) {
                digestBytes = Base64.getDecoder().decode(request.getDocumentDigest());
            } else {
                throw new SigningException("documentDigest is required for signature validation");
            }

            // Determine JCA signature algorithm
            String jcaSigAlgo;
            if (request.getSignatureAlgorithm() != null && !request.getSignatureAlgorithm().isBlank()) {
                jcaSigAlgo = OIDMapper.toJcaSigAlgo(request.getSignatureAlgorithm());
            } else {
                throw new SigningException("signatureAlgorithm is required for signature validation");
            }

            // Verify using certificate's public key
            java.security.Signature sig = java.security.Signature.getInstance(jcaSigAlgo);
            sig.initVerify(certificate.getPublicKey());
            sig.update(digestBytes);
            boolean valid = sig.verify(signatureBytes);

            // Check certificate validity dates
            String certStatus;
            try {
                certificate.checkValidity();
                certStatus = "valid";
            } catch (java.security.cert.CertificateExpiredException e) {
                certStatus = "expired";
                valid = false;
            } catch (java.security.cert.CertificateNotYetValidException e) {
                certStatus = "expired";
                valid = false;
            }

            return CSCVerifyResponse.builder()
                    .valid(valid)
                    .certificateStatus(certStatus)
                    .signedBy(certificate.getSubjectX500Principal().getName())
                    .build();

        } catch (SigningException se) {
            throw se;
        } catch (Exception e) {
            log.error("Error in validateSignature", e);
            throw new SigningException("Failed to validate signature: " + e.getMessage(), e);
        }
    }

    /**
     * Maps a hash algorithm name to DSS DigestAlgorithm enum
     */
    private DigestAlgorithm mapHashAlgorithm(String hashAlgo) {
        String normalized = hashAlgo.toUpperCase().replace("-", "");
        
        switch (normalized) {
            case "SHA256":
                return DigestAlgorithm.SHA256;
            case "SHA384":
                return DigestAlgorithm.SHA384;
            case "SHA512":
                return DigestAlgorithm.SHA512;
            default:
                throw new SigningException("Unsupported hash algorithm for timestamping: " + hashAlgo);
        }
    }

    /**
     * Extracts PIN from CSC request
     */
    private String extractPinFromRequest(CSCSignDocumentRequest request) {
        if (request.getCredentials() != null && 
            request.getCredentials().getPin() != null && 
            request.getCredentials().getPin().getValue() != null) {
            return request.getCredentials().getPin().getValue();
        }
        return null;
    }
    
    /**
     * Internal class for tracking asynchronous signing operations
     */
    @Data
    @Builder
    private static class SigningOperation {
        private String id;
        private String clientId;
        private String status;
        private String errorMessage;
        private Instant createdAt;
        private Instant updatedAt;
        private byte[] signatureResult;
    }
}