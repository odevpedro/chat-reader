#!/usr/bin/env sh
# Importa um arquivo de conversas para um backend rodando. O formato e detectado pelo
# backend (JSON genérico, Markdown ou export do ChatGPT) ou forcado com FORMAT=...
#
#   ./scripts/import.sh sample-data/chats.json
#   ./scripts/import.sh sample-data/chats.md
#   FORMAT=CHATGPT_EXPORT ./scripts/import.sh ~/Downloads/conversations.json
#   BASE_URL=http://localhost:8080 TOKEN=... ./scripts/import.sh arquivo.json
#
# Se AUTH estiver ligada e TOKEN nao for informado, faz login usando
# CHAT_READER_USERNAME / CHAT_READER_PASSWORD do ambiente.
set -eu

file="${1:-sample-data/chats.json}"
base_url="${BASE_URL:-http://localhost:8080}"

if [ ! -f "$file" ]; then
    echo "erro: arquivo nao encontrado: $file" >&2
    exit 1
fi

if [ -z "${TOKEN:-}" ] && [ -n "${CHAT_READER_USERNAME:-}" ] && [ -n "${CHAT_READER_PASSWORD:-}" ]; then
    TOKEN="$(curl -fsS -X POST "$base_url/api/auth/login" \
        -H 'Content-Type: application/json' \
        -d "{\"username\":\"$CHAT_READER_USERNAME\",\"password\":\"$CHAT_READER_PASSWORD\"}" \
        | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')"
fi

set -- -fsS -X POST "$base_url/api/chats/import"
if [ -n "${TOKEN:-}" ]; then
    set -- "$@" -H "Authorization: Bearer $TOKEN"
fi
if [ -n "${FORMAT:-}" ]; then
    set -- "$@" -F "format=$FORMAT"
fi

echo "-> importando $file em $base_url${FORMAT:+ (format=$FORMAT)}"
curl "$@" -F "file=@$file"
printf '\n'
