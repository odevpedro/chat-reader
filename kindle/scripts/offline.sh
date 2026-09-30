#!/usr/bin/env sh
# Etapa 6 — OFFLINE. Sobe um backend descartavel, sincroniza, DERRUBA o backend e
# prova que a biblioteca continua inteira, depois volta o backend e drena a fila.
#
#   ./kindle/scripts/offline.sh
#
# Por que um container descartavel: o teste precisa derrubar o backend de verdade
# (connection refused), e o seu stack em :8080 nao deve ser interrompido. Este script
# cria o seu proprio backend na porta 8081, apontando para o mesmo Postgres, com
# credenciais temporarias — o seu .env nao e tocado.
#
# Sai 0 com "offline ok" nas tres fases; qualquer assercao quebrada sai != 0.
set -eu

root="$(cd "$(dirname "$0")/../.." && pwd)"
image="${LUA_TEST_IMAGE:-chatreader-lua-spec:1.0-1}"
container="chatreader-offline"
port="${OFFLINE_PORT:-8081}"
base="http://127.0.0.1:${port}"

user="offline"
password="offline-descartavel-3f7c"

# O SQLite e o estado entre as fases vivem num diretorio temporario montado em /data,
# entao cada fase roda num container novo com o mesmo banco em disco.
workdir="$(mktemp -d)"
db_name="kindle.db"

lua() {
    docker run --rm \
        --network host \
        -v "$root/kindle:/plugin" \
        -v "$workdir":/data \
        -w /plugin \
        --entrypoint "" \
        "$image" \
        luajit "/plugin/scripts/offline_test.lua" "$@"
}

ROOT="$root"
export ROOT
. "$root/kindle/scripts/disposable_backend.sh"

cleanup() { disposable_rm "$container"; }
trap cleanup EXIT INT TERM

echo "== preparando imagem de teste"
docker build -q -f "$root/kindle/Dockerfile.spec" -t "$image" "$root" >/dev/null 2>&1

echo "== fase 1: online (sync real)"
disposable_start "$container" "$port" "$user" "$password"
lua online "/data/$db_name" "$user" "$password" "$base"

echo
echo "== fase 2: DERRUBANDO o backend"
disposable_stop "$container"
disposable_assert_down "$port"
lua offline "/data/$db_name" "" "" "$base"

echo
echo "== fase 3: o backend voltou e a fila sobe"
disposable_start "$container" "$port" "$user" "$password"
lua drenar "/data/$db_name" "$user" "$password" "$base"

echo
echo "offline ok"
echo "(banco de teste em $workdir, pode apagar)"