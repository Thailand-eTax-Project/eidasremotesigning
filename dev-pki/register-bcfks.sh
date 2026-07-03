#!/usr/bin/env bash
# Converts signer.p12 -> BCFKS and emits the SQL to register it as a BCFKS
# credential. The keytool conversion preserves the P12 key entry's FULL chain
# (BCFKSService.createKeystore cannot — it stores a single certificate).
# The SQL is emitted, not executed: review it, then run it yourself.
set -euo pipefail
cd "$(dirname "$0")"

CLIENT_ID="${1:-}"
[ -n "$CLIENT_ID" ] || { echo "Usage: ./register-bcfks.sh <clientId> [keystore-dir]" >&2; exit 2; }
KEYSTORE_DIR="${2:-${KEYSTORE_PATH:-/app/keystores}}"
SIGNER_PASSWORD="${DEV_PKI_SIGNER_PASSWORD:-etax-dev-signer-pw}"
# BCFKSService rejects passwords < 14 chars at KEY LOAD time (sign time, not
# registration time) — fail here instead of emitting SQL that breaks later.
[ "${#SIGNER_PASSWORD}" -ge 14 ] \
  || { echo "ERROR: DEV_PKI_SIGNER_PASSWORD must be >= 14 chars (BCFKSService.MIN_PASSWORD_LENGTH)" >&2; exit 1; }

command -v keytool >/dev/null 2>&1 || { echo "ERROR: keytool not found (install a JDK)" >&2; exit 1; }
[ -f out/signer/signer.p12 ] || { echo "ERROR: PKI not generated — run ./generate.sh first" >&2; exit 1; }
BCFIPS_JAR=$(ls "$HOME"/.m2/repository/org/bouncycastle/bc-fips/*/bc-fips-*.jar 2>/dev/null | grep -v -- '-sources\.jar$' | sort -V | tail -1 || true)
[ -n "$BCFIPS_JAR" ] || { echo "ERROR: bc-fips jar not in ~/.m2 — run 'mvn dependency:resolve' first" >&2; exit 1; }

CERT_ID=$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)
KS_FILE="$KEYSTORE_DIR/etax-dev-signer-$CERT_ID.bcfks"
mkdir -p "$KEYSTORE_DIR"

keytool -importkeystore \
  -srckeystore out/signer/signer.p12 -srcstoretype PKCS12 -srcstorepass "$SIGNER_PASSWORD" \
  -destkeystore "$KS_FILE" -deststoretype BCFKS -deststorepass "$SIGNER_PASSWORD" \
  -providerclass org.bouncycastle.jcajce.provider.BouncyCastleFipsProvider \
  -providerpath "$BCFIPS_JAR"

CERT_B64=$(openssl x509 -in out/signer/signer.crt -outform DER | base64 -w0)
NOW=$(date -u +"%Y-%m-%d %H:%M:%S")

cat <<EOF

BCFKS keystore written: $KS_FILE

Review, then run against the eidasremotesigning database (service must have
booted once so ddl-auto:update has added certificate_data):

INSERT INTO signing_certificates
  (id, description, storage_type, certificate_alias, keystore_path,
   keystore_password, certificate_data, active, client_id, created_at)
VALUES
  ('$CERT_ID', 'eTax dev PKI signer (BCFKS)', 'BCFKS', 'etax-dev-signer',
   '$KS_FILE', '$SIGNER_PASSWORD', '$CERT_B64', TRUE, '$CLIENT_ID',
   '$NOW');

NOTES:
 - client_id has a FK to oauth2_clients(client_id): '$CLIENT_ID' must already be
   registered (POST /client-registration) or the INSERT fails.
 - Re-registering after './generate.sh --force'? Delete the previous row
   (DELETE FROM signing_certificates WHERE id = '<old-id>') and its old
   .bcfks file under $KEYSTORE_DIR — each run creates a new keystore + row.
EOF
