package com.wpanther.eidasremotesigning.controller;

import com.wpanther.eidasremotesigning.dto.csc.*;
import com.wpanther.eidasremotesigning.service.CSCApiService;
import com.wpanther.eidasremotesigning.service.CSCSignatureService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controller implementing the Cloud Signature Consortium API v2.0 signatures endpoints
 * Handles all signature-related operations as defined in CSC API 2.0
 */
@RestController
@RequestMapping("/csc/v2/signatures")
@RequiredArgsConstructor
@Slf4j
public class CSCSignatureController {

    private final CSCApiService cscApiService;
    private final CSCSignatureService cscSignatureService;

    /**
     * Sign hash(es) with the specified credential
     * Implementation moved from CSCApiController to this specialized controller
     */
    @PostMapping("/signHash")
    public ResponseEntity<CSCSignatureResponse> signHash(
            @Valid @RequestBody CSCSignatureRequest request) {
        log.debug("CSC API: Signature request for credential: {}",
                request.getCredentialID());
        
        CSCSignatureResponse response = cscApiService.signHash(request);
        return ResponseEntity.ok(response);
    }
    
    /**
     * Remote document signing operation
     * Supports signing a complete document instead of just a hash
     */
    @PostMapping("/signDoc")
    public ResponseEntity<CSCSignDocumentResponse> signDocument(
            @Valid @RequestBody CSCSignDocumentRequest request) {
        log.debug("CSC API: Document signature request for credential: {}",
                request.getCredentialID());
        
        CSCSignDocumentResponse response = cscSignatureService.signDocument(request);
        return ResponseEntity.ok(response);
    }
    
    /**
     * Get signature status
     * Returns the current status of an asynchronous signing operation
     */
    @PostMapping("/signPolling")
    public ResponseEntity<CSCSignatureStatusResponse> getSignatureStatus(
            @Valid @RequestBody CSCSignatureStatusRequest request) {
        log.debug("CSC API: Signature status request for requestID: {}",
                request.getRequestID());
        
        CSCSignatureStatusResponse response = cscSignatureService.getSignatureStatus(request);
        return ResponseEntity.ok(response);
    }
    
    /**
     * Timestamp document or hash
     * Creates a timestamp that can be used to prove existence of a document at a point in time
     */
    @PostMapping("/timestamp")
    public ResponseEntity<CSCTimestampResponse> createTimestamp(
            @Valid @RequestBody CSCTimestampRequest request) {
        log.debug("CSC API: Timestamp request");

        CSCTimestampResponse response = cscSignatureService.createTimestamp(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Validate a signature
     */
    @PostMapping("/validate")
    public ResponseEntity<CSCVerifyResponse> validateSignature(
            @Valid @RequestBody CSCVerifyRequest request) {
        log.debug("CSC API: Signature validation request");

        CSCVerifyResponse response = cscSignatureService.validateSignature(request);
        return ResponseEntity.ok(response);
    }
}