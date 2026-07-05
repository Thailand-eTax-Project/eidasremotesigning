# Dev Test PKI

Local 3-tier PKI (root → issuing → signer) with docker-served CRL + OCSP, so
eidasremotesigning can produce genuine XAdES/PAdES **LT/LTA** signatures in dev.
Design: `docs/superpowers/specs/2026-07-03-dev-test-pki-design.md`.

## Quick start

```bash
./generate.sh --include-freetsa   # create everything under out/ (gitignored)
docker compose up -d              # nginx :8880, OCSP :8881/:8882
./verify.sh                       # acceptance test — must print ALL CHECKS PASSED
./provision-softhsm.sh            # PKCS#11 backend  (needs softhsm2 + opensc)
./register-bcfks.sh <clientId>    # or BCFKS backend (emits SQL to review+run)
```

Key outputs (see generate.sh summary for passwords):

| File | Purpose |
|------|---------|
| `out/truststore/dss-truststore.bcfks` | point `app.dss.trust-store.path` here (FIPS-safe) |
| `out/truststore/dss-truststore.p12` | same content, for inspection/non-FIPS tools |
| `out/signer/signer.p12` | signer key + full chain, alias `etax-dev-signer` |
| `out/signer/signer-revoked.crt` | revoked fixture for negative tests |

## Symptom → cure

| Symptom | Cure |
|---------|------|
| LT worked last month, now fails with expired/stale CRL | `./renew-crls.sh` (CRL nextUpdate is 30 days) |
| OCSP still says `good` right after a revocation | expected: only `./revoke.sh` restarts the responder, and `-nmin 5` allows ~5 min staleness. `renew-crls.sh` never touches OCSP. |
| Signing at LTA succeeds but validation stops at LT | freetsa CA not trusted — regenerate with `--include-freetsa` or import `https://freetsa.org/files/cacert.pem` into the trust stores |
| Service can't load the PKCS12 trust store | use the `.bcfks` trust store — BCFKS is the canonical target for `app.dss.trust-store.path` and EU DSS loads BCFKS natively via the plain BC provider; PKCS12 is included for inspection only |
| `generate.sh` refuses to run | `out/` exists; use `--force` **and re-register** certs (DB rows, SoftHSM, copied trust stores) |

## Warnings

- **`--force` after root expiry (10y) is a full re-trust event**: every artifact
  that ever pinned the old root — service DB rows, SoftHSM tokens, trust stores
  copied to other services/machines — must be replaced, not just re-registered.
- URLs `localhost:8880-8882` are baked into the certificates. Changing ports, or
  running eidasremotesigning itself inside docker, requires regenerating with
  different URLs.
- OCSP responses are signed with the CA keys (dev-only shortcut); the CA keys are
  bind-mounted read-only into the responder containers. Never reuse this pattern
  outside a throwaway dev PKI.
- The OCSP containers `apk add openssl` on every start, so `docker compose up`
  needs network access (the stack is otherwise fully local).
- Using podman? Same as the repo's Testcontainers convention:
  `export DOCKER_HOST="unix:///run/user/$(id -u)/podman/podman.sock"`.
