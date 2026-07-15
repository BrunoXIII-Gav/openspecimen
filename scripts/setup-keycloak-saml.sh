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

generate_keystore() {
  require_var OS_SAML_KEYSTORE_LOCAL_PATH
  require_var OS_SAML_KEY_ALIAS
  require_var OS_SAML_KEYSTORE_TYPE
  require_var OS_SAML_KEYSTORE_PASSWORD
  require_var OS_SAML_KEY_PASSWORD
  require_var OS_SAML_KEYALG
  require_var OS_SAML_KEYSIZE
  require_var OS_SAML_CERT_DNAME

  mkdir -p "$(dirname "$OS_SAML_KEYSTORE_LOCAL_PATH")"

  if [[ -f "$OS_SAML_KEYSTORE_LOCAL_PATH" ]]; then
    echo "Keystore already exists: $OS_SAML_KEYSTORE_LOCAL_PATH"
    return
  fi

  keytool -genkeypair \
    -alias "$OS_SAML_KEY_ALIAS" \
    -keyalg "$OS_SAML_KEYALG" \
    -keysize "$OS_SAML_KEYSIZE" \
    -storetype "$OS_SAML_KEYSTORE_TYPE" \
    -keystore "$OS_SAML_KEYSTORE_LOCAL_PATH" \
    -storepass "$OS_SAML_KEYSTORE_PASSWORD" \
    -keypass "$OS_SAML_KEY_PASSWORD" \
    -dname "$OS_SAML_CERT_DNAME"

  echo "Created keystore: $OS_SAML_KEYSTORE_LOCAL_PATH"
}

fetch_admin_token() {
  require_var OS_PUBLIC_URL
  require_var OS_ADMIN_LOGIN_NAME
  require_var OS_ADMIN_PASSWORD
  require_var OS_ADMIN_DOMAIN_NAME

  local response
  response="$(
    curl -fsS "${OS_PUBLIC_URL%/}/rest/ng/sessions" \
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

    curl -fsS -X PUT "${OS_PUBLIC_URL%/}/rest/ng/auth-domains/${OS_SAML_AUTH_DOMAIN_ID}" \
      -H 'Content-Type: application/json' \
      -H 'Accept: application/json' \
      -H "X-OS-API-TOKEN: ${token}" \
      --data @"$payload_file"
  else
    curl -fsS -X POST "${OS_PUBLIC_URL%/}/rest/ng/auth-domains" \
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
