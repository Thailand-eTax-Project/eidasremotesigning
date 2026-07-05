package com.wpanther.eidasremotesigning.service;

import com.wpanther.eidasremotesigning.dto.DigestSigningRequest;
import com.wpanther.eidasremotesigning.dto.csc.*;
import com.wpanther.eidasremotesigning.entity.AsyncOperation;
import com.wpanther.eidasremotesigning.entity.SigningCertificate;
import com.wpanther.eidasremotesigning.entity.SigningLog;
import com.wpanther.eidasremotesigning.entity.TransactionAuthorization;
import com.wpanther.eidasremotesigning.exception.CSCInvalidRequestException;
import com.wpanther.eidasremotesigning.exception.CSCUnsupportedOperationException;
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
import eu.europa.esig.dss.spi.x509.tsp.TSPSource;
import eu.europa.esig.dss.validation.CertificateVerifier;
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

    private final CertificateVerifier certificateVerifier;

    private final TSPSource tspSource;

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
                                 CertificateVerifier certificateVerifier,
                                 TSPSource tspSource,
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
        this.certificateVerifier = certificateVerifier;
        this.tspSource = tspSource;
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

                    // documentDigests[] returns raw signatures; only Baseline-B applies.
                    // Anything stricter is a misuse (documentDigests do not produce
                    // an AdES container) -> 400 invalid_request.
                    ConformanceLevel digestLevel = mapConformanceLevel(entry.getConformance_level());
                    if (digestLevel != ConformanceLevel.B) {
                        throw new CSCInvalidRequestException(
                                "conformance_level " + entry.getConformance_level()
                                        + " is not supported for documentDigests (raw signature path)");
                    }

                    // Determine signature type from the CSC wire value (P/X/PADES/XADES).
                    DigestSigningRequest.SignatureType signatureType = mapSignatureFormat(entry.getSignature_format());

                    // Validate eIDAS compliance for first digest entry
                    if (signedDocuments.isEmpty()) {
                        DigestSigningRequest validationRequest = DigestSigningRequest.builder()
                                .certificateId(credentialId)
                                .digestValue(entry.getHashes().get(0))
                                .digestAlgorithm(hashAlgo)
                                .signatureType(signatureType)
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
                                    .signatureType(signatureType)
                                    .build(),
                            jcaSigAlgo);
                }
            } else if (documentEntries != null && !documentEntries.isEmpty()) {
                // Full document signing path
                for (CSCSignDocumentRequest.DocumentEntry entry : documentEntries) {
                    String signatureFormat = entry.getSignature_format();
                    String signAlgoOid = entry.getSignAlgo();
                    String jcaSigAlgo = OIDMapper.toJcaSigAlgo(signAlgoOid);

                    // 1) signature_format (may throw CSCUnsupportedOperationException -> 400 unsupported_operation)
                    DigestSigningRequest.SignatureType signatureType = mapSignatureFormat(signatureFormat);

                    // 2) conformance_level (may throw CSCInvalidRequestException -> 400 invalid_request)
                    ConformanceLevel conformanceLevel = mapConformanceLevel(entry.getConformance_level());

                    // 3) AWSKMS supports only Baseline-B
                    if ("AWSKMS".equals(certEntity.getStorageType()) && conformanceLevel != ConformanceLevel.B) {
                        throw new CSCInvalidRequestException(
                                "conformance_level " + entry.getConformance_level()
                                        + " is not supported for AWS KMS credentials");
                    }

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

                    // 4) Load the chain (BCFKS/PKCS#11; single EE for AWSKMS-B).
                    //    Needed before eIDAS validation so downstream signing can embed
                    //    the chain at LT/LTA. Even though the validation request only
                    //    takes the EE cert, fetching the chain up-front keeps the order
                    //    stable and surfaces keystore/chain issues before signDocument.
                    X509Certificate[] chain = certificateService.getCertificateChain(credentialId, pin);

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
                                privateKey, hashAlgo, jcaSigAlgo, keyAlgoForSig,
                                conformanceLevel, chain);
                    } else {
                        signedDocumentBase64 = signDocumentWithXAdES(documentBytes, certificate, certEntity,
                                privateKey, hashAlgo, jcaSigAlgo, keyAlgoForSig,
                                conformanceLevel, chain);
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
     * Signs a PDF document using EU DSS PAdES.
     *
     * <p>The two-phase DSS contract: {@link PAdESService#getDataToSign(InMemoryDocument,
     * PAdESSignatureParameters)} returns the {@link ToBeSigned} pre-image; the caller
     * signs those raw bytes (via {@link #signRawBytes} with {@code isDigest=false}); DSS
     * wraps the result plus any required timestamps/validation material. The level
     * parameter selects the DSS {@link SignatureLevel} (B / T / LT / LTA). The chain
     * parameter is required for LT/LTA so DSS can embed the validation material.
     *
     * <p>For {@code PAdES_BASELINE_LTA} the {@code signDocument} call only produces an
     * {@code PAdES_BASELINE_LT} signature (cert refs + revocation + signature timestamp);
     * the archive timestamp is added by a separate {@code extendDocument} call. (XAdES
     * does this inline; PAdES does not.) Without the extension the signature reports
     * as {@code PAdES_BASELINE_LT} even when the caller requested LTA.
     */
    private String signDocumentWithPAdES(byte[] documentBytes, X509Certificate certificate,
            SigningCertificate certEntity, PrivateKey privateKey, String hashAlgo,
            String signatureAlgorithm, String keyAlgoForSig,
            ConformanceLevel level, X509Certificate[] chain) throws Exception {

        PAdESSignatureParameters params = new PAdESSignatureParameters();
        params.setSignatureLevel(resolvePAdESLevel(level));
        params.setDigestAlgorithm(mapHashAlgorithm(hashAlgo));
        params.setSigningCertificate(new CertificateToken(certificate));
        params.setCertificateChain(toCertTokens(chain));

        PAdESService padesService = new PAdESService(certificateVerifier);
        padesService.setTspSource(tspSource);

        InMemoryDocument documentToSign = new InMemoryDocument(documentBytes);
        ToBeSigned dataToSign = padesService.getDataToSign(documentToSign, params);

        // Sign the ToBeSigned bytes using the appropriate backend
        byte[] dssSignatureBytes = signRawBytes(dataToSign.getBytes(), certEntity, privateKey,
                signatureAlgorithm, hashAlgo, keyAlgoForSig, false);

        SignatureValue signatureValue = new SignatureValue(
                SignatureAlgorithm.getAlgorithm(params.getEncryptionAlgorithm(), params.getDigestAlgorithm()),
                dssSignatureBytes);

        DSSDocument signedDoc = padesService.signDocument(documentToSign, params, signatureValue);

        // LTA extension: archive timestamp on top of the LT material.
        if (level == ConformanceLevel.LTA) {
            signedDoc = padesService.extendDocument(signedDoc, params);
        }

        return Base64.getEncoder().encodeToString(toByteArray(signedDoc));
    }

    /**
     * Signs an XML document using EU DSS XAdES. Same two-phase contract as
     * {@link #signDocumentWithPAdES}; the level drives the DSS
     * {@link SignatureLevel}, the chain is embedded for LT/LTA.
     */
    private String signDocumentWithXAdES(byte[] documentBytes, X509Certificate certificate,
            SigningCertificate certEntity, PrivateKey privateKey, String hashAlgo,
            String signatureAlgorithm, String keyAlgoForSig,
            ConformanceLevel level, X509Certificate[] chain) throws Exception {

        XAdESSignatureParameters params = new XAdESSignatureParameters();
        params.setSignatureLevel(resolveXAdESLevel(level));
        params.setSignaturePackaging(SignaturePackaging.ENVELOPED);
        params.setDigestAlgorithm(mapHashAlgorithm(hashAlgo));
        params.setSigningCertificate(new CertificateToken(certificate));
        params.setCertificateChain(toCertTokens(chain));

        XAdESService xadesService = new XAdESService(certificateVerifier);
        xadesService.setTspSource(tspSource);

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
     * Resolves a {@link ConformanceLevel} to its PAdES {@link SignatureLevel} equivalent.
     * Switch over the small enum keeps this exhaustively checked at compile time.
     */
    private static SignatureLevel resolvePAdESLevel(ConformanceLevel level) {
        switch (level) {
            case B:   return SignatureLevel.PAdES_BASELINE_B;
            case T:   return SignatureLevel.PAdES_BASELINE_T;
            case LT:  return SignatureLevel.PAdES_BASELINE_LT;
            case LTA: return SignatureLevel.PAdES_BASELINE_LTA;
            default:  throw new IllegalStateException("Unhandled ConformanceLevel: " + level);
        }
    }

    /**
     * Resolves a {@link ConformanceLevel} to its XAdES {@link SignatureLevel} equivalent.
     * Switch over the small enum keeps this exhaustively checked at compile time.
     */
    private static SignatureLevel resolveXAdESLevel(ConformanceLevel level) {
        switch (level) {
            case B:   return SignatureLevel.XAdES_BASELINE_B;
            case T:   return SignatureLevel.XAdES_BASELINE_T;
            case LT:  return SignatureLevel.XAdES_BASELINE_LT;
            case LTA: return SignatureLevel.XAdES_BASELINE_LTA;
            default:  throw new IllegalStateException("Unhandled ConformanceLevel: " + level);
        }
    }

    /**
     * Converts a {@link X509Certificate} chain to DSS {@link CertificateToken} list,
     * skipping null entries. Used by the PAdES/XAdES signing methods to attach the chain
     * to the DSS signature parameters for LT/LTA validation material embedding.
     */
    private static List<CertificateToken> toCertTokens(X509Certificate[] chain) {
        List<CertificateToken> list = new ArrayList<>();
        if (chain != null) {
            for (X509Certificate c : chain) {
                if (c != null) {
                    list.add(new CertificateToken(c));
                }
            }
        }
        return list;
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
                // BCFKS: use the BC (BouncyCastle) provider
                signature = Signature.getInstance(signatureAlgorithm, "BC");
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
            
            // Create a DSS document from the digest
            DigestAlgorithm digestAlgorithm = mapHashAlgorithm(hashAlgo);
            
            // Get timestamp token using the correct method (using injected TSPSource)
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

            // Validate signatureAlgorithm OID (required by spec) and confirm its hash
            // component matches the supplied hashAlgo. The actual verification uses
            // raw NONEwithRSA/ECDSA over the pre-computed digest, so jcaSigAlgo itself
            // is not fed to Signature.getInstance — this guard just rejects callers
            // who pass inconsistent hashAlgo + signatureAlgorithm.
            if (request.getSignatureAlgorithm() == null || request.getSignatureAlgorithm().isBlank()) {
                throw new SigningException("signatureAlgorithm is required for signature validation");
            }
            String jcaSigAlgo = OIDMapper.toJcaSigAlgo(request.getSignatureAlgorithm());
            if (!jcaSigAlgo.toUpperCase().contains(hashAlgo.toUpperCase().replace("-", ""))) {
                throw new SigningException(
                        "signatureAlgorithm (" + jcaSigAlgo + ") does not match hashAlgo (" + hashAlgo + ")");
            }

            // The supplied documentDigest is ALREADY a hash. Verifying it with a
            // hash-and-verify JCA algorithm (e.g. SHA256withRSA via update(digest))
            // would hash it a second time, checking the signature against
            // SHA256(digest). This must mirror the signHash fix (commit b26d64a):
            // verify the raw digest with NONEwithRSA/ECDSA, wrapping RSA digests in
            // their PKCS#1 v1.5 DigestInfo first — exactly how a real CMS/XAdES/
            // PAdES verifier treats the signature.
            String keyAlgo = certificate.getPublicKey().getAlgorithm();
            boolean valid = verifyPreComputedDigest(
                    digestBytes, hashAlgo, keyAlgo,
                    certificate.getPublicKey(), signatureBytes);

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
     * Verifies a signature over a PRE-COMPUTED digest without re-hashing it. Mirrors
     * {@code CSCApiService.signPreComputedDigest}: RSA keys verify a PKCS#1 v1.5
     * DigestInfo wrapping of the digest with NONEwithRSA (so a signature produced by
     * {@code <hash>withRSA} verifies); EC keys verify the raw digest with NONEwithECDSA.
     * Using a hash-and-verify JCA algorithm here would double-hash and falsely reject
     * every legitimate CSC signHash signature.
     */
    private boolean verifyPreComputedDigest(byte[] digest, String hashAlgo, String keyAlgo,
            java.security.PublicKey publicKey, byte[] signatureBytes) throws Exception {
        String key = keyAlgo == null ? "" : keyAlgo.toUpperCase();
        byte[] toVerify;
        String rawAlgo;
        if (key.startsWith("RSA")) {
            toVerify = rsaDigestInfo(hashAlgo, digest);
            rawAlgo = "NONEwithRSA";
        } else if (key.startsWith("EC")) {
            toVerify = digest;
            rawAlgo = "NONEwithECDSA";
        } else {
            throw new SigningException("Unsupported key algorithm for digest verification: " + keyAlgo);
        }
        java.security.Signature sig = java.security.Signature.getInstance(rawAlgo);
        sig.initVerify(publicKey);
        sig.update(toVerify);
        return sig.verify(signatureBytes);
    }

    /** Wraps a digest in its PKCS#1 v1.5 DigestInfo DER structure for raw RSA verification. */
    private static byte[] rsaDigestInfo(String hashAlgo, byte[] digest) {
        String h = hashAlgo == null ? "" : hashAlgo.toUpperCase().replace("-", "");
        String prefixHex = switch (h) {
            case "SHA256" -> "3031300d060960864801650304020105000420";
            case "SHA384" -> "3041300d060960864801650304020205000430";
            case "SHA512" -> "3051300d060960864801650304020305000440";
            default -> throw new SigningException("Unsupported hash for RSA DigestInfo: " + hashAlgo);
        };
        byte[] prefix = java.util.HexFormat.of().parseHex(prefixHex);
        byte[] out = new byte[prefix.length + digest.length];
        System.arraycopy(prefix, 0, out, 0, prefix.length);
        System.arraycopy(digest, 0, out, prefix.length, digest.length);
        return out;
    }

    enum ConformanceLevel { B, T, LT, LTA }

    /**
     * Maps a CSC v2.0 conformance_level wire value to the internal level.
     * null/blank -> B (default). Accepted (case-insensitive, trimmed):
     * Ades-B-B/Ades-B -> B; Ades-B-T/Ades-T -> T; Ades-B-LT/Ades-LT -> LT;
     * Ades-B-LTA/Ades-LTA -> LTA. Anything else -> CSCInvalidRequestException.
     */
    static ConformanceLevel mapConformanceLevel(String conformanceLevel) {
        if (conformanceLevel == null || conformanceLevel.isBlank()) {
            return ConformanceLevel.B;
        }
        switch (conformanceLevel.trim().toUpperCase()) {
            case "ADES-B-B":
            case "ADES-B":
                return ConformanceLevel.B;
            case "ADES-B-T":
            case "ADES-T":
                return ConformanceLevel.T;
            case "ADES-B-LT":
            case "ADES-LT":
                return ConformanceLevel.LT;
            case "ADES-B-LTA":
            case "ADES-LTA":
                return ConformanceLevel.LTA;
            default:
                throw new CSCInvalidRequestException(
                        "Unsupported conformance_level: " + conformanceLevel
                                + " (supported: Ades-B-B, Ades-B-T, Ades-B-LT, Ades-B-LTA)");
        }
    }

    /**
     * Maps a CSC v2.0 signature_format wire value to the internal SignatureType.
     * Accepted (case-insensitive): "P"/"PADES" -> PADES, "X"/"XADES" -> XADES.
     * Everything else (including "C", "J", null) throws CSCUnsupportedOperationException,
     * which GlobalExceptionHandler maps to HTTP 400 "unsupported_operation".
     */
    static DigestSigningRequest.SignatureType mapSignatureFormat(String signatureFormat) {
        if (signatureFormat == null) {
            throw new CSCUnsupportedOperationException(
                    "signature_format is required (supported: P, X)");
        }
        switch (signatureFormat.trim().toUpperCase()) {
            case "P":
            case "PADES":
                return DigestSigningRequest.SignatureType.PADES;
            case "X":
            case "XADES":
                return DigestSigningRequest.SignatureType.XADES;
            default:
                throw new CSCUnsupportedOperationException(
                        "Unsupported signature_format: " + signatureFormat
                                + " (supported: P, X)");
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