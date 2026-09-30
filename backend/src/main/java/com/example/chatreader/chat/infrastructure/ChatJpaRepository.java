package com.example.chatreader.chat.infrastructure;

import com.example.chatreader.chat.domain.ChatSource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ChatJpaRepository extends JpaRepository<ChatEntity, String> {

    Optional<ChatEntity> findBySourceAndExternalIdAndDeletedFalse(ChatSource source, String externalId);

    Optional<ChatEntity> findBySourceAndContentHashAndDeletedFalse(ChatSource source, String contentHash);

    @Query("SELECT c FROM ChatEntity c WHERE c.deleted = false ORDER BY c.updatedAt DESC NULLS LAST, c.id DESC")
    List<ChatEntity> findActive(Pageable pageable);

    @Query("SELECT c FROM ChatEntity c WHERE c.deleted = false AND c.favorite = true "
            + "ORDER BY c.updatedAt DESC NULLS LAST, c.id DESC")
    List<ChatEntity> findFavorites(Pageable pageable);

    long countByDeletedFalse();

    long countByDeletedFalseAndFavoriteTrue();

    @Query("SELECT c FROM ChatEntity c WHERE c.deleted = false AND c.id IN :ids")
    List<ChatEntity> findActiveByIdIn(@Param("ids") Collection<String> ids);

    @Query("SELECT COALESCE(SUM(c.messageCount), 0) FROM ChatEntity c WHERE c.deleted = false")
    long countActiveMessages();

    @Query("SELECT c FROM ChatEntity c WHERE c.id = :id AND c.deleted = false")
    Optional<ChatEntity> findActiveById(@Param("id") String id);
}
