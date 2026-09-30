-- Metadata lida pelo gerenciador de plugins do KOReader.
--
-- Sem este arquivo o plugin ainda carrega (o que importa e o main.lua), mas aparece
-- sem nome, sem descricao e sem versao no menu de plugins — e o gerenciador nao
-- consegue dizer de onde veio. Aqui ele tem os tres.
--
-- Sem gettext de proposito: um `require("gettext")` aqui seria mais um ponto de falha
-- no carregamento do plugin, em troca de traducao que o projeto nao tem.

return {
    name = "chatreader",
    fullname = "Chat Reader",
    description = [[
        Le o seu historico de conversas de ChatGPT, Claude e Gemini no KOReader,
        com palavras-chave, tags e favoritos, offline. Sincroniza com um servidor
        proprio; o conteudo nunca sai do seu controle.
    ]],
    version = "0.1.0",
    -- Versao do contrato de dados com o servidor. O backend tem a mesma versao em
    -- application.yml; se um so lado mudar, o outro avisa em vez de silenciosamente
    -- ler conversationes erradas.
    data_contract = "1",
}