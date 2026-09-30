package com.example.chatreader.chat.domain;

/** Origem de uma conversa. Nao e o formato do arquivo, e sim de onde ela veio. */
public enum ChatSource {
    /** Export de arquivo do ChatGPT (conversations.json). Adapter: ChatGptExportImporter. */
    CHATGPT_EXPORT,
    /** JSON generico do projeto. Adapter: GenericJsonChatImporter. */
    JSON,
    /** Markdown com front-matter. Adapter: MarkdownChatImporter. */
    MARKDOWN
}
