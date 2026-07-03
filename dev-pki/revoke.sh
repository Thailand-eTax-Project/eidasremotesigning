#!/usr/bin/env bash
# Revokes an end-entity cert, regenerates CRLs, and restarts the issuing OCSP
# responder (it reads index.txt only at startup). Note -nmin 5: OCSP clients
# may still see cached 'good' responses for up to 5 minutes.
set -euo pipefail
cd "$(dirname "$0")"
export PKI_OUT="$PWD/out"

NAME="${1:-}"
[ -n "$NAME" ] || { echo "Usage: ./revoke.sh <cert-name>   (e.g. signer)" >&2; exit 2; }
CRT="out/signer/$NAME.crt"
[ -f "$CRT" ] || { echo "ERROR: $CRT not found" >&2; exit 1; }

openssl ca -config conf/issuing.cnf -revoke "$CRT"
./renew-crls.sh

if command -v docker >/dev/null 2>&1 && [ -n "$(docker compose ps -q ocsp-issuing 2>/dev/null)" ]; then
  docker compose restart ocsp-issuing
  echo "ocsp-issuing restarted — revocation now visible via OCSP."
else
  echo "NOTE: ocsp-issuing not running; restart it before expecting OCSP to reflect this revocation."
fi
