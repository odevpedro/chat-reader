package com.example.chatreader.chat.infrastructure;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
public interface MessageJpaRepository extends JpaRepository<MessageEntity, String> {

    List<MessageEntity> findByChatIdOrderBySequenceAsc(String chatId);

    List<MessageEntity> findByChatId(String chatId, Pageable pageable);

    void deleteByChatId(String chatId);

    long countByChatId(String chatId);
}
