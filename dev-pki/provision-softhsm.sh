#!/usr/bin/env bash
# Loads the dev signer key+cert into a SoftHSM token for the PKCS#11 backend.
set -euo pipefail
cd "$(dirname "$0")"

TOKEN_LABEL="${SOFTHSM_TOKEN_LABEL:-etax-dev}"
PIN="${SOFTHSM_PIN:-123456}"
SO_PIN="${SOFTHSM_SO_PIN:-12345678}"
MODULE="${PKCS11_MODULE:-/usr/lib/softhsm/libsofthsm2.so}"

for tool in softhsm2-util pkcs11-tool openssl; do
  command -v "$tool" >/dev/null 2>&1 \
    || { echo "ERROR: '$tool' not found. Install: sudo apt install softhsm2 opensc" >&2; exit 1; }
done
[ -f "$MODULE" ] || { echo "ERROR: PKCS#11 module not found at $MODULE (set PKCS11_MODULE)" >&2; exit 1; }
[ -f out/signer/signer.key ] || { echo "ERROR: PKI not generated — run ./generate.sh first" >&2; exit 1; }

softhsm2-util --init-token --free --label "$TOKEN_LABEL" --pin "$PIN" --so-pin "$SO_PIN"

openssl pkcs8 -topk8 -nocrypt -in out/signer/signer.key -outform DER -out out/signer/signer.pk8.der
openssl x509 -in out/signer/signer.crt -outform DER -out out/signer/signer.der

pkcs11-tool --module "$MODULE" --token-label "$TOKEN_LABEL" --login --pin "$PIN" \
  --write-object out/signer/signer.pk8.der --type privkey --label etax-dev-signer --id 01
pkcs11-tool --module "$MODULE" --token-label "$TOKEN_LABEL" --login --pin "$PIN" \
  --write-object out/signer/signer.der --type cert --label etax-dev-signer --id 01

rm -f out/signer/signer.pk8.der

cat <<EOF

Token '$TOKEN_LABEL' provisioned (key+cert label: etax-dev-signer).
Register the credential with the running service (PKCS11_ENABLED=true):

  curl -X POST http://localhost:9000/csc/v2/credentials/associate \\
    -H "Authorization: Bearer \$ACCESS_TOKEN" \\
    -H "Content-Type: application/json" \\
    -d '{
      "clientId": "<your-client-id>",
      "certificateAlias": "etax-dev-signer",
      "description": "eTax dev PKI signer (SoftHSM)",
      "credentials": { "pin": { "value": "$PIN" } }
    }'

NOTE: the service identifies you by the Bearer token, NOT the clientId body
field — but the field must still be present: request validation (@NotBlank)
rejects the call with 400 if it is omitted. Any non-blank value passes.
EOF
