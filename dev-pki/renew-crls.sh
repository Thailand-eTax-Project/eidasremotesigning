#!/usr/bin/env bash
# Regenerates both CRLs (nextUpdate +30 days). nginx serves the new files
# immediately (bind mount). OCSP responders are NOT touched by this script:
# revocation visibility via OCSP changes only through revoke.sh, which
# restarts the responder.
set -euo pipefail
cd "$(dirname "$0")"
export PKI_OUT="$PWD/out"

[ -d out/ca/root ] || { echo "ERROR: PKI not generated yet — run ./generate.sh first" >&2; exit 1; }

openssl ca -config conf/root.cnf    -gencrl -out out/ca/root/root.crl.pem
openssl ca -config conf/issuing.cnf -gencrl -out out/ca/issuing/issuing.crl.pem
openssl crl -in out/ca/root/root.crl.pem       -outform DER -out out/www/crl/root.crl
openssl crl -in out/ca/issuing/issuing.crl.pem -outform DER -out out/www/crl/issuing.crl

echo "CRLs regenerated (nextUpdate +30 days)."
echo "NOTE: OCSP responders unaffected; revocations reach OCSP only via revoke.sh."
