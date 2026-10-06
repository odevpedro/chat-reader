#!/usr/bin/env sh
# Importa o export do ChatGPT sem mexer em nada do seu arquivo.
#
# O export vem em um .zip com conversations.json e, em contas grandes, o arquivo
# vem dividido (conversations-001.json, conversations-002.json...). Este script
# descompacta numa pasta temporaria, junta as partes num unico conversations.json e
# delega para o import.sh. Nada do seu arquivo e' tocado.
#
#   ./scripts/import-export.sh ~/Downloads/chatgpt-export-2026-10-06.zip
#   ./scripts/import-export.sh ~/Downloads/           # pega o .zip mais recente
#   ./scripts/import-export.sh conversations.json     # arquivo ja descompactado
#   BASE_URL=... TOKEN=... ./scripts/import-export.sh .../exporte.zip
#
# Modo vigia: importa sozinho quando um export novo aparecer na pasta (o .zip mais
# recente que ainda nao foi importado). Melhor companheiro do "baixe e solte aqui".
#
#   ./scripts/import-export.sh --watch ~/Downloads
set -eu

script_dir="$(cd "$(dirname "$0")" && pwd)"

watch=0
target="${1:-.}"
if [ "$1" = "--watch" ]; then
    watch=1
    target="${2:-.}"
fi

# Marcador do que ja foi importado no modo vigia, ao lado do alvo.
stamp="$target/.chatreader-importado"

newest_zip() {
    find "$target" -maxdepth 1 -name 'chatgpt-export-*.zip' -o -maxdepth 1 -name '*.zip' 2>/dev/null \
        | while read -r f; do echo "$(stat -c '%Y' "$f") $f"; done \
        | sort -nr | head -1 | cut -d' ' -f2-
}

pick_export() {
    # Devolve o caminho do export a importar: zip mais novo da pasta, ou o proprio
    # arquivo quando o alvo ja e' um .zip / conversations.json.
    if [ -f "$target" ]; then
        echo "$target"
        return
    fi
    if [ -d "$target" ]; then
        local zip; zip="$(newest_zip)"
        if [ -n "$zip" ]; then echo "$zip"; return; fi
        local json; json="$(ls "$target"/conversations*.json 2>/dev/null | head -1 || true)"
        if [ -n "$json" ]; then echo "$json"; return; fi
    fi
    return 1
}

import_once() {
    export_exe="$1"

    if [ -z "$export_exe" ]; then
        echo "-> nenhum export encontrado em $target" >&2
        return 1
    fi

    case "$export_exe" in
        *.zip)
            tmp="$(mktemp -d)"
            trap 'rm -rf "$tmp"' EXIT
            unzip -q -o "$export_exe" -d "$tmp"

            # Nome do arquivo de conversas: conversations.json, ou as partes
            # conversations-001.json ... quando a conta e' grande.
            parts="$(ls "$tmp"/conversations*.json 2>/dev/null || true)"
            if [ -z "$parts" ]; then
                echo "erro: nennhum conversations*.json dentro de $(basename "$export_exe")" >&2
                exit 1
            fi

            count="$(echo "$parts" | wc -l)"
            if [ "$count" -eq 1 ]; then
                file="$parts"
            else
                echo "-> juntando $count partes do export..."
                file="$tmp/conversations.merged.json"
                python3 - "$parts" "$file" <<'EOF'
import json, sys
out = []
for path in sys.argv[1].splitlines():
    data = json.load(open(path, encoding="utf-8"))
    if isinstance(data, list):
        out.extend(data)
    else:
        print("aviso: ignorando %s (nao e' um array de conversas)" % path, file=sys.stderr)
json.dump(out, open(sys.argv[2], "w", encoding="utf-8"), ensure_ascii=False)
EOF
            fi
            ;;
        *)
            file="$export_exe"
            ;;
    esac

    echo "-> importando $file"
    BASE_URL="${BASE_URL:-http://localhost:8080}" TOKEN="${TOKEN:-}" "$script_dir/import.sh" "$file"
    echo "(o export continua intacto em $export_exe — nada foi apagado)"
    return 0
}

if [ "$watch" -eq 1 ]; then
    if [ ! -d "$target" ]; then
        echo "erro: --watch precisa de uma pasta: $target" >&2
        exit 1
    fi
    echo "-> vigiando $target (Ctrl-C para parar; novo export e' importado sozinho)"
    while true; do
        zip="$(newest_zip)"
        if [ -n "$zip" ] && { [ ! -f "$stamp" ] || [ "$(stat -c '%Y' "$zip")" -gt "$(stat -c '%Y' "$stamp")" ]; }; then
            echo "[$(date '+%H:%M:%S')] export novo: $(basename "$zip")"
            if import_once "$zip"; then
                touch "$stamp"
            fi
        else
            echo "aguardando export novo..."
        fi
        sleep 30
    done
fi

import_once "$(pick_export)"