#!/usr/bin/env sh
# Gera o hash BCrypt de uma senha, no formato aceito pelo backend
# (spring-security-crypto). Use o resultado em CHAT_READER_PASSWORD_HASH.
#
#   ./scripts/hash-password.sh 'minha-senha'
#
# A senha NUNCA entra no historico do shell nem em arquivos: passe como argumento
# ou seja solicitado de forma interativa.
set -eu

if [ "$#" -ge 1 ]; then
    password="$1"
else
    printf 'Senha: ' >&2
    stty -echo 2>/dev/null || true
    read -r password
    stty echo 2>/dev/null || true
    printf '\n' >&2
fi

if [ -z "$password" ]; then
    echo "erro: senha vazia" >&2
    exit 1
fi

if command -v htpasswd >/dev/null 2>&1; then
    hash="$(htpasswd -bnBC 10 "" "$password" | tr -d ':\n')"
elif command -v docker >/dev/null 2>&1; then
    hash="$(docker run --rm httpd:alpine htpasswd -bnBC 10 "" "$password" | tr -d ':\n')"
else
    echo "erro: instale apache2-utils (htpasswd) ou use Docker" >&2
    exit 1
fi

printf '%s\n' "$hash"
# O Compose interpola "$" nos valores do .env; aspas simples preservam o hash.
printf '\nno .env, use aspas simples para nao corromper os "$":\n' >&2
printf "CHAT_READER_PASSWORD_HASH='%s'\n" "$hash" >&2
