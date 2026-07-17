#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ENV_FILE:-$ROOT_DIR/.env}"

if [[ -f "$ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  set -a && source "$ENV_FILE" && set +a
fi

require_var() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required variable: $name" >&2
    exit 1
  fi
}

resolve_repo_path() {
  local path="$1"

  if [[ "$path" = /* ]]; then
    printf '%s\n' "$path"
  else
    path="${path#./}"
    printf '%s/%s\n' "$ROOT_DIR" "$path"
  fi
}

json_get() {
  local key="$1"
  python3 -c '
import json
import sys

key = sys.argv[1]
payload = json.load(sys.stdin)
value = payload
for part in key.split("."):
    value = value[part]
print(value)
' "$key"
}

wait_for_openspecimen() {
  require_var OS_PUBLIC_URL

  local health_url="${OS_PUBLIC_URL%/}/ui-app/"
  local timeout_seconds="${OS_READY_TIMEOUT_SECONDS:-300}"
  local started_at elapsed_seconds
  started_at="$(date +%s)"

  while true; do
    if curl -fsS "$health_url" >/dev/null 2>&1; then
      return 0
    fi

    elapsed_seconds=$(( $(date +%s) - started_at ))
    if (( elapsed_seconds >= timeout_seconds )); then
      echo "OpenSpecimen is not ready yet: $health_url" >&2
      echo "Waited ${timeout_seconds}s for the UI health URL to respond successfully." >&2
      return 1
    fi

    sleep 5
  done
}

run_os_request() {
  local method="$1"
  local url="$2"
  shift 2

  local response_file http_status
  response_file="$(mktemp)"

  if ! http_status="$(
    curl -sS -o "$response_file" -w '%{http_code}' -X "$method" "$url" "$@"
  )"; then
    echo "OpenSpecimen request failed: ${method} ${url}" >&2
    if [[ -s "$response_file" ]]; then
      echo "Response body:" >&2
      cat "$response_file" >&2
    fi
    rm -f "$response_file"
    return 1
  fi

  if [[ ! "$http_status" =~ ^2 ]]; then
    echo "OpenSpecimen request failed: ${method} ${url} -> HTTP ${http_status}" >&2
    if [[ -s "$response_file" ]]; then
      echo "Response body:" >&2
      cat "$response_file" >&2
    fi
    rm -f "$response_file"
    return 1
  fi

  cat "$response_file"
  rm -f "$response_file"
}

generate_keystore() {
  require_var OS_SAML_KEYSTORE_LOCAL_PATH
  require_var OS_SAML_KEY_ALIAS
  require_var OS_SAML_KEYSTORE_TYPE
  require_var OS_SAML_KEYSTORE_PASSWORD
  require_var OS_SAML_KEY_PASSWORD
  require_var OS_SAML_KEYALG
  require_var OS_SAML_KEYSIZE
  require_var OS_SAML_CERT_DNAME

  local keystore_local_path
  keystore_local_path="$(resolve_repo_path "$OS_SAML_KEYSTORE_LOCAL_PATH")"

  mkdir -p "$(dirname "$keystore_local_path")"

  if [[ -f "$keystore_local_path" ]]; then
    echo "Keystore already exists: $keystore_local_path"
    return
  fi

  keytool -genkeypair \
    -alias "$OS_SAML_KEY_ALIAS" \
    -keyalg "$OS_SAML_KEYALG" \
    -keysize "$OS_SAML_KEYSIZE" \
    -storetype "$OS_SAML_KEYSTORE_TYPE" \
    -keystore "$keystore_local_path" \
    -storepass "$OS_SAML_KEYSTORE_PASSWORD" \
    -keypass "$OS_SAML_KEY_PASSWORD" \
    -dname "$OS_SAML_CERT_DNAME"

  echo "Created keystore: $keystore_local_path"
}

fetch_admin_token() {
  require_var OS_PUBLIC_URL
  require_var OS_ADMIN_LOGIN_NAME
  require_var OS_ADMIN_PASSWORD
  require_var OS_ADMIN_DOMAIN_NAME

  local response
  wait_for_openspecimen
  response="$(
    run_os_request POST "${OS_PUBLIC_URL%/}/rest/ng/sessions" \
      -H 'Content-Type: application/json' \
      -H 'Accept: application/json' \
      --data-raw "{
        \"loginName\": \"${OS_ADMIN_LOGIN_NAME}\",
        \"password\": \"${OS_ADMIN_PASSWORD}\",
        \"domainName\": \"${OS_ADMIN_DOMAIN_NAME}\"
      }"
  )"

  printf '%s' "$response" | json_get token
}

configure_auth_domain() {
  require_var OS_PUBLIC_URL
  require_var OS_SAML_AUTH_DOMAIN_NAME
  require_var OS_SAML_ENTITY_ID
  require_var OS_SAML_IDP_METADATA_URL
  require_var OS_SAML_KEYSTORE_PATH
  require_var OS_SAML_KEYSTORE_PASSWORD
  require_var OS_SAML_KEY_ALIAS
  require_var OS_SAML_KEY_PASSWORD
  require_var OS_SAML_LOGIN_NAME_ATTR

  local token payload_file
  token="$(fetch_admin_token)"
  payload_file="$(mktemp)"

  cat > "$payload_file" <<EOF
{
  "name": "${OS_SAML_AUTH_DOMAIN_NAME}",
  "allowLogins": true,
  "authType": "saml",
  "implClass": "com.krishagni.catissueplus.core.auth.services.impl.SamlAuthenticationServiceImpl",
  "activityStatus": "Active",
  "authProviderProps": {
    "entityId": "${OS_SAML_ENTITY_ID}",
    "idpMetadataURL": "${OS_SAML_IDP_METADATA_URL}",
    "keyStoreFilePath": "${OS_SAML_KEYSTORE_PATH}",
    "keyStorePasswd": "${OS_SAML_KEYSTORE_PASSWORD}",
    "keyAlias": "${OS_SAML_KEY_ALIAS}",
    "keyPasswd": "${OS_SAML_KEY_PASSWORD}",
    "loginNameAttr": "${OS_SAML_LOGIN_NAME_ATTR}"
  }
}
EOF

  if [[ -n "${OS_SAML_AUTH_DOMAIN_ID:-}" ]]; then
    python3 - "$payload_file" "$OS_SAML_AUTH_DOMAIN_ID" <<'PY'
import json
import sys

path = sys.argv[1]
domain_id = int(sys.argv[2])
with open(path, 'r', encoding='utf-8') as fh:
    payload = json.load(fh)
payload["id"] = domain_id
with open(path, 'w', encoding='utf-8') as fh:
    json.dump(payload, fh, indent=2)
PY

    run_os_request PUT "${OS_PUBLIC_URL%/}/rest/ng/auth-domains/${OS_SAML_AUTH_DOMAIN_ID}" \
      -H 'Content-Type: application/json' \
      -H 'Accept: application/json' \
      -H "X-OS-API-TOKEN: ${token}" \
      --data @"$payload_file"
  else
    run_os_request POST "${OS_PUBLIC_URL%/}/rest/ng/auth-domains" \
      -H 'Content-Type: application/json' \
      -H 'Accept: application/json' \
      -H "X-OS-API-TOKEN: ${token}" \
      --data @"$payload_file"
  fi

  rm -f "$payload_file"
  echo
  echo "OpenSpecimen SAML auth domain configured."
}

main() {
  generate_keystore
  configure_auth_domain
}

main "$@"
