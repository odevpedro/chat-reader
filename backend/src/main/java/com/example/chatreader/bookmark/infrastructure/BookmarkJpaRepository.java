package com.example.chatreader.bookmark.infrastructure;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BookmarkJpaRepository extends JpaRepository<BookmarkEntity, String> {

    List<BookmarkEntity> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    List<BookmarkEntity> findByChatIdOrderByCreatedAtDescIdDesc(String chatId, Pageable pageable);

    Optional<BookmarkEntity> findByChatIdAndMessageId(String chatId, String messageId);

    long countByChatId(String chatId);

    boolean existsByChatIdAndMessageId(String chatId, String messageId);

    /** Usado quando o chat e marcado como removido: os bookmarks ficam orfaos. */
    @Modifying
    @Query("DELETE FROM BookmarkEntity b WHERE b.chatId = :chatId")
    int deleteByChatId(@Param("chatId") String chatId);
}
