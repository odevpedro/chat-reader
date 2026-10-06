# Conectar no Kindle via SSH (guia rápido)

Acesso de shell ao Kindle para instalar/debugar o plugin sem passar pelo navegador.

> **Em um minuto:**
>
> ```sh
> sshpass -p '<senha>' ssh -p 2222 root@<ip-do-kindle>
> ```
>
> (sem `sshpass`, ver [senha](#senha) abaixo)

Os valores deste aparelho — IP, MAC, senha — ficam fora do repositório, em
`docs/conectar-ssh.local.md` (ignorado pelo git). Troque `<ip-do-kindle>` e `<senha>`
pelos de lá.

## O que é o SSH do Kindle

Não é o SSH do sistema da Amazon: é o **servidor SSH embutido do KOReader**
(dropbear), que só existe enquanto o KOReader está aberto e você o liga no menu.
Duas consequências:

- a porta é a **2222** (padrão do plugin `SSH.koplugin`), não 22;
- depois de reiniciar o aparelho ou fechar o KOReader, **o servidor volta a ficar
  desligado** — é preciso religar (ou marcar o autostart, ver abaixo).

## Ligar o SSH no Kindle

1. Toque na borda superior da tela para abrir o menu;
2. **Configurações (engrenagem) → Rede → Servidor SSH**;
3. Marque **Login without password**;
4. Marque **SSH server** — um pop-up mostra o IP e a porta.

Para não repetir isso toda vez: em **Servidor SSH → Start SSH server with KOReader**
(deixe marcado). O servidor sobe sozinho toda vez que o KOReader abre.

## Dados da conexão

| Campo | Valor |
|---|---|
| Host | `<ip-do-kindle>` (muda se o roteador atribuir outro — ver [descobrir o IP](#descobrir-o-ip)) |
| Porta | `2222` |
| Usuário | `root` |
| Senha | `<senha>` (qualquer uma serve com **Login without password**) |

### Senha

Com **Login without password** marcado, o dropbear aceita qualquer senha — a que
está anotada no arquivo local é a que se usa por hábito. Sem `sshpass`, digite na
hora:

```sh
ssh -p 2222 root@<ip-do-kindle>
```

Ou use a variável de sessão, que não deixa a senha no histórico:

```sh
SSH_ASKPASS_REQUIRE=force SSH_ASKPASS=/caminho/para/ask.sh setsid ssh -p 2222 root@<ip-do-kindle>
# ask.sh:  #!/bin/sh
#           echo '<senha>'
```

## Descobrir o IP

O IP do Kindle muda (DHCP). Duas formas:

```sh
# 1. varredura na porta do SSH (a que funciona de verdade)
nmap -p 2222 --open 192.168.1.0/24

# 2. varredura por endereço MAC — estável mesmo com IP novo
#    MAC do aparelho: <mac-do-kindle>   (ver arquivo local)
ip neigh show | grep -i "<mac-do-kindle>"
```

Se nada aparecer, o Kindle está com a tela dormindo (Wi-Fi desliga junto) — acorde
o aparelho e ligue o servidor SSH de novo.

## Comandos úteis

```sh
# instalar/atualizar o plugin (roda no COMPUTADOR)
./kindle/scripts/package.sh
scp -P 2222 -r chatreader.koplugin root@<ip-do-kindle>:/mnt/us/koreader/plugins/

# conferir se está instalado e com a versão certa
ssh -p 2222 root@<ip-do-kindle> 'ls /mnt/us/koreader/plugins/chatreader.koplugin'

# estado: banco, configuração e erros do KOReader
ssh -p 2222 root@<ip-do-kindle> '
  ls -la /mnt/us/koreader/data/chatreader* 2>/dev/null || echo "banco nao existe";
  cat /mnt/us/koreader/settings/chatreader.lua 2>/dev/null;
  tail -30 /mnt/us/koreader/temp/koreader.log 2>/dev/null'
```

Caminhos no aparelho:

| O quê | Onde |
|---|---|
| Plugin | `/mnt/us/koreader/plugins/chatreader.koplugin/` |
| Banco SQLite | `/mnt/us/koreader/data/chatreader.sqlite3` |
| Config (base_url, token) | `/mnt/us/koreader/settings/chatreader.lua` |
| Log do KOReader | `/mnt/us/koreader/temp/koreader.log` (e `/mnt/us/koreader/crash.log`) |

Depois de copiar o plugin, **feche o KOReader e abra de novo** — plugin novo só é
lido na partida seguinte.

> O `scp` precisa que `chatreader.koplugin` seja uma **pasta**. `./kindle/scripts/package.sh`
> gera um `.zip` com esse nome; descompacte-o em `chatreader.koplugin/` antes de copiar,
> senão o pluginloader não lê.

## O aparelho

- Kindle com jailbreak e KOReader **v2026.03**;
- IP, MAC, firmware exato e IP do computador do servidor: `docs/conectar-ssh.local.md`.

## Problemas comuns

| Sintoma | Causa |
|---|---|
| `Connection timed out` | Kindle com tela dormindo, ou SSH desligado no KOReader |
| `Connection refused` na porta 22 | porta errada — use **2222** |
| `Permission denied` | você ligou **Login with key only** (use *Login without password*) |
| IP não bate mais | refaça a [varredura](#descobrir-o-ip) — DHCP mudou |
