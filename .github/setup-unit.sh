#!/usr/bin/env bash
set -euo pipefail

docker compose -f docker-compose-ci.yml up -d

for i in $(seq 1 20); do
  status=$(docker inspect --format '{{json .State.Health.Status}}' plugin-argocd-ci 2>/dev/null | tr -d '"')

  if [ "$status" = "healthy" ]; then
    echo "ArgoCD test container is healthy."
    exit 0
  fi

  sleep 2
done

echo "ArgoCD test container did not become healthy in time." >&2
docker compose -f docker-compose-ci.yml logs argocd || true
exit 1
