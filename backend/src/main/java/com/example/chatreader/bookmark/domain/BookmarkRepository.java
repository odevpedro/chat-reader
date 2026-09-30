package com.example.chatreader.bookmark.domain;

import java.util.List;
import java.util.Optional;

/** Porta de persistencia de bookmarks. */
public interface BookmarkRepository {

    Bookmark save(Bookmark bookmark);

    Optional<Bookmark> findById(String id);

    Optional<Bookmark> findByChatIdAndMessageId(String chatId, String messageId);

    List<Bookmark> findAll(int page, int size);

    List<Bookmark> findByChatId(String chatId, int page, int size);

    long count();

    long countByChatId(String chatId);

    boolean existsByChatIdAndMessageId(String chatId, String messageId);

    void deleteById(String id);
}
