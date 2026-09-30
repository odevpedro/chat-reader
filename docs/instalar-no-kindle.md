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

Guarde esse IP. Supondo `192.168.0.10`, o endereço do servidor é
`http://192.168.0.10:8080`.

Duas coisas que precisam ser verdade para o Kindle alcançar:

1. **mesma rede Wi-Fi** — não funciona em cellular, VPN ou rede de visitante;
2. **o firewall do seu computador libera a porta 8080**, ou o teste dá timeout em vez
   de recusa. Se você usa `ufw`: `sudo ufw allow 8080/tcp`.

## 4. Ter uma senha de usuário

O servidor não aceita qualquer senha: o hash vem do `.env` local. Para um usuário de
teste:

```sh
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
# em outro terminal, se o .env ainda nao tem:
htpasswd -bnBC 10 usuario senha-forte
```

Copie a linha gerada para `CHAT_READER_USERS` no `.env` e reinicie o servidor.
Verifique que responde:

```sh
curl -s http://192.168.0.10:8080/actuator/health
```

Se isso não responder, o problema está aqui — ainda não é do Kindle.

## 5. Importar conversas

Pelo menos uma, para o plugin ter o que mostrar:

```sh
./scripts/import.sh sample-data/chats.json
```

## 6. Gerar o pacote

```sh
./kindle/scripts/package.sh
```

Isso cria `chatreader.koplugin` na raiz do repo e, no fim, imprime a verificação:

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

**Sem SSH, pelo navegador do Kindle:**

1. `http://<ip-do-kindle>:8080/` no navegador do Kindle;
2. entre em *Arquivos* → `koreader` → `plugins`;
3. *Enviar arquivo* → escolha o `chatreader.koplugin`.

**Com SSH** (mais confiável para arquivo grande):

```sh
scp chatreader.koplugin root@<ip-do-kindle>:/mnt/sd/koreader/plugins/
```

O nome tem que terminar em `.koplugin`. Sem o sufixo, o pluginloader ignora a pasta e
o menu simplesmente não muda — é a causa número um de "instalei e nada aconteceu".

## 8. Reiniciar e abrir

Feche o KOReader de vez (no menu, *Sair*) e abra de novo. Plugin novo só é lido na
partida seguinte.

**Menu → Ferramentas → Plugins → Chat Reader**

## 9. Configurar de dentro do Kindle

O menu **Configurações** do plugin pede, nesta ordem:

1. **Endereço** — `http://192.168.0.10:8080` (o IP do computador, não `localhost`).
2. **Usuário** — o mesmo do passo 4.
3. **Entrar / trocar senha** — digitar a senha troca por um token. O token fica salvo
   e expira sozinho; o plugin renova na próxima sincronização.

Depois, **Sincronizar agora**. A biblioteca deve aparecer.

## Quando algo dá errado

Abra **Configurações → Diagnóstico**. A tela responde seis perguntas de uma vez:

```
Chat Reader 0.1.0 · contrato 1
KOReader 2024.2

Banco: /mnt/sd/koreader/data/chatreader.sqlite3
  conversas: 6 · mensagens: 32 · tags: 3 · bookmarks: 2

Servidor: http://192.168.0.10:8080
  usuário: odevpedro
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
`192.168.15.x`, o seu computador tem que ser da mesma faixa.

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