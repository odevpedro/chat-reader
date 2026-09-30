#!/usr/bin/env sh
# Executa a suite de testes Lua (busted) no container.
#
#   ./kindle/spec.sh                          # tudo
#   ./kindle/spec.sh spec/sync_spec.lua       # um arquivo
#
# Nao precisa de Lua na maquina: a imagem reproduz o par LuaJIT + ljsqlite3 que o
# KOReader embarca (doc/DataStore.md), entao o mesmo codigo roda aqui e no Kindle.
set -eu

root="$(cd "$(dirname "$0")/.." && pwd)"
image="${LUA_TEST_IMAGE:-chatreader-lua-spec:1.0-1}"

docker build -q -f "$root/kindle/Dockerfile.spec" -t "$image" "$root" >/dev/null 2>&1

# Sem argumento, o container roda um busted por arquivo de spec: o busted isola cada
# arquivo (envmode = "insulate"), o que faria o ljsqlite3 — modulo com FFI — ser
# carregado de novo e quebrar. Com argumentos, delega ao busted.
exec docker run --rm \
    -v "$root/kindle:/plugin" \
    -w /plugin \
    "$image" "$@"
