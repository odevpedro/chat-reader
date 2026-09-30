package com.example.chatreader.importer.domain;

/** Formatos que o import layer sabe ler. Cada um tem exatamente um adapter. */
public enum ImportFormat {

    /** JSON generico do projeto (sample-data/chats.json). */
    JSON,

    /** Markdown com front-matter, separado por {@code ---}. */
    MARKDOWN,

    /** Export de arquivo do ChatGPT (conversations.json). */
    CHATGPT_EXPORT
}
