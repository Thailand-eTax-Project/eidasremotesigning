#!/usr/bin/env bash
# One-shot dev PKI generation: root CA -> issuing CA -> signer certs -> CRLs.
# Usage: ./generate.sh [--force] [--include-freetsa]
set -euo pipefail
cd "$(dirname "$0")"
export PKI_OUT="$PWD/out"

TRUSTSTORE_PASSWORD="${DEV_PKI_TRUSTSTORE_PASSWORD:-changeit}"
SIGNER_PASSWORD="${DEV_PKI_SIGNER_PASSWORD:-etax-dev-signer-pw}"
# signer.p12 later becomes a BCFKS keystore; BCFKSService requires >= 14 chars.
[ "${#SIGNER_PASSWORD}" -ge 14 ] \
  || { echo "ERROR: DEV_PKI_SIGNER_PASSWORD must be >= 14 chars (BCFKSService.MIN_PASSWORD_LENGTH)" >&2; exit 1; }
FORCE=0
INCLUDE_FREETSA=0
for arg in "$@"; do
  case "$arg" in
    --force)           FORCE=1 ;;
    --include-freetsa) INCLUDE_FREETSA=1 ;;
    *) echo "Usage: ./generate.sh [--force] [--include-freetsa]" >&2; exit 2 ;;
  esac
done

for tool in openssl keytool curl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "ERROR: '$tool' not found on PATH" >&2; exit 1; }
done
# Exclude -sources.jar siblings: Maven installs both, and sort -V | tail -1 would
# otherwise pick the sources-only jar (no .class files for the BC provider).
BC_JAR=$(ls "$HOME"/.m2/repository/org/bouncycastle/bcprov-jdk18on/*/bcprov-jdk18on-*.jar 2>/dev/null | grep -v -- '-sources\.jar$' | sort -V | tail -1 || true)
[ -n "$BC_JAR" ] || { echo "ERROR: bcprov-jdk18on jar not in ~/.m2 — run 'mvn dependency:resolve' in eidasremotesigning" >&2; exit 1; }

if [ -d out ]; then
  if [ "$FORCE" -eq 1 ]; then
    rm -rf out
  else
    echo "ERROR: out/ already exists. Regenerating invalidates certificates already" >&2
    echo "registered in the service DB, SoftHSM tokens, and copied trust stores." >&2
    echo "Use --force to wipe and regenerate, then re-run the registration steps." >&2
    exit 1
  fi
fi

mkdir -p out/ca/root/private out/ca/root/newcerts \
         out/ca/issuing/private out/ca/issuing/newcerts \
         out/www/crl out/www/certs out/signer out/truststore
chmod 700 out/ca/root/private out/ca/issuing/private
touch out/ca/root/index.txt out/ca/issuing/index.txt
echo 1000 > out/ca/root/serial;    echo 1000 > out/ca/root/crlnumber
echo 1000 > out/ca/issuing/serial; echo 1000 > out/ca/issuing/crlnumber

echo "== Root CA (RSA 4096, 10y)"
openssl genrsa -out out/ca/root/private/root.key 4096
openssl req -new -x509 -config conf/root.cnf -extensions v3_root_ca \
  -key out/ca/root/private/root.key -sha256 -days 3650 \
  -subj "/C=TH/O=eTax Dev/CN=eTax Dev Root CA" -out out/ca/root/root.crt

echo "== Issuing CA (RSA 3072, 5y, pathlen:0)"
openssl genrsa -out out/ca/issuing/private/issuing.key 3072
openssl req -new -config conf/issuing.cnf \
  -key out/ca/issuing/private/issuing.key \
  -subj "/C=TH/O=eTax Dev/CN=eTax Dev Issuing CA" -out out/ca/issuing/issuing.csr
openssl ca -batch -notext -config conf/root.cnf -extensions v3_issuing_ca -days 1825 \
  -in out/ca/issuing/issuing.csr -out out/ca/issuing/issuing.crt

issue_signer() { # $1 = file basename, $2 = CN
  openssl genrsa -out "out/signer/$1.key" 2048
  openssl req -new -config conf/issuing.cnf -key "out/signer/$1.key" \
    -subj "/C=TH/O=eTax Dev/CN=$2" -out "out/signer/$1.csr"
  openssl ca -batch -notext -config conf/issuing.cnf -extensions v3_signer -days 730 \
    -in "out/signer/$1.csr" -out "out/signer/$1.crt"
}
echo "== End-entity signers (RSA 2048, 2y)"
issue_signer signer "eTax Dev Signer"
issue_signer signer-revoked "eTax Dev Signer Revoked"

echo "== Revoking the negative-test fixture"
openssl ca -config conf/issuing.cnf -revoke out/signer/signer-revoked.crt

./renew-crls.sh

echo "== Publishing CA certs for AIA (DER)"
openssl x509 -in out/ca/root/root.crt       -outform DER -out out/www/certs/root.crt
openssl x509 -in out/ca/issuing/issuing.crt -outform DER -out out/www/certs/issuing.crt

cat out/signer/signer.crt out/ca/issuing/issuing.crt out/ca/root/root.crt > out/signer/chain.pem
cat out/ca/issuing/issuing.crt out/ca/root/root.crt > out/signer/ca-chain.pem

echo "== Signer PKCS12 (key + full chain)"
openssl pkcs12 -export -name etax-dev-signer \
  -inkey out/signer/signer.key -in out/signer/signer.crt \
  -certfile out/signer/ca-chain.pem \
  -passout "pass:$SIGNER_PASSWORD" -out out/signer/signer.p12

echo "== DSS trust stores (PKCS12 + BCFKS)"
# BCFKS is the canonical target for app.dss.trust-store.path: it provides a
# single-file keystore with strong KDF-based password stretching suitable for
# the plain BC JCE provider used by BCFKSService. PKCS12 is also produced for
# inspection by tools / workflows that don't have the BC provider classpath.
import_trusted() { # $1 = alias, $2 = cert file (PEM or DER)
  keytool -importcert -noprompt -alias "$1" -file "$2" \
    -keystore out/truststore/dss-truststore.p12 -storetype PKCS12 \
    -storepass "$TRUSTSTORE_PASSWORD"
  keytool -importcert -noprompt -alias "$1" -file "$2" \
    -keystore out/truststore/dss-truststore.bcfks -storetype BCFKS \
    -providerclass org.bouncycastle.jce.provider.BouncyCastleProvider \
    -providerpath "$BC_JAR" \
    -storepass "$TRUSTSTORE_PASSWORD"
}
import_trusted etax-dev-root    out/ca/root/root.crt
import_trusted etax-dev-issuing out/ca/issuing/issuing.crt

if [ "$INCLUDE_FREETSA" -eq 1 ]; then
  echo "== freetsa.org CA (needed to VALIDATE LTA; signing works without it)"
  curl -fsS https://freetsa.org/files/cacert.pem -o out/truststore/freetsa-ca.pem
  openssl x509 -noout -subject -in out/truststore/freetsa-ca.pem
  import_trusted freetsa-ca out/truststore/freetsa-ca.pem
fi

cat <<SUMMARY

============================================================
 dev PKI ready — $PKI_OUT
============================================================
 Signer PKCS12 : out/signer/signer.p12   (alias etax-dev-signer)
                 password: $SIGNER_PASSWORD
 Trust stores  : out/truststore/dss-truststore.bcfks  <- point app.dss.trust-store.path here
                 out/truststore/dss-truststore.p12    (inspection/non-FIPS tools)
                 password: $TRUSTSTORE_PASSWORD
 CA chain      : out/signer/chain.pem / ca-chain.pem
 Revoked cert  : out/signer/signer-revoked.crt (negative-test fixture)

 Next steps:
   1. docker compose up -d          # serve CRL/AIA/OCSP (ports 8880-8882)
   2. ./verify.sh                   # PKI acceptance test
   3. ./provision-softhsm.sh        # PKCS#11 backend, or:
      ./register-bcfks.sh <clientId>  # BCFKS backend
 If regenerated with --force: re-run step 3 registrations.
============================================================
SUMMARY
