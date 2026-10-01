#!/usr/bin/env bash
# Generates a valid RSA-2048 signed JWT for local testing and developer experiments
set -euo pipefail

CLIENT_ID="${1:-bank-core-producer}"
SCOPE="${2:-notifications:write notifications:read preferences:read preferences:write}"
EXP_SECONDS="${3:-3600}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
KEY_FILE="$PROJECT_ROOT/src/main/resources/certs/app.key"

if [ ! -f "$KEY_FILE" ]; then
    echo "Error: Private key file not found at $KEY_FILE" >&2
    exit 1
fi

NOW=$(date +%s)
EXP=$((NOW + EXP_SECONDS))

b64url() {
    openssl base64 -e -A | tr '+/' '-_' | tr -d '='
}

HEADER='{"alg":"RS256","typ":"JWT"}'
PAYLOAD=$(cat <<EOF
{
  "sub": "$CLIENT_ID",
  "client_id": "$CLIENT_ID",
  "azp": "$CLIENT_ID",
  "scope": "$SCOPE",
  "iss": "pigeon-dev-issuer",
  "iat": $NOW,
  "exp": $EXP
}
EOF
)

HEADER_B64=$(echo -n "$HEADER" | b64url)
PAYLOAD_B64=$(echo -n "$PAYLOAD" | b64url)
SIGN_INPUT="${HEADER_B64}.${PAYLOAD_B64}"

SIGNATURE_B64=$(echo -n "$SIGN_INPUT" | openssl dgst -sha256 -sign "$KEY_FILE" | b64url)

JWT="${SIGN_INPUT}.${SIGNATURE_B64}"
echo "$JWT"
