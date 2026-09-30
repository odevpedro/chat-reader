package com.example.chatreader.sync.domain;

import com.example.chatreader.bookmark.domain.Bookmark;
import com.example.chatreader.chat.domain.Chat;
import com.example.chatreader.tag.domain.TagRef;

import java.util.List;

/**
 * Full snapshot: o estado inteiro quando o token do cliente ficou antigo demais
 * (docs/sync-protocol.md, secao 4.4).
 *
 * <p>Vem paginado por chat porque 10k chats nao cabem numa requisicao (secao 9).
 * As tags vao inteiras (catalogo pequeno); os bookmarks vao ate
 * {@code maxBookmarksInSnapshot} e, se sobrarem, {@code bookmarksOmitted} manda o
 * cliente buscar o resto por {@code GET /api/bookmarks}.
 *
 * @param syncVersion        token a gravar apos aplicar o snapshot
 * @param page               pagina de chats (0-based)
 * @param total              total de chats ativos
 * @param last               ultima pagina de chats
 * @param bookmarksOmitted   true se nem todos os bookmarks couberam
 */
public record SyncSnapshot(
        long syncVersion,
        int page,
        int size,
        long total,
        boolean last,
        List<TagRef> tags,
        List<Chat> chats,
        List<Bookmark> bookmarks,
        boolean bookmarksOmitted
) {
}
