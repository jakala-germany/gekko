#!/bin/bash
# Checks `--heal api` over HTTPS with the freshly built CLI and a fake endpoint (scripts/fake-llm-server.py): the TLS
# connection, the bearer key, a private CA (GEKKO_LLM_CA_CERT) and the error messages. No real model and no secret.
# Needs: ./gradlew :cli:linkReleaseExecutableLinuxX64 (or ...MacosArm64), curl, openssl and python3.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ "$(uname)" = "Darwin" ]; then
  GEKKO="${GEKKO:-cli/build/bin/macosArm64/releaseExecutable/gekko.kexe}"
else
  GEKKO="${GEKKO:-cli/build/bin/linuxX64/releaseExecutable/gekko.kexe}"
fi
GEKKO="$(cd "$(dirname "$GEKKO")" && pwd)/$(basename "$GEKKO")"

work="$(mktemp -d)"
server=""
cleanup() {
  [ -z "$server" ] || kill "$server" 2>/dev/null || true
  rm -rf "$work"
}
trap cleanup EXIT

# A self-signed certificate for localhost; the CLI must only trust it when GEKKO_LLM_CA_CERT names it
cat > "$work/openssl.cnf" <<'CONF'
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = localhost
[ext]
subjectAltName = DNS:localhost,IP:127.0.0.1
basicConstraints = CA:TRUE
CONF
openssl req -x509 -newkey rsa:2048 -nodes -days 1 -config "$work/openssl.cnf" \
  -keyout "$work/key.pem" -out "$work/cert.pem" 2>/dev/null

python3 scripts/fake-llm-server.py "$work/cert.pem" "$work/key.pem" test-key > "$work/server.out" &
server=$!
for _ in $(seq 1 50); do
  grep -q '^port=' "$work/server.out" 2>/dev/null && break
  sleep 0.1
done
port="$(sed -n 's/^port=//p' "$work/server.out")"
[ -n "$port" ] || { echo "the fake endpoint did not start" >&2; exit 1; }

export GEKKO_LLM_BASE_URL="https://localhost:$port/v1"
export GEKKO_LLM_MODEL=stub

echo "== the model answers over HTTPS with the right key and the private CA"
GEKKO_LLM_API_KEY=test-key GEKKO_LLM_CA_CERT="$work/cert.pem" "$GEKKO" llm-check --heal api | tee "$work/ok.out"
grep -q 'the model answered: pong' "$work/ok.out"

echo "== a wrong key is reported with the status of the endpoint (exit 3)"
status=0
GEKKO_LLM_API_KEY=wrong GEKKO_LLM_CA_CERT="$work/cert.pem" "$GEKKO" llm-check --heal api > "$work/key.out" 2>&1 || status=$?
cat "$work/key.out"
[ "$status" -eq 3 ] || { echo "a wrong key must exit with 3, got $status" >&2; exit 1; }
grep -q 'HTTP 401: Incorrect API key' "$work/key.out"

echo "== a certificate nobody trusts is refused (exit 3)"
status=0
GEKKO_LLM_API_KEY=test-key "$GEKKO" llm-check --heal api > "$work/tls.out" 2>&1 || status=$?
cat "$work/tls.out"
[ "$status" -eq 3 ] || { echo "an untrusted certificate must exit with 3, got $status" >&2; exit 1; }
grep -q 'GEKKO_LLM_CA_CERT' "$work/tls.out"

echo "== a missing setting is a usage error (exit 2)"
status=0
env -u GEKKO_LLM_BASE_URL "$GEKKO" llm-check --heal api > "$work/usage.out" 2>&1 || status=$?
[ "$status" -eq 2 ] || { echo "a missing GEKKO_LLM_BASE_URL must exit with 2, got $status" >&2; exit 1; }

echo "LLM end to end passed"
