package com.wpanther.eidasremotesigning.testsupport;

import eu.europa.esig.dss.enumerations.DigestAlgorithm;
import eu.europa.esig.dss.model.TimestampBinary;
import eu.europa.esig.dss.spi.x509.tsp.TSPSource;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoGeneratorBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.DigestCalculator;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.tsp.TSPAlgorithms;
import org.bouncycastle.tsp.TimeStampRequestGenerator;
import org.bouncycastle.tsp.TimeStampResponseGenerator;
import org.bouncycastle.tsp.TimeStampTokenGenerator;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Date;

import org.bouncycastle.asn1.x500.X500Name;

/**
 * Deterministic, in-process {@link TSPSource} for tests. Mints valid RFC 3161
 * timestamps with a generated TSA key + self-signed TSA certificate. The
 * DSA 5.13.x distribution ships no {@code MockTSPSource}, so this is the
 * project's replacement.
 *
 * <p>The TSA certificate carries the {@code id-kp-timeStamping} extended key
 * usage extension — required by BC's {@link TimeStampTokenGenerator} — and
 * the {@link #getTsaCertificate()} accessor exposes it so the companion test
 * verifier (see {@link DssTestConfig#testCertificateVerifier}) can trust it.
 */
public class TestTSPSource implements TSPSource {

    private static final String TSA_POLICY_OID = "1.2.3.4.1";

    private final PrivateKey tsaKey;
    private final X509Certificate tsaCert;
    private final TimeStampResponseGenerator responseGenerator;

    public TestTSPSource() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA", "BC");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        this.tsaKey = kp.getPrivate();
        this.tsaCert = buildTsaCertificate(kp);

        DigestCalculator digestCalculator = new JcaDigestCalculatorProviderBuilder()
                .setProvider("BC")
                .build()
                .get(new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256));

        var signerInfoGen = new JcaSimpleSignerInfoGeneratorBuilder()
                .setProvider("BC")
                .build("SHA256withRSA", tsaKey, tsaCert);

        TimeStampTokenGenerator tokenGen = new TimeStampTokenGenerator(
                signerInfoGen, digestCalculator, new ASN1ObjectIdentifier(TSA_POLICY_OID));
        this.responseGenerator = new TimeStampResponseGenerator(tokenGen, TSPAlgorithms.ALLOWED);
    }

    /**
     * Builds a self-signed TSA certificate with the
     * {@code id-kp-timeStamping} extended key usage extension. BC's
     * {@link TimeStampTokenGenerator} rejects certificates without it.
     */
    private static X509Certificate buildTsaCertificate(KeyPair kp) throws Exception {
        X500Name subject = new X500Name("CN=Test TSA,O=eidasremotesigning-tests");
        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000);

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.ONE, notBefore, notAfter, subject, kp.getPublic());
        builder.addExtension(Extension.keyUsage, false, new KeyUsage(KeyUsage.digitalSignature));
        builder.addExtension(Extension.extendedKeyUsage, true,
                new ExtendedKeyUsage(new KeyPurposeId[] { KeyPurposeId.id_kp_timeStamping }));

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC")
                .build(kp.getPrivate());
        return new JcaX509CertificateConverter()
                .setProvider("BC")
                .getCertificate(builder.build(signer));
    }

    @Override
    public TimestampBinary getTimeStampResponse(DigestAlgorithm digestAlgorithm, byte[] digest) {
        try {
            ASN1ObjectIdentifier bcAlgo = toBcAlgo(digestAlgorithm);
            // BC 1.84: use the generator's 4-arg generate(oid, digest, nonce) so the
            // request/response nonces match (response.validate(request) checks).
            TimeStampRequestGenerator reqGen = new TimeStampRequestGenerator();
            reqGen.setReqPolicy(new ASN1ObjectIdentifier(TSA_POLICY_OID));
            var request = reqGen.generate(bcAlgo, digest, BigInteger.valueOf(System.nanoTime()));
            var response = responseGenerator.generate(request, BigInteger.ONE, new Date());
            response.validate(request);

            // Mirror DSS's OnlineTSPSource (eu.europa.esig.dss.service.tsp.OnlineTSPSource
            // #getTimeStampResponse): return the CMSSignedData DER-encoded — NOT the outer
            // TimeStampResp SEQUENCE. The outer SEQUENCE carries BER (DLSequence) artifacts
            // that DSS then trips on with "illegal object in getInstance:
            // org.bouncycastle.asn1.DLSequence" when it parses the bytes back to extract the
            // unsigned attribute during baseline-T extension.
            org.bouncycastle.tsp.TimeStampToken timeStampToken = response.getTimeStampToken();
            return new TimestampBinary(timeStampToken.toCMSSignedData().getEncoded());
        } catch (Exception e) {
            throw new IllegalStateException("TestTSPSource failed to mint timestamp", e);
        }
    }

    public X509Certificate getTsaCertificate() {
        return tsaCert;
    }

    private static ASN1ObjectIdentifier toBcAlgo(DigestAlgorithm da) {
        switch (da) {
            case SHA256:
                return NISTObjectIdentifiers.id_sha256;
            case SHA384:
                return NISTObjectIdentifiers.id_sha384;
            case SHA512:
                return NISTObjectIdentifiers.id_sha512;
            default:
                throw new IllegalArgumentException("Unsupported digest for TestTSPSource: " + da);
        }
    }
}
