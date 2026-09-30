# Imagem de teste para o plugin Lua (fora do Kindle).
#
# O KOReader embarca LuaJIT 2.1 e o binding ljsqlite3; aqui reproduzimos o mesmo par
# (LuaJIT do Debian + o proprio ljsqlite3 usado pelo KOReader) para que os testes de
# busted usem o mesmo interpretador e o mesmo binding de SQLite do dispositivo.
# Nao e imagem de producao: nada disso vai para o Kindle, que traz o proprio runtime.
FROM debian:bookworm-slim

ARG BUSTED_VERSION=2.2.0

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        build-essential ca-certificates curl git libsqlite3-0 luajit \
        libluajit-5.1-dev luarocks \
    && rm -rf /var/lib/apt/lists/*

ENV LUA_VERSION=5.1

# busted (runner de teste) — o mesmo que o proprio KOReader usa nos specs dele
RUN git clone --depth 1 --branch v${BUSTED_VERSION} https://github.com/lunarmodules/busted.git /opt/busted \
    && cd /opt/busted \
    && luarocks --lua-version=${LUA_VERSION} make \
    && rm -rf /opt/busted

# ljsqlite3: mesmo binding do KOReader (doc/DataStore.md, thirdparty da koreader-base).
# E modulo puro de LuaJIT (o sqlite entra por ffi.load("sqlite3"), por isso o symlink
# libsqlite3.so abaixo), mas depende do xsys.
RUN ln -s /usr/lib/x86_64-linux-gnu/libsqlite3.so.0 /usr/local/lib/libsqlite3.so \
    && ldconfig \
    && luarocks --lua-version=${LUA_VERSION} install xsys \
    && git clone --depth 1 https://github.com/stepelu/lua-ljsqlite3.git /tmp/ljsqlite3 \
    && mkdir -p /usr/local/share/lua/${LUA_VERSION}/lua-ljsqlite3 \
    && cp /tmp/ljsqlite3/init.lua /tmp/ljsqlite3/__init.lua \
        /usr/local/share/lua/${LUA_VERSION}/lua-ljsqlite3/ \
    && rm -rf /tmp/ljsqlite3

# busted precisa de FFI (o ljsqlite3 e modulo de LuaJIT), entao roda com luajit e
# nao com o lua5.1 do Debian. O caminho do script usa glob porque a versao do rock
# (scm-1) muda com o tempo.
#
# Sem argumentos, roda UM busted por arquivo de spec. O busted registra cada arquivo
# com envmode = "insulate" (busted/init.lua), o que restaura package.loaded e _G a
# cada arquivo: modulo com FFI, como o ljsqlite3, e executado de novo no arquivo
# seguinte e quebra no segundo ffi.cdef. Como nao ha opcao para desligar isso,
# processo separado por arquivo e a forma honesta de isolar. Com argumentos, delega
# ao busted (para rodar um unico arquivo).
WORKDIR /plugin
ENTRYPOINT ["sh", "-c", "set -e; b=$(echo /usr/local/lib/luarocks/rocks-*/busted/*/bin/busted); if [ $# -eq 0 ]; then for f in spec/*_spec.lua; do echo \"== $f\"; luajit $b --output=utfTerminal \"$f\"; done; else luajit $b --output=utfTerminal \"$@\"; fi", "--"]
