# Instalar no Kindle

Guia para colocar o Chat Reader no KOReader de verdade. Foi escrito para ser seguido
na ordem, sem SSH.

> **Estado honesto:** o plugin passou por 131 testes no container, incluindo testes que
> executam as telas com stubs do KOReader e conferem os construtores contra a fonte do
> upstream. Isso pega erro de sintaxe, `require` quebrado, campo errado em widget e
> callback que estoura. **Não** pega o que só o aparelho mostra: contraste, leitura de
> linha, tempo de montagem em e-ink, gesto de virar página. Se algo aqui não funcionar,
> o primeiro lugar para olhar é o **Diagnóstico**.

## 1. Ter o KOReader no Kindle

O Chat Reader é um plugin: ele **não** funciona no firmware original da Amazon, porque
o firmware não tem o KOReader. Se você jailbreakou e instalou o KOReader, tudo bem.

Confirme que o KOReader abre e você consegue ler um livro. Se ainda não tem, pare
aqui — o resto deste guia não vai funcionar.

## 2. Descobrir o endereço IP do Kindle

Na interface do próprio KOReader:

**Menu → Ferramentas → Mochila do dispositivo → Mostrar rede (WLAN)** (em algumas
versões: *Gerenciar redes →SSID (endereço IP)*).

Anote o IP. É para ele que o plugin vai falar, e é assim que você confere que o
servidor está alcançável a partir do Kindle.

## 3. Ligar o servidor

No computador onde está o projeto:

```sh
docker compose up -d
```

O servidor escuta na porta 8080. Descubra o IP do **seu computador** na rede local:

- Linux: `ip addr | grep "inet "` (ignore a linha `127.0.0.1`);
- macOS: `ipconfig getifaddr en0`.

Guarde esse IP. O endereço do servidor é `http://<esse-IP>:8080` — é este que
você digita no Kindle no passo 9.

O `docker-compose.yml` publica a porta em `0.0.0.0:8080`, ou seja, **qualquer máquina
na rede alcança o servidor**, não só o Kindle. Isso é o padrão do Docker.

Duas coisas precisam ser verdade para o Kindle alcançar:

1. **mesma rede Wi-Fi** — não funciona em celular, VPN ou rede de visitante;
2. **o firewall do seu computador não barra a porta 8080**, ou o teste dá timeout em vez
   de recusa.

Sobre o firewall: se o seu não está ativo, não faça nada — a exposição fica restrita à
sua rede doméstica, que é aceitável. Se está ativo, libere a faixa da sua rede:

```sh
# a ordem importa: libere antes de negar, senão o backend sai do ar
sudo ufw allow from 192.168.1.0/24 to any port 8080 proto tcp   # <- a faixa do SEU Wi-Fi
sudo ufw deny 8080/tcp
sudo ufw status numbered   # confira: 8080 liberado só para essa faixa
```

Para desfazer: `sudo ufw delete deny 8080/tcp`.

## 4. Definir usuário e senha

O `.env` do seu projeto já tem `CHAT_READER_USERNAME` e um hash de senha, e o backend já
está no ar. **Se você já sabe a senha em texto** que corresponde a esse hash, pule
para o passo 5.

Se ainda não tem senha definida, crie uma:

```sh
./scripts/hash-password.sh
```

O script pergunta a senha sem eco e devolve uma linha como
`CHAT_READER_PASSWORD_HASH='$2a$10$...'`. Abra o `.env` e troque o valor de
`CHAT_READER_PASSWORD_HASH` por essa linha (sem o prefixo da variável, e sem as
aspas simples se o seu editor atrapalhar). Depois:

```sh
docker compose up -d          # recria o container com o hash novo
curl -s http://localhost:8080/actuator/health
```

**Anote a senha em texto num lugar seguro.** Você vai precisar dela no passo 5 e no
passo 9, e não tem como recuperá-la a partir do hash. Esta é a parte realmente
importante deste passo.

Se o health check não responder, o problema está aqui — ainda não é do Kindle.

## 5. Importar conversas

Pelo menos uma, para o plugin ter o que mostrar:

```sh
CHAT_READER_USERNAME=reader CHAT_READER_PASSWORD='a-senha-do-passo-4' \
  ./scripts/import.sh sample-data/chats.json
```

**Por que a senha aparece aqui.** O `import.sh` fala com a API, e a API exige token. Ele
consegue um token fazendo login com `CHAT_READER_USERNAME` e `CHAT_READER_PASSWORD` do
ambiente — a senha em **texto**. O `.env` guarda só o *hash* BCrypt, que serve para
verificar a senha mas não para autenticá-la, então o import sozinho dá 401.

Consequência prática: a senha em texto que você escolher no passo 4 é a mesma que vai
para o Kindle no passo 9. Se você ainda não tem senha definida, crie uma agora — o
script abaixo pergunta e não deixa a senha no histórico do shell:

```sh
./scripts/hash-password.sh
```

Anote a senha em texto num lugar seguro (gerenciador de senhas). Você vai precisar
dela no passo 9 e não tem como recuperá-la do hash.

## 6. Gerar o pacote

O pluginloader só lê **diretórios** terminados em `.koplugin` (`pluginloader.lua`
filtra `mode == "directory"`); um `.zip` com esse nome é silenciosamente ignorado.
Então o pacote de instalação é uma pasta:

```sh
mkdir -p chatreader.koplugin
./kindle/scripts/package.sh chatreader.koplugin
```

Isso cria `chatreader.koplugin/` na raiz do repo. O `./kindle/scripts/package.sh`
sem argumento gera um `.zip` com esse nome — útil só para conferir/conteúdo, **não**
para instalar. No fim o script imprime a verificação:

```
conferindo os requires do main.lua contra o conteudo do pacote:
  ok  client.http
  ...
carregando o pacote recem-empacotado (fora do repo):
  ok  main.lua carrega, classe chatreader
```

Se aparecer `FALTA` ou `ERRO`, **não copie para o aparelho** — o plugin não vai
carregar. O problema está na lista.

## 7. Copiar para o Kindle

**Via SSH** (a pasta inteira, preservando o modo diretório):

```sh
scp -P 2222 -r chatreader.koplugin root@<ip-do-kindle>:/mnt/us/koreader/plugins/
```

O servidor SSH está embutido no KOReader (veja [docs/conectar-ssh.md](conectar-ssh.md)):
ligue em **Configurações → Rede → Servidor SSH**. Sem ele, copie o `.zip` para o
aparelho e descompacte lá em `koreader/plugins/chatreader.koplugin/`.

O nome tem que terminar em `.koplugin`. Sem o sufixo, o pluginloader ignora a pasta e
o menu simplesmente não muda — é a causa número um de "instalei e nada aconteceu".

## 8. Reiniciar e abrir

Feche o KOReader de vez (no menu, *Sair*) e abra de novo. Plugin novo só é lido na
partida seguinte.

**Menu → Ferramentas → Plugins → Chat Reader**

## 9. Configurar de dentro do Kindle

O menu **Configurações** do plugin pede, nesta ordem:

1. **Endereço** — `http://<IP-do-seu-PC>:8080`. É o IP do **computador**, não
   `localhost` e não o do Kindle.
2. **Usuário** — o mesmo do passo 4.
3. **Entrar / trocar senha** — digitar a senha troca por um token. O token fica salvo
   e expira sozinho; o plugin renova na próxima sincronização.

Depois, **Sincronizar agora**. A biblioteca deve aparecer.

## Quando algo dá errado

Abra **Configurações → Diagnóstico**. A tela responde seis perguntas de uma vez:

```
Chat Reader 0.1.0 · contrato 1
KOReader v2026.03

Banco: /mnt/us/koreader/data/chatreader.sqlite3
  conversas: 6 · mensagens: 32 · tags: 3 · bookmarks: 2

Servidor: http://<ip-do-seu-computador>:8080
  usuário: reader
  token: 57 min

Fila de escritas: 0 pendente(s)
```

### O menu não tem "Chat Reader"

O pluginloader não leu a pasta. Quase sempre é o nome: confira que é
`chatreader.koplugin` (com o ponto), dentro de `koreader/plugins/`, e que o KOReader
foi **reiniciado** depois da cópia.

### "Endereço recusado" / conexão recusada

O Kindle chegou no endereço, e não há nada escutando. O servidor não está rodando, ou
está em outra máquina/porta. O texto exato importa: **"recusado"** é porta fechada
(nada escutando), **"tempo esgotado"** é firewall ou rede errada.

### "Tempo esgotado" em tudo

Firewall do computador ou rede diferente. No próprio Kindle, o endereço IP do
diagnóstico precisa ser o do seu computador na rede local. Se o IP do Kindle for
`192.168.15.x`, o seu computador tem que estar na mesma faixa — e o número que você
digitou no endereço tem que ser o do computador, não o do Kindle.

### 401 na sincronização

Token expirado ou senha trocada. **Entrar / trocar senha** de novo.

### A biblioteca está vazia mas o diagnóstico mostra 0 conversas

Sincronize. Se o sync responder com erro, o diagnóstico mostra o **último erro** logo
abaixo da fila.

### A tela abriu, mas o texto sai cortado ou ilegível

Isso é o que **não** dá para testar no container, e é o motivo de o teste no aparelho
existir. Se acontecer, é do KOReader ou do `TextViewer`, não do plugin: vale
reportar com o modelo do aparelho e a versão do KOReader.

## O que os testes já garantem (e o que não)

| Verificado | Onde |
|---|---|
| Tudo compila | `spec/syntax_spec.lua` |
| As telas **executam**: carregam, instanciam widgets, botões respondem | `spec/ui_spec.lua` |
| Campos aceitos por cada widget do KOReader | conferidos contra `frontend/ui/widget/*.lua` do upstream |
| Store, sync, biblioteca, leitor, HTTP, outbox | specs da pasta `spec/` |
| Contrato com o backend real | `./kindle/scripts/contract.sh` |
| Offline de verdade (backend derrubado) | `./kindle/scripts/offline.sh` |
| O pacote carrega fora do repo | `./kindle/scripts/package.sh` |

**Não** verificado: e-ink (contraste,Refresh, temporização de refresh), gestos de
página, teclado, orientação de tela, e comportamento com biblioteca grande.

## Para Desinstalar

Apague a pasta `koreader/plugins/chatreader.koplugin` e reinicie o KOReader. Os dados
ficam em `koreader/data/chatreader.sqlite3`; apague essa pasta para limpar tudo.