package com.wpanther.eidasremotesigning.controller;

import com.wpanther.eidasremotesigning.dto.csc.*;
import com.wpanther.eidasremotesigning.service.CSCApiService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.wpanther.eidasremotesigning.util.CSCConstants;
import com.wpanther.eidasremotesigning.util.OIDMapper;
import org.springframework.beans.factory.annotation.Value;

import java.net.URI;
import java.util.Arrays;
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

    @Value("${app.csc.base-url}")
    private String cscBaseUrl;

    @Value("${app.csc.logo-url:}")
    private String cscLogoUrl;

    /**
     * Get information about this CSC service
     */
    @PostMapping("/info")
    public ResponseEntity<CSCInfoResponse> getInfo() {
        log.debug("CSC API: Request for service information");

        List<String> sigAlgoOids = Arrays.asList(OIDMapper.supportedSigOidsForKeyAlgo("RSA"));

        CSCInfoResponse response = CSCInfoResponse.builder()
                .specs(CSCConstants.SPECS_VERSION)
                .name("eIDAS Remote Signing Service")
                .logo(cscLogoUrl.isEmpty() ? null : cscLogoUrl)
                .region("EU")
                .lang("en")
                .description("eIDAS compliant remote signing service supporting PKCS#11 hardware tokens, AWS KMS, and BCFKS keystores")
                .authType(List.of(CSCConstants.AUTH_TYPE_OAUTH2_CODE))
                .methods(List.of(
                        "credentials/list",
                        "credentials/info",
                        "credentials/authorize",
                        "credentials/authorizeCheck",
                        "credentials/extendTransaction",
                        "signatures/signHash",
                        "signatures/signDoc",
                        "signatures/timestamp",
                        "signatures/signPolling",
                        "signatures/validate"
                ))
                .signAlgorithms(CSCInfoResponse.SignAlgorithms.builder()
                        .algos(sigAlgoOids)
                        .build())
                .signature_formats(CSCInfoResponse.SignatureFormats.builder()
                        .formats(List.of("P", "X"))
                        // Per CSC spec §11.1: one inner array per format entry.
                        // "P" (PAdES) supports "Certification" and "Revision" envelope properties.
                        // "X" (XAdES) supports "Enveloped", "Enveloping", "Detached".
                        .envelope_properties(List.of(
                                List.of("Certification", "Revision"),
                                List.of("Enveloped", "Enveloping", "Detached")
                        ))
                        .build())
                .conformance_levels(List.of("Ades-B-B"))
                .oauth2(cscBaseUrl)
                .asynchronousOperationMode(true)
                .build();

        return ResponseEntity.ok(response);
    }

    /**
     * List available credentials (certificates)
     */
    @PostMapping("/credentials/list")
    public ResponseEntity<CSCCredentialsListResponse> listCredentials(
            @Valid @RequestBody CSCCredentialsListRequest request) {
        log.debug("CSC API: Request for credentials list");
        
        CSCCredentialsListResponse response = cscApiService.listCredentials(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/credentials/info")
    public ResponseEntity<CSCCertificateInfo> getCredentialInfo(
            @Valid @RequestBody CSCCredentialsInfoRequest request) {
        log.debug("CSC API: Request for credential info, ID: {}",
                request.getCredentialID());

        CSCCertificateInfo response = cscApiService.getCredentialInfo(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/credentials/associate")
public ResponseEntity<CSCCertificateInfo> associateCertificate(
        @Valid @RequestBody CSCAssociateCertificateRequest request) {
    log.debug("CSC API: Request to associate certificate with alias: {}",
            request.getCertificateAlias());
    
    CSCCertificateInfo response = cscApiService.associateCertificate(request);
    return ResponseEntity.created(URI.create("/csc/v2/credentials/info?credentialID=" + response.getCredentialID()))
            .body(response);
}

}
