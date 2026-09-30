#!/usr/bin/env sh
# Backend descartavel para os testes que precisam de um servidor de verdade (credencial
# em texto claro, rede derrubada) sem mexer no seu stack nem pedir a senha do .env.
#
#   . disposable_backend.sh iniciar <container> <porta> <usuario> <senha>
#   . disposable_backend.sh parar     <container>
#   . disposable_backend.sh parar_tudo <container>   # derruba, se existir
#
# O .env e' lido para as variaveis de infra (banco, perfil), mas CHAT_READER_USERNAME,
# CHAT_READER_PASSWORD_HASH e JWT_SECRET sao sobrescritos: a senha e' a deste script.
# O hash vem de scripts/hash-password.sh, o mesmo caminho do usuario, para nao poder
# divergir da senha.
#
# Nao levanta o Postgres: reaproveita o do seu compose, sobe o backend em outra porta.
set -eu

# O chamador exporta ROOT (raiz do repositorio). Nao recalculamos aqui: este arquivo e'
# sourced por dash, que nao tem BASH_SOURCE, e o $0 seria o do script chamador.
: "${ROOT:?exporte ROOT antes de usar disposable_backend.sh}"

disposable_start() {
    _container="$1"; _port="$2"; _user="$3"; _password="$4"
    _root="$ROOT"
    _image="${BACKEND_IMAGE:-chat-reader-backend:latest}"
    _hash="$("$_root/scripts/hash-password.sh" "$_password" 2>/dev/null | head -1)"

    docker rm -f "$_container" >/dev/null 2>&1 || true
    docker run -d --name "$_container" \
        --network "${COMPOSE_NETWORK:-chat-reader_default}" \
        -p "${_port}:8080" \
        --env-file "$_root/.env" \
        -e POSTGRES_HOST="${POSTGRES_HOST_ON_NETWORK:-postgres}" \
        -e POSTGRES_PORT=5432 \
        -e SERVER_PORT=8080 \
        -e CHAT_READER_USERNAME="$_user" \
        -e CHAT_READER_PASSWORD_HASH="$_hash" \
        -e JWT_SECRET="${DISPOSABLE_JWT_SECRET:-descartavel-nao-usar-em-producao-0123456789}" \
        "$_image" >/dev/null

    _i=0
    while [ "$_i" -lt 45 ]; do
        if [ "$(curl -s -o /dev/null -w '%{http_code}' \
                 "http://127.0.0.1:${_port}/actuator/health" || true)" = "200" ]; then
            return 0
        fi
        _i=$((_i + 1))
        sleep 2
    done
    echo "ERRO: o backend descartavel nao ficou saudavel em 127.0.0.1:${_port}" >&2
    return 1
}

# Derruba de verdade: e' isto que a fase offline precisa para ter "connection refused".
disposable_stop() {
    docker stop -t 5 "$1" >/dev/null 2>&1 || true
}

disposable_rm() {
    docker rm -f "$1" >/dev/null 2>&1 || true
}

# Falha se a porta ainda responder: sem isto, um "offline" que devolveu 401 mentiria.
disposable_assert_down() {
    _port="$1"
    sleep 1
    if curl -s -o /dev/null -m 3 "http://127.0.0.1:${_port}/actuator/health" 2>/dev/null; then
        echo "ERRO: o backend ainda responde; o teste offline seria mentira" >&2
        return 1
    fi
    echo "   porta ${_port} fechada (connection refused)"
}
