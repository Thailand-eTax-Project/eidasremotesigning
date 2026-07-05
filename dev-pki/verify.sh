#!/usr/bin/env bash
# PKI acceptance test: chain, CRL/AIA URLs, OCSP good+revoked, trust stores.
# Requires: ./generate.sh done and 'docker compose up -d' running.
set -uo pipefail   # no -e: collect all failures, report at end
cd "$(dirname "$0")"

TRUSTSTORE_PASSWORD="${DEV_PKI_TRUSTSTORE_PASSWORD:-changeit}"
SIGNER_PASSWORD="${DEV_PKI_SIGNER_PASSWORD:-etax-dev-signer-pw}"
BC_JAR=$(ls "$HOME"/.m2/repository/org/bouncycastle/bcprov-jdk18on/*/bcprov-jdk18on-*.jar 2>/dev/null | grep -v -- '-sources\.jar$' | sort -V | tail -1)
[ -n "$BC_JAR" ] \
  || echo "WARN: bcprov-jdk18on jar not in ~/.m2 (run 'mvn dependency:resolve') — the BCFKS check will FAIL"
FAIL=0

check() { # $1 = description, rest = command
  local desc="$1"; shift
  if "$@" >/dev/null 2>&1; then echo "PASS: $desc"; else echo "FAIL: $desc"; FAIL=1; fi
}

# For checks against the containers: docker-proxy accepts on the published
# port before the process inside listens (nginx starting, 'apk add openssl'
# still installing), so a bare TCP probe passes too early. Retry the real
# query instead.
check_retry() { # $1 = description, rest = command
  local desc="$1" i; shift
  for i in $(seq 1 15); do
    if "$@" >/dev/null 2>&1; then echo "PASS: $desc"; return 0; fi
    sleep 1
  done
  echo "FAIL: $desc"; FAIL=1
}

# The OCSP responders are single-threaded dev-grade processes: wait for the
# ports instead of relying on a fixed sleep after 'docker compose up -d'.
wait_port() { # $1 = port
  local i
  for i in $(seq 1 15); do
    (exec 3<>"/dev/tcp/localhost/$1") 2>/dev/null && { exec 3>&- 3<&- || true; return 0; }
    sleep 1
  done
  echo "WARN: port $1 not answering after 15s — is 'docker compose up -d' running?"
  return 1
}
wait_port 8880; wait_port 8881; wait_port 8882

check "chain verifies (root -> issuing -> signer)" \
  openssl verify -CAfile out/ca/root/root.crt \
    -untrusted out/ca/issuing/issuing.crt out/signer/signer.crt

for path in crl/root.crl crl/issuing.crl certs/root.crt certs/issuing.crt; do
  check_retry "http://localhost:8880/$path reachable" \
    curl -fsS -o /dev/null "http://localhost:8880/$path"
done

check_retry "served issuing CRL parses as DER" \
  bash -c 'curl -fsS http://localhost:8880/crl/issuing.crl | openssl crl -inform DER -noout'

check_retry "OCSP(8882): signer is good" \
  bash -c 'openssl ocsp -issuer out/ca/issuing/issuing.crt -cert out/signer/signer.crt \
    -url http://localhost:8882 -CAfile out/signer/ca-chain.pem -no_nonce 2>/dev/null \
    | grep -q "signer.crt: good"'

check_retry "OCSP(8882): signer-revoked is revoked" \
  bash -c 'openssl ocsp -issuer out/ca/issuing/issuing.crt -cert out/signer/signer-revoked.crt \
    -url http://localhost:8882 -CAfile out/signer/ca-chain.pem -no_nonce 2>/dev/null \
    | grep -q "signer-revoked.crt: revoked"'

check_retry "OCSP(8881): issuing CA is good" \
  bash -c 'openssl ocsp -issuer out/ca/root/root.crt -cert out/ca/issuing/issuing.crt \
    -url http://localhost:8881 -CAfile out/ca/root/root.crt -no_nonce 2>/dev/null \
    | grep -q "issuing.crt: good"'

check "signer.p12 opens and contains the 3-cert chain" \
  bash -c "openssl pkcs12 -in out/signer/signer.p12 -passin 'pass:$SIGNER_PASSWORD' \
    -nokeys -nomacver 2>/dev/null | grep -c 'BEGIN CERTIFICATE' | grep -qx 3"

check "PKCS12 trust store lists >=2 trusted entries" \
  bash -c "keytool -list -keystore out/truststore/dss-truststore.p12 -storetype PKCS12 \
    -storepass '$TRUSTSTORE_PASSWORD' | grep -c trustedCertEntry | grep -qE '^[23]$'"

check "BCFKS trust store lists >=2 trusted entries (BC provider)" \
  bash -c "keytool -list -keystore out/truststore/dss-truststore.bcfks -storetype BCFKS \
    -providerclass org.bouncycastle.jce.provider.BouncyCastleProvider \
    -providerpath '$BC_JAR' \
    -storepass '$TRUSTSTORE_PASSWORD' | grep -c trustedCertEntry | grep -qE '^[23]$'"

echo
if [ "$FAIL" -eq 0 ]; then echo "ALL CHECKS PASSED"; else echo "SOME CHECKS FAILED"; fi
exit "$FAIL"
