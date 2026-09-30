#!/usr/bin/env sh
# Contrato ponta a ponta: o plugin Lua contra o backend REAL.
#
#   ./kindle/scripts/contract.sh                                # backend descartavel
#   ./kindle/scripts/contract.sh USUARIO SENHA [http://host:porta]
#
# Com usuario e senha, usa o backend que voce indicar (padrao :8080, o seu compose).
# Sem argumentos, sobe um backend DESCARTAVEL na porta 8082, apontando para o seu
# Postgres, com usuario e senha gerados aqui. Motivo: o contrato precisa de login em
# texto claro e o .env guarda so o hash; pedir a sua senha de verdade seria um Obstaculo
# para rodar o teste. Seu stack em :8080 nao e tocado.
#
# Sai 0 com "contrato ok".
set -eu

root="$(cd "$(dirname "$0")/../.." && pwd)"
image="${LUA_TEST_IMAGE:-chatreader-lua-spec:1.0-1}"

docker build -q -f "$root/kindle/Dockerfile.spec" -t "$image" "$root" >/dev/null 2>&1

# roda o cliente Lua num container efemero com o plugin montado
run_client() {
    docker run --rm \
        --network host \
        -v "$root/kindle:/plugin" \
        -w /plugin \
        --entrypoint "" \
        "$image" \
        luajit /plugin/scripts/contract_test.lua "$@"
}

if [ "$#" -ge 2 ]; then
    exec run_client "$@"
fi

# ---- modo descartavel ---------------------------------------------------------
ROOT="$root"
export ROOT
. "$root/kindle/scripts/disposable_backend.sh"
container="chatreader-contract"
port="${CONTRACT_PORT:-8082}"
base="http://127.0.0.1:${port}"
user="contrato"
password="contrato-descartavel-5b1e"

cleanup() { disposable_rm "$container"; }
trap cleanup EXIT INT TERM

echo "== backend descartavel em ${base} (o seu :8080 nao e tocado)"
disposable_start "$container" "$port" "$user" "$password"

run_client "$user" "$password" "$base"