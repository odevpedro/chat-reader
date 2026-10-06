#!/usr/bin/env sh
# Gera o pacote do plugin para instalar no Kindle.
#
#   ./kindle/scripts/package.sh                 # chatreader.koplugin no raiz do repo
#   ./kindle/scripts/package.sh /tmp/saida.zip
#
# Por que o passo extra: o pluginloader do KOReader so acha diretorios que terminam
# em `.koplugin`. O repo guarda o codigo em `kindle/` porque e o nome logico do
# componente, mas o que vai para o aparelho tem que se chamar `chatreader.koplugin`.
# Escrever essa transformacao em um passo unico evita aClassicica de "instalei, o
# menu nao mudou, apaguei o plugin e instalei de novo".
set -eu

root="$(cd "$(dirname "$0")/../.." && pwd)"
out="${1:-$root/chatreader.koplugin}"

staging="$(mktemp -d)"
trap 'rm -rf "$staging"' EXIT

plugin="$staging/chatreader.koplugin"

# O que entra: codigo do plugin + o que o KOReader precisa para carregar. `spec/`,
# `scripts/` e o Dockerfile nao vao para o aparelho — testes nao rodam la, e o
# `scripts/json.lua` e copia de trabalho do container.
mkdir -p "$plugin"
cp "$root/kindle/main.lua"        "$plugin/"
cp "$root/kindle/metadata.lua"    "$plugin/"
cp -R "$root/kindle/client"       "$plugin/"
cp -R "$root/kindle/store"        "$plugin/"
cp -R "$root/kindle/views"        "$plugin/"

# O banco e' criado em DataStorage:getDataDir(), que ja existe no KOReader — nao ha
# schema para embutir aqui. O que o store precisa e do ljsqlite3, que ja vem na
# propria imagem do KOReader.

# A ordem importa: `rm -f` em diretorio falha e, com `set -eu`, matava o script antes
# do ramo de diretorio — quem pre-criava a pasta de saida nunca chegava nele.
if [ -d "$out" ]; then
    # saida e um diretorio: e um pacote extraido, util para conferir o conteudo.
    rm -rf "$out"
    cp -R "$plugin" "$out"
    echo "pasta pronta em $out"
else
    rm -f "$out"
    (cd "$staging" && zip -q -r "$out" chatreader.koplugin)
    echo "pacote pronto em $out"
fi

# O ponto que mais vezes quebra: um `require("views.lerra")` em main.lua passa em
# todo spec (que roda do repo, onde views/ existe) e so vira tela branca no aparelho,
# se o pacote tiver sido montado errado. Aqui os requires do main.lua sao conferidos
# contra o conteudo do pacote ja montado.
#
# So os modulos do proprio plugin sao conferidos. `datastorage`, `luasettings`,
# `rapidjson` e `ui.*` vem do KOReader e nao tem como estar no pacote — cobrar a
# presenca deles aqui daria falso negativo.
echo "conferindo os requires do main.lua contra o conteudo do pacote:"
missing=0
for mod in $(sed -n 's/.*require("\([a-z_.]*\)".*/\1/p' "$plugin/main.lua" | sort -u); do
    # Modulos do KOReader: skip.
    case "$mod" in
        datastorage|luasettings|rapidjson|ui|ui.*) continue ;;
    esac
    path="$plugin/$(echo "$mod" | tr '.' '/').lua"
    if [ -f "$path" ]; then
        echo "  ok  $mod"
    else
        echo "  FALTA  $mod (esperado $path)"
        missing=$((missing + 1))
    fi
done
if [ "$missing" -ne 0 ]; then
    echo "$missing modulo(s) do plugin faltando — o plugin nao carregaria no aparelho." >&2
    exit 1
fi

# O teste definitivo: carregar o main.lua de dentro do pacote, com o package.path
# apontando para ele e nao para o repo. Um `require` quebrado passa em todo spec
# (que roda do repo) e so vira tela branca no aparelho — aqui o repo esta fora do
# caminho de busca, entao o erro aparece igual ao do aparelho.
echo
echo "carregando o pacote recem-empacotado (fora do repo):"
check_dir="$staging/check"
if [ -d "$out" ]; then check_dir="$out"; else check_dir="$staging/chatreader.koplugin"; fi
container_probe() {
    docker run --rm --entrypoint luajit -v "$1:/p" -w /p "$image" -e '
        package.preload["datastorage"] = function() return {
            getSettingsDir = function() return "/tmp" end,
            getDataDir = function() return "/tmp" end } end
        package.preload["luasettings"] = function() return { open = function()
            return { readSetting = function(_, _, d) return d end,
                     saveSetting = function() end, flush = function() end } end } end
        local function klass()
            local c = {}; c.__index = c
            function c:new(f) local o = setmetatable({}, self)
                if f then for k, v in pairs(f) do o[k] = v end end
                o._class = self; return o end
            function c:extend(d) local ch = setmetatable({}, c)
                for k, v in pairs(d or {}) do ch[k] = v end
                ch.__index = ch; return ch end
            return c
        end
        local UIM = klass(); UIM.show = function() end; UIM.close = function() end
        package.preload["ui/uimanager"] = function() return UIM end
        for _, m in ipairs({ "ui/widget/infomessage", "ui/widget/buttondialog",
            "ui/widget/confirmbox", "ui/widget/textviewer", "ui/widget/menu",
            "ui/widget/inputdialog", "ui/widget/container/widgetcontainer",
            "ui/network/manager" }) do
            package.preload[m] = function() return klass() end
        end
        local ok, err = pcall(require, "main")
        if not ok then print("  ERRO: " .. tostring(err)); os.exit(1) end
        print("  ok  main.lua carrega, classe " .. require("main").name)
    ' 2>/dev/null
}
if command -v docker >/dev/null 2>&1; then
    image="${LUA_TEST_IMAGE:-chatreader-lua-spec:1.0-1}"
    if docker image inspect "$image" >/dev/null 2>&1; then
        container_probe "$check_dir" || {
            echo "o pacote nao carrega fora do repo — nao instale assim." >&2
            exit 1
        }
    else
        echo "  (pulado: imagem $image nao existe; rode ./kindle/spec.sh uma vez)"
    fi
else
    echo "  (pulado: docker nao encontrado)"
fi

echo
echo "Instalar: copie 'chatreader.koplugin' para koreader/plugins/ no aparelho,"
echo "depois reinicie o KOReader e abra Menu > Ferramentas > Plugins > Chat Reader."