#!/usr/bin/env bash
# Creates/updates the two k8s secrets from infra/.env. Idempotent.
set -euo pipefail
cd "$(dirname "$0")"
set -a; source .env; set +a

kubectl -n dhl-demo create secret generic dhl-secrets \
  --from-literal=db-password="$DB_PASSWORD" \
  --from-literal=keycloak-admin-password="$KEYCLOAK_ADMIN_PASSWORD" \
  --from-literal=locker-client-secret="$LOCKER_CLIENT_SECRET" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl -n dhl-demo create secret docker-registry ghcr \
  --docker-server=ghcr.io \
  --docker-username="$GHCR_USERNAME" \
  --docker-password="$GHCR_PAT" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "secrets applied to namespace dhl-demo"
