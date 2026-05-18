package com.wpanther.eidasremotesigning.service;

import com.wpanther.eidasremotesigning.dto.DigestSigningRequest;
import com.wpanther.eidasremotesigning.dto.csc.*;
import com.wpanther.eidasremotesigning.entity.AsyncOperation;
import com.wpanther.eidasremotesigning.entity.SigningCertificate;
import com.wpanther.eidasremotesigning.entity.TransactionAuthorization;
import com.wpanther.eidasremotesigning.exception.CertificateException;
import com.wpanther.eidasremotesigning.exception.SigningException;
import com.wpanther.eidasremotesigning.repository.OAuth2ClientRepository;
import com.wpanther.eidasremotesigning.repository.SigningCertificateRepository;
import com.wpanther.eidasremotesigning.util.CSCConstants;
import com.wpanther.eidasremotesigning.util.OIDMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.security.core.context.SecurityContextHolder;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Service implementing the Cloud Signature Consortium API v2.0 functionality
 */
@Service
@Slf4j
public class CSCApiService {

    private final SigningCertificateRepository certificateRepository;
    private final SigningCertificateService certificateService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private PKCS11Service pkcs11Service;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private AWSKMSService awskmsService;
    private final EIDASComplianceService eidasComplianceService;
    private final SigningLogService signingLogService;
    private final OAuth2ClientRepository oauth2ClientRepository;
    private final AsyncOperationService asyncOperationService;
    private final CSCAuthorizationService cscAuthorizationService;

    private final Executor asyncExecutor;

    private int operationExpiryMinutes;

    public CSCApiService(SigningCertificateRepository certificateRepository,
                          SigningCertificateService certificateService,
                          EIDASComplianceService eidasComplianceService,
                          SigningLogService signingLogService,
                          OAuth2ClientRepository oauth2ClientRepository,
                          AsyncOperationService asyncOperationService,
                          CSCAuthorizationService cscAuthorizationService,
                          @Qualifier("asyncSigningExecutor") Executor asyncExecutor,
                          @Value("${app.async.operation-expiry-minutes:30}") int operationExpiryMinutes) {
        this.certificateRepository = certificateRepository;
        this.certificateService = certificateService;
        this.eidasComplianceService = eidasComplianceService;
        this.signingLogService = signingLogService;
        this.oauth2ClientRepository = oauth2ClientRepository;
        this.asyncOperationService = asyncOperationService;
        this.cscAuthorizationService = cscAuthorizationService;
        this.asyncExecutor = asyncExecutor;
        this.operationExpiryMinutes = operationExpiryMinutes;
    }

    private PKCS11Service requirePkcs11Service() {
        if (pkcs11Service == null) {
            throw new CertificateException("PKCS#11 is not enabled. Configure app.pkcs11.enabled=true and ensure SoftHSM is installed.");
        }
        return pkcs11Service;
    }

    private String currentClientId() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    @Transactional(readOnly = true)
    public CSCCredentialsListResponse listCredentials(CSCCredentialsListRequest request) {
        try {
            String clientId = currentClientId();
            List<SigningCertificate> certificates = certificateRepository.findByClientId(clientId);

            List<String> credentialIds = certificates.stream()
                    .map(SigningCertificate::getId)
                    .collect(java.util.stream.Collectors.toList());

            if (request.getMaxResults() != null && request.getMaxResults() > 0
                    && credentialIds.size() > request.getMaxResults()) {
                credentialIds = credentialIds.subList(0, request.getMaxResults());
            }

            return CSCCredentialsListResponse.builder()
                    .credentialIDs(credentialIds)
                    .build();
        } catch (Exception e) {
            log.error("Error in listCredentials", e);
            throw new CertificateException("Failed to list credentials: " + e.getMessage(), e);
        }
    }

    /**
     * Get detailed information about a specific credential (certificate)
     */
    @Transactional(readOnly = true)
    public CSCCertificateInfo getCredentialInfo(CSCCredentialsInfoRequest request) {
        try {
            String clientId = currentClientId();
            String credentialID = request.getCredentialID();
            String pin = extractPinFromRequest(request);

            SigningCertificate cert = certificateRepository.findByIdAndClientId(credentialID, clientId)
                    .orElseThrow(() -> new CertificateException("Certificate not found"));

            X509Certificate x509Cert;
            if ("PKCS11".equals(cert.getStorageType())) {
                if (pin == null) {
                    throw new CertificateException("PIN is required for PKCS#11 certificate access");
                }
                x509Cert = requirePkcs11Service().getCertificate(cert.getCertificateAlias(), pin);
            } else if ("AWSKMS".equals(cert.getStorageType())) {
                byte[] certBytes = Base64.getDecoder().decode(cert.getCertificateData());
                java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(certBytes);
                java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
                x509Cert = (X509Certificate) cf.generateCertificate(bis);
            } else {
                x509Cert = certificateService.loadCertificateFromBCFKS(cert);
            }

            return mapToCscCertificateInfo(cert, x509Cert);
        } catch (CertificateException ce) {
            throw ce;
        } catch (Exception e) {
            log.error("Error in getCredentialInfo", e);
            throw new CertificateException("Failed to get credential info: " + e.getMessage(), e);
        }
    }

    private String extractPinFromRequest(CSCCredentialsInfoRequest request) {
        if (request.getCredentials() != null
                && request.getCredentials().getPin() != null
                && request.getCredentials().getPin().getValue() != null) {
            return request.getCredentials().getPin().getValue();
        }
        return null;
    }

    /**
     * Sign hash(es) using the specified credential
     * Supports both synchronous and asynchronous modes
     */
    @Transactional
    public CSCSignatureResponse signHash(CSCSignatureRequest request) {
        // Check if async mode is requested
        if (CSCConstants.OPERATION_MODE_ASYNC.equals(request.getOperationMode())) {
            return signHashAsync(request);
        }

        // Execute synchronously for backward compatibility
        return executeSignHash(request);
    }

    /**
     * Handle asynchronous signHash request
     * Creates an async operation and returns operationID immediately
     */
    private CSCSignatureResponse signHashAsync(CSCSignatureRequest request) {
        // Create async operation
        AsyncOperation operation = asyncOperationService.createOperation(
                currentClientId(),
                AsyncOperationService.TYPE_SIGN_HASH,
                operationExpiryMinutes
        );

        // Submit async task
        CompletableFuture.runAsync(() -> executeAsyncSignHash(operation.getId(), request), asyncExecutor);

        // Return immediately with responseID
        return CSCSignatureResponse.builder()
                .responseID(operation.getId())
                .build();
    }

    /**
     * Execute signHash asynchronously in background thread
     * Updates AsyncOperation with result or error
     */
    @Async("asyncSigningExecutor")
    void executeAsyncSignHash(String operationId, CSCSignatureRequest request) {
        try {
            CSCSignatureResponse result = executeSignHash(request);
            asyncOperationService.updateOperationSuccess(operationId, result);
            log.info("Async signHash completed successfully: operationId={}", operationId);
        } catch (Exception e) {
            asyncOperationService.updateOperationFailure(operationId, e.getMessage());
            log.error("Async signHash failed: operationId={}", operationId, e);
        }
    }

    /**
     * Core logic for signing hash(es)
     * Shared by both sync and async execution paths
     */
    private CSCSignatureResponse executeSignHash(CSCSignatureRequest request) {
        try {
            String credentialID = request.getCredentialID();
            String pin = extractPinFromRequest(request);

            // Validate request
            if (request.getHashes() == null || request.getHashes().length == 0) {
                throw new SigningException("No hash values provided to sign");
            }

            // Either PIN or SAD is required
            if (request.getSAD() == null && (pin == null || pin.isEmpty())) {
                throw new SigningException("Either PIN or SAD is required for signing operations");
            }

            // Validate SAD if provided
            TransactionAuthorization transaction = null;
            if (request.getSAD() != null) {
                transaction = cscAuthorizationService.validateTransactionForSigningBySad(
                        currentClientId(), request.getSAD());
                if (!transaction.getCertificateId().equals(credentialID)) {
                    throw new SigningException("Credential ID does not match authorized transaction");
                }
            }

            // After SAD validation, get stored PIN from transaction (if available)
            if (pin == null || pin.isEmpty()) {
                pin = (transaction != null) ? transaction.getStoredPin() : null;
            }

            // Find the certificate (with client ownership check)
            SigningCertificate certEntity = certificateRepository.findByIdAndClientId(credentialID, currentClientId())
                    .orElseThrow(() -> new SigningException("Certificate not found"));

            // Verify certificate is active
            if (!certEntity.isActive()) {
                throw new SigningException("Certificate is not active");
            }

            // Load certificate and private key based on storage type
            PrivateKey privateKey = null;
            X509Certificate certificate;

            if ("AWSKMS".equals(certEntity.getStorageType())) {
                if (awskmsService == null) {
                    throw new SigningException("AWS KMS is not enabled or configured");
                }
                certificate = certificateService.getCertificateWithX509(credentialID, null)
                        .getX509Certificate();
            } else {
                if (pin == null || pin.isEmpty()) {
                    throw new SigningException("PIN was not captured during credential authorization");
                }
                privateKey = certificateService.getPrivateKey(credentialID, pin);
                certificate = certificateService.getCertificateWithX509(credentialID, pin)
                        .getX509Certificate();
            }

            // Validate hash algorithm — translate OID to JCA name
            String hashAlgoOid = request.getHashAlgorithmOID();
            String hashAlgo = OIDMapper.toJcaHashAlgo(hashAlgoOid);

            // Determine signature type (always XAdES for signHash — no more SignatureAttributes)
            DigestSigningRequest.SignatureType signatureType = DigestSigningRequest.SignatureType.XADES;

            // Create digest signing request for eIDAS compliance validation
            DigestSigningRequest validationRequest = DigestSigningRequest.builder()
                    .certificateId(credentialID)
                    .digestValue(request.getHashes()[0])
                    .digestAlgorithm(hashAlgo)
                    .signatureType(signatureType)
                    .build();

            // Verify eIDAS compliance
            eidasComplianceService.validateEIDASCompliance(validationRequest, certificate);

            // Determine signature algorithm
            String keyAlgoForSig;
            if ("AWSKMS".equals(certEntity.getStorageType())) {
                keyAlgoForSig = awskmsService.getKeyAlgorithmType(certEntity.getKmsKeyId());
            } else {
                keyAlgoForSig = privateKey.getAlgorithm();
            }

            // Use signAlgo from request if provided, otherwise derive from key type + hash algo
            String jcaSigAlgo;
            if (request.getSignAlgo() != null && !request.getSignAlgo().isBlank()) {
                jcaSigAlgo = OIDMapper.toJcaSigAlgo(request.getSignAlgo());
            } else {
                jcaSigAlgo = OIDMapper.deriveJcaSigAlgo(keyAlgoForSig, hashAlgo);
            }

            // Convert signature algorithm to OID for response
            String sigAlgoOid = OIDMapper.toOidSigAlgo(jcaSigAlgo);

            // Results for multiple hash values
            String[] signatures = new String[request.getHashes().length];

            // Sign each hash
            for (int i = 0; i < request.getHashes().length; i++) {
                String hashToSign = request.getHashes()[i];
                byte[] hashBytes = Base64.getDecoder().decode(hashToSign);

                byte[] signatureBytes;

                if ("AWSKMS".equals(certEntity.getStorageType())) {
                    signatureBytes = awskmsService.signDigest(
                            certEntity.getKmsKeyId(),
                            hashBytes,
                            hashAlgo,
                            keyAlgoForSig);
                } else {
                    Signature signature;
                    if ("PKCS11".equals(certEntity.getStorageType())) {
                        signature = Signature.getInstance(jcaSigAlgo, certEntity.getProviderName());
                    } else {
                        signature = Signature.getInstance(jcaSigAlgo, "BCFIPS");
                    }
                    signature.initSign(privateKey);
                    signature.update(hashBytes);
                    signatureBytes = signature.sign();
                }

                signatures[i] = Base64.getEncoder().encodeToString(signatureBytes);

                // Log the successful signing operation
                DigestSigningRequest logRequest = DigestSigningRequest.builder()
                        .certificateId(credentialID)
                        .digestValue(hashToSign)
                        .digestAlgorithm(hashAlgo)
                        .signatureType(signatureType)
                        .build();
                signingLogService.logSuccessfulSigning(logRequest, jcaSigAlgo);
            }

            // Build and return CSC response
            return CSCSignatureResponse.builder()
                    .signatureAlgorithm(sigAlgoOid)
                    .signatures(signatures)
                    .build();
        } catch (SigningException se) {
            throw se;
        } catch (Exception e) {
            log.error("Error in signHash", e);
            throw new SigningException("Failed to sign hash: " + e.getMessage(), e);
        }
    }

    private CSCCertificateInfo mapToCscCertificateInfo(SigningCertificate cert, X509Certificate x509Cert)
            throws Exception {
        String subjectDN = x509Cert.getSubjectX500Principal().getName();
        String issuerDN = x509Cert.getIssuerX500Principal().getName();
        String serialNumber = x509Cert.getSerialNumber().toString();

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmssZ")
                .withZone(ZoneOffset.UTC);
        String validFrom = formatter.format(x509Cert.getNotBefore().toInstant());
        String validTo = formatter.format(x509Cert.getNotAfter().toInstant());

        String certBase64 = Base64.getEncoder().encodeToString(x509Cert.getEncoded());

        String keyAlgoJca = x509Cert.getPublicKey().getAlgorithm();
        Integer keyLen = getKeySize(x509Cert.getPublicKey());
        String[] sigOids = OIDMapper.supportedSigOidsForKeyAlgo(keyAlgoJca);

        String[] keyUsage = null;
        boolean[] keyUsageBits = x509Cert.getKeyUsage();
        if (keyUsageBits != null) {
            List<String> usages = new ArrayList<>();
            if (keyUsageBits[0]) usages.add("digitalSignature");
            if (keyUsageBits[1]) usages.add("nonRepudiation");
            if (keyUsageBits[2]) usages.add("keyEncipherment");
            if (keyUsageBits[3]) usages.add("dataEncipherment");
            if (keyUsageBits[4]) usages.add("keyAgreement");
            if (keyUsageBits[5]) usages.add("keyCertSign");
            if (keyUsageBits[6]) usages.add("cRLSign");
            keyUsage = usages.toArray(new String[0]);
        }

        CSCCertificateInfo.CSCCertificateDetails certDetails = CSCCertificateInfo.CSCCertificateDetails.builder()
                .subjectDN(subjectDN)
                .issuerDN(issuerDN)
                .serialNumber(serialNumber)
                .keyUsage(keyUsage)
                .validFrom(validFrom)
                .validTo(validTo)
                .certificates(new String[]{certBase64})
                .build();

        CSCCertificateInfo.CSCKeyInfo keyInfo = CSCCertificateInfo.CSCKeyInfo.builder()
                .status(CSCConstants.KEY_STATUS_ENABLED)
                .algo(sigOids)
                .len(keyLen)
                .build();

        CSCCertificateInfo.CSCPINInfo pinInfo = CSCCertificateInfo.CSCPINInfo.builder()
                .presence("PKCS11".equals(cert.getStorageType()) ? "mandatory" : "notRequired")
                .format("numeric")
                .label("HSM PIN")
                .description("PIN for accessing the PKCS#11 token")
                .build();

        return CSCCertificateInfo.builder()
                .credentialID(cert.getId())
                .description(cert.getDescription())
                .status(cert.isActive()
                        ? CSCConstants.CREDENTIAL_STATUS_ENABLED
                        : CSCConstants.CREDENTIAL_STATUS_SUSPENDED)
                .cert(certDetails)
                .key(keyInfo)
                .pin(pinInfo)
                .authMode("explicit")
                .scal("1")
                .multisign(1)
                .build();
    }

    /**
     * Extracts PIN from CSC request
     */
    private String extractPinFromRequest(CSCSignatureRequest request) {
        if (request.getCredentials() != null &&
                request.getCredentials().getPin() != null &&
                request.getCredentials().getPin().getValue() != null) {
            return request.getCredentials().getPin().getValue();
        }
        return null;
    }

    /**
     * Extracts PIN from CSC request
     */
    private String extractPinFromRequest(CSCCredentialsListRequest request) {
        if (request.getCredentials() != null &&
                request.getCredentials().getPin() != null &&
                request.getCredentials().getPin().getValue() != null) {
            return request.getCredentials().getPin().getValue();
        }
        return null;
    }

        /**
     * Associates an existing PKCS#11 certificate with a client
     * @param request The association request
     * @return The associated certificate info
     */
    @Transactional
    public CSCCertificateInfo associateCertificate(CSCAssociateCertificateRequest request) {
        try {
            String clientId = currentClientId();
            String pin = extractPinFromRequest(request);
            
            if (pin == null || pin.isEmpty()) {
                throw new CertificateException("PIN is required to access PKCS#11 token");
            }
            
            // Verify client exists
            if (!oauth2ClientRepository.existsByClientId(clientId)) {
                throw new CertificateException("Invalid OAuth2 client ID. Client does not exist.");
            }
            
            // Verify certificate exists in the HSM
            X509Certificate certificate = requirePkcs11Service().getCertificate(request.getCertificateAlias(), pin);
            
            // Verify private key is available
            if (!requirePkcs11Service().validateCertificateAndKey(request.getCertificateAlias(), pin)) {
                throw new CertificateException("Private key not found for certificate with alias: " + request.getCertificateAlias());
            }
            
            // Create entity for the certificate association
            SigningCertificate certEntity = SigningCertificate.builder()
                .id(UUID.randomUUID().toString())
                .description(request.getDescription())
                .storageType("PKCS11")
                .certificateAlias(request.getCertificateAlias())
                .providerName(requirePkcs11Service().getProviderName())
                .slotId(request.getSlotId())
                .active(true)
                .clientId(clientId)
                .createdAt(Instant.now())
                .build();
                
            certificateRepository.save(certEntity);
            
            // Map to CSC certificate info format
            return mapToCscCertificateInfo(certEntity, certificate);
        } catch (Exception e) {
            log.error("Error associating certificate", e);
            throw new CertificateException("Failed to associate PKCS#11 certificate: " + e.getMessage(), e);
        }
    }

    /**
     * Extracts PIN from CSC association request
     */
    private String extractPinFromRequest(CSCAssociateCertificateRequest request) {
        if (request.getCredentials() != null && 
            request.getCredentials().getPin() != null && 
            request.getCredentials().getPin().getValue() != null) {
            return request.getCredentials().getPin().getValue();
        }
        return null;
    }

    /**
     * Estimates the key size based on the public key
     */
    private int getKeySize(java.security.PublicKey publicKey) {
        if (publicKey instanceof java.security.interfaces.RSAPublicKey) {
            return ((java.security.interfaces.RSAPublicKey) publicKey).getModulus().bitLength();
        } else if (publicKey instanceof java.security.interfaces.ECPublicKey) {
            // For EC keys, we estimate based on field size
            return ((java.security.interfaces.ECPublicKey) publicKey).getParams().getOrder().bitLength();
        }
        return 0; // Unknown key type
    }
}