package com.wpanther.eidasremotesigning.service;

import com.wpanther.eidasremotesigning.dto.DigestSigningRequest;
import com.wpanther.eidasremotesigning.dto.csc.*;
import com.wpanther.eidasremotesigning.entity.AsyncOperation;
import com.wpanther.eidasremotesigning.entity.SigningCertificate;
import com.wpanther.eidasremotesigning.entity.SigningLog;
import com.wpanther.eidasremotesigning.entity.TransactionAuthorization;
import com.wpanther.eidasremotesigning.exception.SigningException;
import com.wpanther.eidasremotesigning.exception.SigningInProgressException;
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

import org.springframework.security.core.context.SecurityContextHolder;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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

    private String currentClientId() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

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
                currentClientId(),
                AsyncOperationService.TYPE_SIGN_DOCUMENT,
                operationExpiryMinutes
        );

        // Submit async task
        CompletableFuture.runAsync(() -> executeAsyncSignDocument(operation.getId(), request), asyncExecutor);

        // Return immediately with operationID
        return CSCSignDocumentResponse.builder()
                .responseID(operation.getId())
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
            if (Boolean.TRUE.equals(request.getReturnValidationInfo())) {
                log.warn("returnValidationInfo=true is not supported; validationInfo will not be included in response");
            }

            String clientId = currentClientId();
            String credentialId = request.getCredentialID();

            // Check if we have a SAD or PIN
            if (request.getSAD() == null) {
                throw new SigningException("SAD is required for signing operations");
            }

            // Validate transaction using SAD lookup
            TransactionAuthorization transaction = cscAuthorizationService.validateTransactionForSigningBySad(
                    clientId, request.getSAD());

            // Make sure the credential IDs match
            if (!transaction.getCertificateId().equals(credentialId)) {
                throw new SigningException("Credential ID does not match authorized transaction");
            }

            // Find the certificate (with client ownership check)
            SigningCertificate certEntity = certificateRepository.findByIdAndClientId(credentialId, clientId)
                    .orElseThrow(() -> new SigningException("Certificate not found"));

            // Verify certificate is active
            if (!certEntity.isActive()) {
                throw new SigningException("Certificate is not active");
            }

            // Get stored PIN from transaction (SAD was already validated)
            String pin = transaction.getStoredPin();

            // Load certificate and private key
            X509Certificate certificate;
            PrivateKey privateKey = null;  // Will be null for AWS KMS

            if ("AWSKMS".equals(certEntity.getStorageType())) {
                // For AWS KMS, no PIN needed
                certificate = certificateService.getCertificateWithX509(credentialId, null)
                        .getX509Certificate();
            } else {
                // For PKCS#11/BCFKS, PIN is required
                if (pin == null || pin.isEmpty()) {
                    throw new SigningException("PIN was not captured during credential authorization");
                }
                certificate = certificateService.getCertificateWithX509(credentialId, pin)
                        .getX509Certificate();
                privateKey = certificateService.getPrivateKey(credentialId, pin);
            }

            // Determine signature algorithm
            String keyAlgoForSig;
            if ("AWSKMS".equals(certEntity.getStorageType())) {
                if (awskmsService == null) {
                    throw new SigningException("AWS KMS is not enabled or configured");
                }
                keyAlgoForSig = awskmsService.getKeyAlgorithmType(certEntity.getKmsKeyId());
            } else {
                keyAlgoForSig = privateKey.getAlgorithm();
            }

            // Prepare results list
            List<String> signedDocuments = new ArrayList<>();
            List<String> signatureObjects = new ArrayList<>();

            // Check if we have documentDigests (digest-only signing) or documents (full document signing)
            List<CSCSignDocumentRequest.DocumentDigestEntry> digestEntries = request.getDocumentDigests();
            List<CSCSignDocumentRequest.DocumentEntry> documentEntries = request.getDocuments();

            if (digestEntries != null && !digestEntries.isEmpty()) {
                // Digest-only signing path
                for (CSCSignDocumentRequest.DocumentDigestEntry entry : digestEntries) {
                    String hashAlgoOid = entry.getHashAlgorithmOID();
                    String hashAlgo = OIDMapper.toJcaHashAlgo(hashAlgoOid);
                    String signAlgoOid = entry.getSignAlgo();
                    String jcaSigAlgo = OIDMapper.toJcaSigAlgo(signAlgoOid);

                    // Validate eIDAS compliance for first digest entry
                    if (signedDocuments.isEmpty()) {
                        DigestSigningRequest validationRequest = DigestSigningRequest.builder()
                                .certificateId(credentialId)
                                .digestValue(entry.getHashes().get(0))
                                .digestAlgorithm(hashAlgo)
                                .signatureType(DigestSigningRequest.SignatureType.XADES)
                                .build();
                        eidasComplianceService.validateEIDASCompliance(validationRequest, certificate);
                    }

                    // Sign each hash
                    List<String> signatures = new ArrayList<>();
                    for (String hashBase64 : entry.getHashes()) {
                        byte[] hashBytes = Base64.getDecoder().decode(hashBase64);
                        byte[] signatureBytes = signRawBytes(hashBytes, certEntity, privateKey,
                                jcaSigAlgo, hashAlgo, keyAlgoForSig, true);
                        signatures.add(Base64.getEncoder().encodeToString(signatureBytes));
                    }

                    // For digest-only, signatureObject contains the raw signatures
                    signatureObjects.add(String.join(",", signatures));

                    // Log successful signing
                    signingLogService.logSuccessfulSigning(
                            DigestSigningRequest.builder()
                                    .certificateId(credentialId)
                                    .digestAlgorithm(hashAlgo)
                                    .signatureType(DigestSigningRequest.SignatureType.XADES)
                                    .build(),
                            jcaSigAlgo);
                }
            } else if (documentEntries != null && !documentEntries.isEmpty()) {
                // Full document signing path
                for (CSCSignDocumentRequest.DocumentEntry entry : documentEntries) {
                    String signatureFormat = entry.getSignature_format();
                    String signAlgoOid = entry.getSignAlgo();
                    String jcaSigAlgo = OIDMapper.toJcaSigAlgo(signAlgoOid);

                    // Determine hash algorithm from signAlgo
                    String hashAlgo = OIDMapper.toJcaHashAlgoForSig(signAlgoOid);

                    // Decode document
                    byte[] documentBytes = Base64.getDecoder().decode(entry.getDocument());

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

                    // Calculate digest
                    MessageDigest digest = MessageDigest.getInstance(hashAlgo);
                    byte[] digestBytes = digest.digest(documentBytes);

                    // Determine signature type from format
                    DigestSigningRequest.SignatureType signatureType =
                            "PADES".equalsIgnoreCase(signatureFormat) ?
                                    DigestSigningRequest.SignatureType.PADES :
                                    DigestSigningRequest.SignatureType.XADES;

                    // Validate eIDAS compliance
                    DigestSigningRequest validationRequest = DigestSigningRequest.builder()
                            .certificateId(credentialId)
                            .digestValue(Base64.getEncoder().encodeToString(digestBytes))
                            .digestAlgorithm(hashAlgo)
                            .signatureType(signatureType)
                            .build();
                    eidasComplianceService.validateEIDASCompliance(validationRequest, certificate);

                    // Sign document using EU DSS
                    String signedDocumentBase64;
                    if (DigestSigningRequest.SignatureType.PADES == signatureType) {
                        signedDocumentBase64 = signDocumentWithPAdES(documentBytes, certificate, certEntity,
                                privateKey, hashAlgo, jcaSigAlgo, keyAlgoForSig);
                    } else {
                        signedDocumentBase64 = signDocumentWithXAdES(documentBytes, certificate, certEntity,
                                privateKey, hashAlgo, jcaSigAlgo, keyAlgoForSig);
                    }

                    signedDocuments.add(signedDocumentBase64);
                    // No signatureObject entry: EU DSS embeds the signature into the document

                    // Log successful signing
                    signingLogService.logSuccessfulSigning(validationRequest, jcaSigAlgo);
                }

                // Return response for full document signing (signature embedded by EU DSS)
                return CSCSignDocumentResponse.builder()
                        .documentWithSignature(signedDocuments)
                        .build();

            } else {
                throw new SigningException("Either documentDigests or documents must be provided");
            }

            // Return response for digest-only signing
            return CSCSignDocumentResponse.builder()
                    .documentWithSignature(signedDocuments.isEmpty() ? null : signedDocuments)
                    .signatureObject(signatureObjects.isEmpty() ? null : signatureObjects)
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
            if (request.getHash() != null) {
                // Use provided hash
                digestBytes = Base64.getDecoder().decode(request.getHash());
            } else if (request.getNonce() != null) {
                // Calculate digest from document
                byte[] documentBytes = Base64.getDecoder().decode(request.getNonce());
                MessageDigest digest = MessageDigest.getInstance(hashAlgo);
                digestBytes = digest.digest(documentBytes);
            } else {
                throw new SigningException("Either hash or nonce must be provided");
            }
            
            // Create TSP source
            OnlineTSPSource tspSource = new OnlineTSPSource(tspUrl);
            
            // Create a DSS document from the digest
            DigestAlgorithm digestAlgorithm = mapHashAlgorithm(hashAlgo);
            
            // Get timestamp token using the correct method
            TimestampBinary timeStampToken = 
                    tspSource.getTimeStampResponse(digestAlgorithm, digestBytes);
            
            byte[] timestampTokenBytes = timeStampToken.getBytes();
            
            String timestamp = Base64.getEncoder().encodeToString(timestampTokenBytes);

            // Return response
            return CSCTimestampResponse.builder()
                    .timestamp(timestamp)
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
                certStatus = CSCConstants.CERT_STATUS_VALID;
            } catch (java.security.cert.CertificateExpiredException e) {
                certStatus = CSCConstants.CERT_STATUS_EXPIRED;
                valid = false;
            } catch (java.security.cert.CertificateNotYetValidException e) {
                certStatus = CSCConstants.CERT_STATUS_EXPIRED;
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