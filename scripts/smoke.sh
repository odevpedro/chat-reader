#!/usr/bin/env sh
# Smoke test ponta a ponta contra um backend rodando (por padrao via compose).
#
#   cp .env.example .env   # preencha JWT_SECRET e CHAT_READER_PASSWORD_HASH
#   ./scripts/smoke.sh
#
# Verifica: health -> login -> import (cria) -> import de novo (idempotente)
#           -> listagem contem as conversas -> delta sync, snapshot e ack.
set -eu

base_url="${BASE_URL:-http://localhost:8080}"
sample="${1:-sample-data/chats.json}"
token=""

# Executa curl acrescentando o header de auth apenas quando ha token.
authed() {
    if [ -n "$token" ]; then
        curl "$@" -H "Authorization: Bearer $token"
    else
        curl "$@"
    fi
}

echo "==> aguardando $base_url/actuator/health"
i=0
until curl -fsS "$base_url/actuator/health" >/dev/null 2>&1; do
    i=$((i + 1))
    if [ "$i" -gt 60 ]; then
        echo "erro: backend nao respondeu em 60s" >&2
        exit 1
    fi
    sleep 1
done
echo "    ok"

if [ -n "${CHAT_READER_USERNAME:-}" ] && [ -n "${CHAT_READER_PASSWORD:-}" ]; then
    echo "==> login"
    token="$(curl -fsS -X POST "$base_url/api/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"$CHAT_READER_USERNAME\",\"password\":\"$CHAT_READER_PASSWORD\"}" \
        | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"
    if [ -z "$token" ]; then
        echo "erro: login nao devolveu token" >&2
        exit 1
    fi
    echo "    ok"
fi

echo "==> import (1a vez, deve criar)"
authed -fsS -X POST "$base_url/api/chats/import" -F "file=@$sample"
printf '\n'

echo "==> import (2a vez, deve ser idempotente: skipped)"
authed -fsS -X POST "$base_url/api/chats/import" -F "file=@$sample"
printf '\n'

echo "==> listagem"
authed -fsS "$base_url/api/chats?size=100"
printf '\n'

echo "==> sync: cliente sem token recebe fullResyncRequired"
authed -fsS "$base_url/api/sync?since=0&limit=5"
printf '\n'

echo "==> sync: snapshot entrega o estado e um token utilizavel"
snapshot="$(authed -fsS "$base_url/api/sync/snapshot?size=100")"
# nomes propios: $token acima e o JWT do usuario
sync_token="$(printf '%s' "$snapshot" | sed -n 's/.*"syncToken":\([0-9]*\).*/\1/p')"
sync_total="$(printf '%s' "$snapshot" | sed -n 's/.*"total":\([0-9]*\).*/\1/p')"
printf '    total=%s syncToken=%s\n' "$sync_total" "$sync_token"

if [ "$sync_token" -le 0 ] 2>/dev/null; then
    echo "erro: snapshot devolveu syncToken 0 (cliente ficaria preso pedindo snapshot)" >&2
    exit 1
fi

echo "==> sync: com esse token o delta vem vazio e sem pedir full resync"
authed -fsS "$base_url/api/sync?since=$sync_token"
printf '\n'

echo "==> ack vazio"
authed -fsS -X POST "$base_url/api/sync/ack" \
    -H 'Content-Type: application/json' -d "{\"ackVersion\":$sync_token,\"localChanges\":[]}"
printf '\n'

echo "smoke ok"
