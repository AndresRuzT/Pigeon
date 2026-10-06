#!/usr/bin/env bash
# ==============================================================================
# Pigeon - Interactive Demo Script
# Demonstrates resilience, idempotency, priority bypass, fallback, and webhooks.
# ==============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

BASE_URL="${PIGEON_BASE_URL:-http://localhost:8080}"
WEBHOOK_SECRET="${PIGEON_WEBHOOK_SECRET:-pigeon_dev_webhook_secret_key_32bytes}"

echo "========================================================================"
echo "                   PIGEON BANKING NOTIFICATION ENGINE                   "
echo "                             Interactive Demo                           "
echo "========================================================================"
echo "Target Base URL: $BASE_URL"
echo ""

# 1. Generate JWT using dev-token.sh
echo "[1/6] Generating developer JWT via scripts/dev-token.sh..."
JWT=$("$SCRIPT_DIR/dev-token.sh" "bank-core-producer" "notifications:write notifications:read")
echo "JWT generated successfully."
echo ""

# Helper function to generate HMAC-SHA256 signature
generate_hmac() {
    local payload="$1"
    echo -n "$payload" | openssl dgst -sha256 -hmac "$WEBHOOK_SECRET" | sed 's/^.* //'
}

# Scenario 1: Standard Transfer Notification (Email Delivery)
echo "------------------------------------------------------------------------"
echo "Scenario 1: Ingesting TRANSFER_COMPLETED (Standard Flow)"
echo "------------------------------------------------------------------------"
IDEMPOTENCY_KEY_1="demo-transfer-$(date +%s%N)"
TRANSFER_PAYLOAD=$(cat <<EOF
{
  "customerId": "cust-1001",
  "eventType": "TRANSFER_COMPLETED",
  "locale": "es",
  "occurredAt": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
  "data": {
    "accountNumber": "9876543210",
    "amount": "150000.00",
    "currency": "COP",
    "recipient": "Carlos Mendoza"
  }
}
EOF
)

HTTP_CODE=$(curl -s -o /tmp/pigeon_resp1.json -w "%{http_code}" -X POST "$BASE_URL/api/v1/events" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $IDEMPOTENCY_KEY_1" \
    -d "$TRANSFER_PAYLOAD" || true)

echo "HTTP Status Code: $HTTP_CODE"
if [ "$HTTP_CODE" = "202" ] || [ "$HTTP_CODE" = "200" ]; then
    cat /tmp/pigeon_resp1.json
    echo ""
    NOTIFICATION_ID_1=$(grep -o '"notificationId":"[^"]*' /tmp/pigeon_resp1.json | cut -d'"' -f4 || echo "")
    echo "Notification Accepted: $NOTIFICATION_ID_1"
    echo "Check MailHog at http://localhost:8025 to see the delivered email with masked account."
else
    echo "Response: $(cat /tmp/pigeon_resp1.json 2>/dev/null || echo 'Connection failed')"
    echo "(Ensure Pigeon application is running on $BASE_URL)"
fi
echo ""

# Scenario 2: Idempotent Replay
echo "------------------------------------------------------------------------"
echo "Scenario 2: Idempotent Replay (Same Idempotency-Key & Payload)"
echo "------------------------------------------------------------------------"
HTTP_CODE_2=$(curl -s -o /tmp/pigeon_resp2.json -w "%{http_code}" -X POST "$BASE_URL/api/v1/events" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $IDEMPOTENCY_KEY_1" \
    -d "$TRANSFER_PAYLOAD" || true)

echo "HTTP Status Code: $HTTP_CODE_2 (Expected: 200 OK with Idempotency-Replayed header)"
cat /tmp/pigeon_resp2.json 2>/dev/null || true
echo ""
echo ""

# Scenario 3: High-Priority Fraud Alert (Bypasses Quiet Hours & Opt-out)
echo "------------------------------------------------------------------------"
echo "Scenario 3: High-Priority Alert (FRAUD_SUSPECTED)"
echo "------------------------------------------------------------------------"
IDEMPOTENCY_KEY_3="demo-fraud-$(date +%s%N)"
FRAUD_PAYLOAD=$(cat <<EOF
{
  "customerId": "cust-1002",
  "eventType": "FRAUD_SUSPECTED",
  "locale": "en",
  "occurredAt": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
  "data": {
    "accountNumber": "1122334455",
    "location": "Lagos, NG",
    "device": "Unknown Android Browser"
  }
}
EOF
)

HTTP_CODE_3=$(curl -s -o /tmp/pigeon_resp3.json -w "%{http_code}" -X POST "$BASE_URL/api/v1/events" \
    -H "Authorization: Bearer $JWT" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $IDEMPOTENCY_KEY_3" \
    -d "$FRAUD_PAYLOAD" || true)

echo "HTTP Status Code: $HTTP_CODE_3"
cat /tmp/pigeon_resp3.json 2>/dev/null || true
echo ""
echo "Fraud alert routed to pigeon.high-priority queue. Bypasses quiet hours."
echo ""

# Scenario 4: Query Notification Status and Cryptographic Audit Trail
if [ -n "${NOTIFICATION_ID_1:-}" ]; then
    echo "------------------------------------------------------------------------"
    echo "Scenario 4: Querying Notification & Audit Trail for $NOTIFICATION_ID_1"
    echo "------------------------------------------------------------------------"
    sleep 1 # allow outbox relay to deliver
    curl -s -X GET "$BASE_URL/api/v1/notifications/$NOTIFICATION_ID_1" \
        -H "Authorization: Bearer $JWT" | jq . 2>/dev/null || cat /tmp/pigeon_resp1.json
    echo ""
fi

# Scenario 5: Webhook Delivery Receipt with HMAC-SHA256 Signature
echo "------------------------------------------------------------------------"
echo "Scenario 5: Asynchronous Provider Delivery Receipt via Signed Webhook"
echo "------------------------------------------------------------------------"
SAMPLE_NID="${NOTIFICATION_ID_1:-00000000-0000-0000-0000-000000000001}"
RECEIPT_PAYLOAD=$(cat <<EOF
{
  "notificationId": "$SAMPLE_NID",
  "providerRef": "wiremock-dlr-999888",
  "status": "DELIVERED",
  "occurredAt": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
}
EOF
)

SIGNATURE=$(generate_hmac "$RECEIPT_PAYLOAD")
echo "Computed HMAC-SHA256 signature: $SIGNATURE"

HTTP_CODE_5=$(curl -s -o /tmp/pigeon_resp5.json -w "%{http_code}" -X POST "$BASE_URL/api/v1/webhooks/sms/receipts" \
    -H "Content-Type: application/json" \
    -H "X-Signature: $SIGNATURE" \
    -d "$RECEIPT_PAYLOAD" || true)

echo "HTTP Status Code: $HTTP_CODE_5"
cat /tmp/pigeon_resp5.json 2>/dev/null || true
echo ""

echo "========================================================================"
echo "Demo scenarios finished. Inspect components:"
echo " - Swagger UI:          $BASE_URL/swagger-ui.html"
echo " - MailHog UI:          http://localhost:8025"
echo " - RabbitMQ Management: http://localhost:15672 (guest/guest)"
echo " - Prometheus:          http://localhost:9090"
echo " - Grafana:             http://localhost:3000 (admin/admin)"
echo "========================================================================"
