#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/docker-compose-ci.yml"
STATE_DIR="/tmp/plugin-argocd-ci"
BIN_DIR="$STATE_DIR/bin"
KUBECONFIG_FILE="$STATE_DIR/kubeconfig"
PORT_FORWARD_PID_FILE="$STATE_DIR/port-forward.pid"
PORT_FORWARD_LOG_FILE="$STATE_DIR/port-forward.log"
SETUP_ENV_FILE="$STATE_DIR/setup.env"
TOKEN_FILE="$STATE_DIR/admin.token"
KIND_CLUSTER_NAME="plugin-argocd-ci"
ARGOCD_SERVER_HOSTPORT="127.0.0.1:18080"
ARGOCD_SERVER_URL="https://${ARGOCD_SERVER_HOSTPORT}"
ARGOCD_APP_NAME="plugin-argocd-guestbook"
ARGOCD_INSTALL_MANIFEST_URL="https://raw.githubusercontent.com/argoproj/argo-cd/v2.11.7/manifests/install.yaml"

mkdir -p "$STATE_DIR" "$BIN_DIR"
export PATH="$BIN_DIR:$PATH"
export KUBECONFIG="$KUBECONFIG_FILE"

install_kind() {
  if command -v kind >/dev/null 2>&1; then
    return 0
  fi

  local arch
  arch="$(uname -m)"
  case "$arch" in
    x86_64) arch="amd64" ;;
    aarch64|arm64) arch="arm64" ;;
    *)
      echo "Unsupported architecture for kind: $arch" >&2
      exit 1
      ;;
  esac

  curl -fsSL -o "$BIN_DIR/kind" "https://kind.sigs.k8s.io/dl/v0.23.0/kind-linux-${arch}"
  chmod +x "$BIN_DIR/kind"
}

install_kubectl() {
  if command -v kubectl >/dev/null 2>&1; then
    return 0
  fi

  local arch
  arch="$(uname -m)"
  case "$arch" in
    x86_64) arch="amd64" ;;
    aarch64|arm64) arch="arm64" ;;
    *)
      echo "Unsupported architecture for kubectl: $arch" >&2
      exit 1
      ;;
  esac

  local version
  version="$(curl -fsSL https://dl.k8s.io/release/stable.txt)"

  curl -fsSL -o "$BIN_DIR/kubectl" "https://dl.k8s.io/release/${version}/bin/linux/${arch}/kubectl"
  chmod +x "$BIN_DIR/kubectl"
}

create_kind_cluster() {
  if ! kind get clusters | grep -qx "$KIND_CLUSTER_NAME"; then
    kind create cluster --name "$KIND_CLUSTER_NAME" --wait 180s --kubeconfig "$KUBECONFIG_FILE"
  fi

  kind export kubeconfig --name "$KIND_CLUSTER_NAME" --kubeconfig "$KUBECONFIG_FILE"
}

deploy_argocd() {
  kubectl get namespace argocd >/dev/null 2>&1 || kubectl create namespace argocd
  kubectl apply -n argocd -f "$ARGOCD_INSTALL_MANIFEST_URL"

  kubectl rollout status deployment/argocd-server -n argocd --timeout=300s
  kubectl rollout status deployment/argocd-repo-server -n argocd --timeout=300s
  kubectl rollout status deployment/argocd-redis -n argocd --timeout=300s
  kubectl rollout status statefulset/argocd-application-controller -n argocd --timeout=300s
}

start_port_forward() {
  if [[ -f "$PORT_FORWARD_PID_FILE" ]]; then
    local old_pid
    old_pid="$(cat "$PORT_FORWARD_PID_FILE")"
    if kill -0 "$old_pid" 2>/dev/null; then
      kill "$old_pid" || true
      sleep 1
    fi
  fi

  nohup env KUBECONFIG="$KUBECONFIG_FILE" bash -c '
    while true; do
      kubectl -n argocd port-forward svc/argocd-server 18080:443
      sleep 1
    done
  ' >"$PORT_FORWARD_LOG_FILE" 2>&1 &
  echo "$!" > "$PORT_FORWARD_PID_FILE"
}

wait_for_argocd_api() {
  for i in $(seq 1 45); do
    if curl -ksSf --connect-timeout 2 --max-time 5 "${ARGOCD_SERVER_URL}/api/version" >/dev/null; then
      return 0
    fi
    sleep 2
  done

  echo "ArgoCD API did not become reachable in time." >&2
  cat "$PORT_FORWARD_LOG_FILE" >&2 || true
  exit 1
}

bootstrap_argocd_app() {
  local admin_password
  admin_password="$(
    kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 --decode
  )"

  local session_response
  local admin_token
  admin_token=""

  for i in $(seq 1 30); do
    session_response="$(
      curl -ksS "${ARGOCD_SERVER_URL}/api/v1/session" \
        --connect-timeout 2 \
        --max-time 5 \
        -H "Content-Type: application/json" \
        --data "{\"username\":\"admin\",\"password\":\"${admin_password}\"}" || true
    )"
    admin_token="$(echo "$session_response" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"

    if [[ -n "$admin_token" ]]; then
      break
    fi
    sleep 2
  done

  if [[ -z "$admin_token" ]]; then
    echo "Failed to generate ArgoCD API token." >&2
    echo "Session response: $session_response" >&2
    exit 1
  fi

  cat <<EOF | kubectl apply -f -
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: $ARGOCD_APP_NAME
  namespace: argocd
spec:
  project: default
  source:
    repoURL: https://github.com/argoproj/argocd-example-apps.git
    targetRevision: HEAD
    path: guestbook
  destination:
    server: https://kubernetes.default.svc
    namespace: default
EOF

  kubectl -n argocd get application "$ARGOCD_APP_NAME" >/dev/null

  printf '%s' "$admin_token" > "$TOKEN_FILE"
  chmod 600 "$TOKEN_FILE"

  cat > "$SETUP_ENV_FILE" <<EOF
ARGOCD_TEST_SERVER=$ARGOCD_SERVER_URL
ARGOCD_TEST_APP=$ARGOCD_APP_NAME
ARGOCD_TEST_TOKEN_FILE=$TOKEN_FILE
EOF
}

docker compose -f "$COMPOSE_FILE" up -d

helper_ready=false
for i in $(seq 1 20); do
  status=$(docker inspect --format '{{json .State.Health.Status}}' plugin-argocd-ci 2>/dev/null | tr -d '"')

  if [ "$status" = "healthy" ]; then
    helper_ready=true
    break
  fi

  sleep 2
done

if [ "$helper_ready" != "true" ]; then
  echo "ArgoCD test container did not become healthy in time." >&2
  docker compose -f "$COMPOSE_FILE" logs argocd || true
  exit 1
fi

install_kind
install_kubectl
create_kind_cluster
deploy_argocd
start_port_forward
wait_for_argocd_api
bootstrap_argocd_app

echo "ArgoCD integration environment is ready."
