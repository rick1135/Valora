#!/usr/bin/env bash
set -euo pipefail

# Smoke test reproduzível de instalação limpa do Valora usando o docker-compose.yml do projeto
# com override mínimo temporário e projeto isolado (-p). Não toca dados/volumes existentes e não imprime segredos.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
PROJECT_COMPOSE_FILE="${REPO_ROOT}/docker-compose.yml"

log() {
    printf '[smoke] %s\n' "$*"
}

fail() {
    printf '[smoke][ERRO] %s\n' "$*" >&2
    exit 1
}

require_cmd() {
    command -v "$1" >/dev/null 2>&1 || fail "Comando obrigatório não encontrado: $1"
}

require_cmd docker
require_cmd curl
require_cmd python3

[[ -f "$PROJECT_COMPOSE_FILE" ]] || fail "Arquivo não encontrado: ${PROJECT_COMPOSE_FILE}"

if ! docker compose version >/dev/null 2>&1; then
    fail "Plugin 'docker compose' não está disponível."
fi

TMP_DIR="$(mktemp -d)"
PROJECT_NAME="valora-smoke-$$-$(date +%s)"
OVERRIDE_FILE="${TMP_DIR}/docker-compose.smoke.override.yml"
ENV_FILE="${TMP_DIR}/.env.smoke"
APP_LOG_FILE="${TMP_DIR}/app.log"
KEEP_STACK="${KEEP_STACK:-0}"
CURL_CONNECT_TIMEOUT="${CURL_CONNECT_TIMEOUT:-5}"
CURL_MAX_TIME="${CURL_MAX_TIME:-30}"

dc() {
    env -u JWT_SECRET -u BRAPI_TOKEN -u SPRING_PROFILES_ACTIVE \
        docker compose -p "$PROJECT_NAME" -f "$PROJECT_COMPOSE_FILE" -f "$OVERRIDE_FILE" --env-file "$ENV_FILE" "$@"
}

cleanup() {
    local exit_code="${1:-$?}"
    trap - EXIT INT TERM

    if [[ "$exit_code" -ne 0 && -f "$OVERRIDE_FILE" && -f "$ENV_FILE" ]]; then
        log "Falha detectada (exit=${exit_code}). Últimas linhas de log da aplicação:"
        dc logs --no-color --tail=60 app 2>/dev/null || true
    fi
    if [[ "$KEEP_STACK" != "1" ]]; then
        if [[ -f "$OVERRIDE_FILE" && -f "$ENV_FILE" ]]; then
            log "Removendo stack e volumes descartáveis (${PROJECT_NAME})..."
            local down_err_file="${TMP_DIR}/down.err"
            if ! dc down -v --remove-orphans >/dev/null 2>"$down_err_file"; then
                log "AVISO: falha ao remover stack (${PROJECT_NAME}) com 'docker compose down -v --remove-orphans':"
                cat "$down_err_file" >&2 || true
                if [[ "$exit_code" -eq 0 ]]; then
                    exit_code=1
                fi
            fi
        fi
        rm -rf "$TMP_DIR"
    else
        log "KEEP_STACK=1 ativo; stack mantida: ${PROJECT_NAME}"
        log "Arquivos temporários preservados em: ${TMP_DIR}"
        log "Para remover depois: docker compose -p \"${PROJECT_NAME}\" -f \"${PROJECT_COMPOSE_FILE}\" -f \"${OVERRIDE_FILE}\" --env-file \"${ENV_FILE}\" down -v --remove-orphans && rm -rf \"${TMP_DIR}\""
    fi
    exit "$exit_code"
}

on_signal() {
    local sig="$1"
    local code=1
    case "$sig" in
        INT) code=130 ;;
        TERM) code=143 ;;
    esac
    log "Sinal ${sig} recebido; executando cleanup..."
    cleanup "$code"
}

trap 'cleanup' EXIT
trap 'on_signal INT' INT
trap 'on_signal TERM' TERM

JWT_SECRET_VALUE="$(python3 -c 'import secrets; print(secrets.token_urlsafe(48))')"

umask 077
cat > "$ENV_FILE" <<EOF
SPRING_PROFILES_ACTIVE=prod
JWT_SECRET=${JWT_SECRET_VALUE}
BRAPI_TOKEN=
EOF

cat > "$OVERRIDE_FILE" <<EOF
services:
  postgres:
    container_name: !reset null
    ports: !reset []

  redis:
    container_name: !reset null
    ports: !reset []

  app:
    container_name: !reset null
    ports: !override
      - "127.0.0.1:${SMOKE_PORT:-0}:8080"
EOF

if ! dc config >/dev/null; then
    fail "Configuração do Docker Compose inválida (verifique se a versão instalada suporta '!reset' e '!override')."
fi

discover_base_url() {
    local app_addr app_port
    app_addr="$(dc port app 8080 | head -n1 | tr -d '[:space:]')"
    app_port="${app_addr##*:}"
    [[ -n "$app_port" && "$app_port" =~ ^[0-9]+$ ]] || fail "Não foi possível descobrir a porta da aplicação via 'docker compose port app 8080' (recebido: '${app_addr}')."
    APP_PORT="$app_port"
    BASE_URL="http://127.0.0.1:${APP_PORT}"
}

wait_for_app_ready() {
    local ready=0
    local code
    log "Aguardando aplicação responder em ${BASE_URL}/v3/api-docs..."
    for _ in $(seq 1 60); do
        code="$(curl -s --connect-timeout 2 --max-time 5 -o /dev/null -w '%{http_code}' "${BASE_URL}/v3/api-docs" || true)"
        if [[ "$code" == "200" ]]; then
            ready=1
            break
        fi
        sleep 2
    done
    [[ "$ready" == "1" ]] || fail "Aplicação não ficou pronta a tempo na porta ${APP_PORT}."
}

http_req() {
    local method="$1"
    local path="$2"
    local token="${3:-}"
    local body="${4:-}"
    local out_file="${TMP_DIR}/resp.json"

    local -a args=(
        -sS
        --connect-timeout "$CURL_CONNECT_TIMEOUT"
        --max-time "$CURL_MAX_TIME"
        -o "$out_file"
        -w '%{http_code}'
        -X "$method"
        "${BASE_URL}${path}"
        -H "Accept: application/json"
    )
    if [[ -n "$token" ]]; then
        args+=(-H "Authorization: Bearer ${token}")
    fi
    if [[ -n "$body" ]]; then
        args+=(-H "Content-Type: application/json" -d "$body")
    fi

    HTTP_STATUS="$(curl "${args[@]}")"
    HTTP_BODY_FILE="$out_file"
}

assert_status() {
    local expected="$1"
    local context="$2"
    if [[ "$HTTP_STATUS" != "$expected" ]]; then
        log "Resposta inesperada em '${context}': HTTP ${HTTP_STATUS} (esperado ${expected}; corpo omitido por segurança)"
        fail "Falha na asserção HTTP: ${context}"
    fi
}

json_field() {
    local field="$1"
    python3 - "$HTTP_BODY_FILE" "$field" <<'PY'
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)
if not isinstance(data, dict):
    sys.exit(2)
val = data.get(sys.argv[2])
if val is None:
    sys.exit(2)
print(val)
PY
}

json_array_len() {
    python3 - "$HTTP_BODY_FILE" <<'PY'
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)
if not isinstance(data, list):
    sys.exit(2)
print(len(data))
PY
}

json_array_first_field() {
    local field="$1"
    python3 - "$HTTP_BODY_FILE" "$field" <<'PY'
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)
if not isinstance(data, list) or not data or not isinstance(data[0], dict):
    sys.exit(2)
value = data[0].get(sys.argv[2])
if value is None:
    sys.exit(2)
print(value)
PY
}

json_decimal_equals() {
    local field="$1"
    local expected="$2"
    python3 - "$HTTP_BODY_FILE" "$field" "$expected" <<'PY'
import json, sys
from decimal import Decimal
with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)
if not isinstance(data, dict) or data.get(sys.argv[2]) is None:
    sys.exit(2)
print("true" if Decimal(str(data[sys.argv[2]])) == Decimal(sys.argv[3]) else "false")
PY
}

json_array_contains() {
    local field="$1"
    local expected="$2"
    local mode="${3:-exact}"
    python3 - "$HTTP_BODY_FILE" "$field" "$expected" "$mode" <<'PY'
import json, sys
with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)
if not isinstance(data, list):
    sys.exit(2)
field, expected, mode = sys.argv[2], sys.argv[3], sys.argv[4]
if mode == "ci":
    expected_cmp = expected.upper()
    found = any(isinstance(item, dict) and str(item.get(field) or "").upper() == expected_cmp for item in data)
else:
    found = any(isinstance(item, dict) and str(item.get(field) or "") == expected for item in data)
print("true" if found else "false")
PY
}

log "Subindo stack isolada '${PROJECT_NAME}' com docker-compose.yml do projeto (build via Docker)..."
dc up -d --build
discover_base_url
log "Aplicação exposta em ${BASE_URL}"
wait_for_app_ready

log "1/8 Validando profile 'prod' nos logs..."
dc logs --no-color app > "$APP_LOG_FILE" 2>&1
if ! grep -qE 'profile(s)? (is|are) active:.*"prod"' "$APP_LOG_FILE"; then
    fail "Profile 'prod' não confirmado nos logs da aplicação."
fi

log "2/8 Validando PostgreSQL, Flyway (incluindo V14) e Redis reais..."
FLYWAY_OK="$(dc exec -T postgres psql -U valora -d valora -tAc "SELECT count(*) FROM flyway_schema_history WHERE success = true;" | tr -d '[:space:]')"
FLYWAY_FAIL="$(dc exec -T postgres psql -U valora -d valora -tAc "SELECT count(*) FROM flyway_schema_history WHERE success = false;" | tr -d '[:space:]')"
FLYWAY_V14_OK="$(dc exec -T postgres psql -U valora -d valora -tAc "SELECT count(*) FROM flyway_schema_history WHERE version = '14' AND success = true;" | tr -d '[:space:]')"
[[ "${FLYWAY_OK:-0}" -ge 1 ]] || fail "Nenhuma migração Flyway aplicada com sucesso."
[[ "${FLYWAY_FAIL:-1}" -eq 0 ]] || fail "Existem migrações Flyway com falha (${FLYWAY_FAIL})."
[[ "${FLYWAY_V14_OK:-0}" -eq 1 ]] || fail "Migração V14 (seed inicial de ativos) não foi aplicada com sucesso (count=${FLYWAY_V14_OK:-0})."

REDIS_PONG="$(dc exec -T redis redis-cli ping | tr -d '[:space:]')"
[[ "$REDIS_PONG" == "PONG" ]] || fail "Redis não respondeu PONG (recebido: ${REDIS_PONG})."
log "Flyway OK (${FLYWAY_OK} migrações, V14 confirmada), Redis OK (${REDIS_PONG})."

log "3/8 Validando cadastro e login..."
UNIQUE_TAG="$(date +%s)-$$"
USER_A_EMAIL="smoke.a.${UNIQUE_TAG}@valora.local"
USER_B_EMAIL="smoke.b.${UNIQUE_TAG}@valora.local"
SMOKE_PASS="ValoraSmoke!2026"

http_req POST "/auth/register" "" "{\"name\":\"Smoke User A\",\"email\":\"${USER_A_EMAIL}\",\"password\":\"${SMOKE_PASS}\"}"
assert_status "201" "cadastro usuário A"
TOKEN_A_REG="$(json_field "token")"
REFRESH_A_REG="$(json_field "refreshToken")"
[[ -n "$TOKEN_A_REG" && -n "$REFRESH_A_REG" ]] || fail "Cadastro usuário A não retornou tokens."

http_req POST "/auth/login" "" "{\"email\":\"${USER_A_EMAIL}\",\"password\":\"${SMOKE_PASS}\"}"
assert_status "200" "login usuário A"
TOKEN_A="$(json_field "token")"
REFRESH_A1="$(json_field "refreshToken")"
[[ -n "$TOKEN_A" && -n "$REFRESH_A1" ]] || fail "Login usuário A não retornou tokens."

http_req POST "/auth/register" "" "{\"name\":\"Smoke User B\",\"email\":\"${USER_B_EMAIL}\",\"password\":\"${SMOKE_PASS}\"}"
assert_status "201" "cadastro usuário B"
TOKEN_B="$(json_field "token")"
[[ -n "$TOKEN_B" ]] || fail "Cadastro usuário B não retornou token."

log "4/8 Validando catálogo inicial de ativos (GET /assets e /assets/search?ticker=PETR4)..."
http_req GET "/assets?page=0&size=20" "$TOKEN_A"
assert_status "200" "listar catálogo inicial"
ASSETS_COUNT="$(json_array_len)"
[[ "$ASSETS_COUNT" -ge 1 ]] || fail "Catálogo inicial vazio (esperado >= 1 ativo)."

http_req GET "/assets/search?ticker=PETR4" "$TOKEN_A"
assert_status "200" "buscar PETR4 no catálogo inicial"
PETR4_FOUND="$(json_array_contains "ticker" "PETR4" "ci")"
[[ "$PETR4_FOUND" == "true" ]] || fail "Ativo PETR4 não encontrado em /assets/search?ticker=PETR4."
PETR4_ID="$(json_array_first_field "id")"
log "Catálogo inicial OK (${ASSETS_COUNT} ativos na página 0, PETR4 presente)."

log "5/8 Validando rotação e revogação de refresh token..."
http_req POST "/auth/refresh" "" "{\"refreshToken\":\"${REFRESH_A1}\"}"
assert_status "200" "rotação de refresh token"
TOKEN_A_NEW="$(json_field "token")"
REFRESH_A2="$(json_field "refreshToken")"
[[ -n "$TOKEN_A_NEW" && -n "$REFRESH_A2" ]] || fail "Refresh não retornou novo par de tokens."
[[ "$REFRESH_A2" != "$REFRESH_A1" ]] || fail "Refresh token não foi rotacionado."

http_req POST "/auth/refresh" "" "{\"refreshToken\":\"${REFRESH_A1}\"}"
assert_status "401" "reuso de refresh token rotacionado deve retornar 401"

http_req POST "/auth/logout" "" "{\"refreshToken\":\"${REFRESH_A2}\"}"
assert_status "204" "logout deve revogar refresh token (204)"

http_req POST "/auth/refresh" "" "{\"refreshToken\":\"${REFRESH_A2}\"}"
assert_status "401" "reuso de refresh token revogado deve retornar 401"
log "Rotação e revogação de refresh token OK."

log "6/8 Validando isolamento entre usuários por HTTP..."
http_req GET "/portfolios"
assert_status "401" "acesso sem autenticação a /portfolios deve retornar 401"

http_req POST "/portfolios" "$TOKEN_A_NEW" '{"name":"Carteira Smoke A","description":"Isolamento HTTP"}'
assert_status "201" "usuário A cria carteira"
PORTFOLIO_A_ID="$(json_field "id")"
[[ -n "$PORTFOLIO_A_ID" ]] || fail "ID da carteira do usuário A vazio."

http_req GET "/portfolios/${PORTFOLIO_A_ID}" "$TOKEN_A_NEW"
assert_status "200" "usuário A acessa própria carteira"

http_req GET "/portfolios" "$TOKEN_B"
assert_status "200" "usuário B lista próprias carteiras"
LEAKED="$(json_array_contains "id" "$PORTFOLIO_A_ID")"
[[ "$LEAKED" == "false" ]] || fail "Vazamento de carteira entre usuários em GET /portfolios."

http_req GET "/portfolios/${PORTFOLIO_A_ID}" "$TOKEN_B"
assert_status "404" "usuário B não pode ler carteira de A"

http_req GET "/portfolios/${PORTFOLIO_A_ID}/summary" "$TOKEN_B"
assert_status "404" "usuário B não pode ler summary de A"

http_req GET "/portfolios/${PORTFOLIO_A_ID}/positions" "$TOKEN_B"
assert_status "404" "usuário B não pode ler positions de A"

http_req DELETE "/portfolios/${PORTFOLIO_A_ID}" "$TOKEN_B"
assert_status "404" "usuário B não pode excluir carteira de A"

log "7/8 Validando compra, metas e páginas complementares..."
TX_DATE="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
http_req POST "/transactions" "$TOKEN_A_NEW" "{\"portfolioId\":\"${PORTFOLIO_A_ID}\",\"assetId\":\"${PETR4_ID}\",\"type\":\"BUY\",\"quantity\":10,\"unitPrice\":10,\"transactionDate\":\"${TX_DATE}\"}"
assert_status "201" "registrar compra de PETR4"

http_req GET "/portfolios/${PORTFOLIO_A_ID}/positions" "$TOKEN_A_NEW"
assert_status "200" "posições após compra"
[[ "$(json_array_contains "ticker" "PETR4" "ci")" == "true" ]] || fail "PETR4 ausente das posições após compra."

http_req GET "/portfolios/${PORTFOLIO_A_ID}/summary" "$TOKEN_A_NEW"
assert_status "200" "resumo após compra"
[[ "$(json_decimal_equals "totalInvested" "100")" == "true" ]] || fail "Resumo não mostra custo investido de R$ 100 após compra."

http_req PUT "/portfolios/${PORTFOLIO_A_ID}/targets" "$TOKEN_A_NEW" '[{"assetCategory":"ACOES","targetPercentage":100}]'
assert_status "204" "salvar meta de alocação"
http_req GET "/portfolios/${PORTFOLIO_A_ID}/targets" "$TOKEN_A_NEW"
assert_status "200" "ler metas salvas"
[[ "$(json_array_contains "assetCategory" "ACOES")" == "true" ]] || fail "Meta ACOES não foi retornada."
http_req GET "/portfolios/${PORTFOLIO_A_ID}/rebalance?contributionAmount=100" "$TOKEN_A_NEW"
assert_status "200" "simular aporte"
http_req GET "/portfolios/${PORTFOLIO_A_ID}/targets" "$TOKEN_B"
assert_status "404" "usuário B não pode ler metas de A"

for page in dashboard.html proventos.html rentabilidade.html metas.html; do
    http_req GET "/${page}"
    assert_status "200" "página ${page}"
done

log "8/8 Validando persistência após reinício do serviço app..."
dc restart app
discover_base_url
wait_for_app_ready

http_req POST "/auth/login" "" "{\"email\":\"${USER_A_EMAIL}\",\"password\":\"${SMOKE_PASS}\"}"
assert_status "200" "login usuário A após reinício do app"
TOKEN_A_AFTER_RESTART="$(json_field "token")"
[[ -n "$TOKEN_A_AFTER_RESTART" ]] || fail "Login usuário A após reinício não retornou token."

http_req GET "/portfolios/${PORTFOLIO_A_ID}" "$TOKEN_A_AFTER_RESTART"
assert_status "200" "usuário A acessa carteira persistida após reinício do app"

http_req GET "/portfolios" "$TOKEN_A_AFTER_RESTART"
assert_status "200" "usuário A lista carteiras após reinício do app"
PERSISTED="$(json_array_contains "id" "$PORTFOLIO_A_ID")"
[[ "$PERSISTED" == "true" ]] || fail "Carteira criada antes do reinício não foi encontrada após reinício do app."
log "Persistência após reinício do app OK."

log "SUCESSO: Todos os checks do smoke test de instalação limpa passaram!"
