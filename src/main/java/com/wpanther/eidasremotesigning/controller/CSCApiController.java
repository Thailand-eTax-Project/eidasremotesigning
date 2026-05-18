package com.wpanther.eidasremotesigning.controller;

import com.wpanther.eidasremotesigning.dto.csc.*;
import com.wpanther.eidasremotesigning.service.CSCApiService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.wpanther.eidasremotesigning.util.CSCConstants;

import java.net.URI;
import java.util.List;

/**
 * Controller implementing the Cloud Signature Consortium API v2.0
 * See https://cloudsignatureconsortium.org/resources/csc-api-v2-0/
 */
@RestController
@RequestMapping("/csc/v2")
@RequiredArgsConstructor
@Slf4j
public class CSCApiController {

    private final CSCApiService cscApiService;

    /**
     * Get information about this CSC service
     */
    @GetMapping("/info")
    public ResponseEntity<CSCInfoResponse> getInfo() {
        log.debug("CSC API: Request for service information");

        CSCInfoResponse response = CSCInfoResponse.builder()
                .specs(CSCConstants.SPECS_VERSION)
                .name("eIDAS Remote Signing Service")
                .region("EU")
                .lang(List.of("en"))
                .description("eIDAS compliant remote signing service supporting PKCS#11 hardware tokens, AWS KMS, and BCFKS keystores with asynchronous operation support")
                .authType(List.of(CSCConstants.AUTH_TYPE_OAUTH2_CODE))
                .methods(List.of(
                        "credentials/list",
                        "credentials/info",
                        "credentials/authorize",
                        "credentials/authorizeStatus",
                        "credentials/extendTransaction",
                        "signatures/signHash",
                        "signatures/signDocument",
                        "signatures/timestamp",
                        "signatures/status",
                        "signatures/validate"
                ))
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * List available credentials (certificates)
     */
    @PostMapping("/credentials/list")
    public ResponseEntity<CSCCredentialsListResponse> listCredentials(
            @Valid @RequestBody CSCCredentialsListRequest request) {
        log.debug("CSC API: Request for credentials list from client: {}", request.getClientId());
        
        CSCCredentialsListResponse response = cscApiService.listCredentials(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/credentials/info")
    public ResponseEntity<CSCCertificateInfo> getCredentialInfo(
            @Valid @RequestBody CSCCredentialsInfoRequest request) {
        log.debug("CSC API: Request for credential info, ID: {}, client: {}",
                request.getCredentialID(), request.getClientId());

        CSCCertificateInfo response = cscApiService.getCredentialInfo(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/credentials/associate")
public ResponseEntity<CSCCertificateInfo> associateCertificate(
        @Valid @RequestBody CSCAssociateCertificateRequest request) {
    log.debug("CSC API: Request to associate certificate with alias: {}, client: {}", 
            request.getCertificateAlias(), request.getClientId());
    
    CSCCertificateInfo response = cscApiService.associateCertificate(request);
    return ResponseEntity.created(URI.create("/csc/v2/credentials/info?credentialID=" + response.getCredentialID()))
            .body(response);
}

}
