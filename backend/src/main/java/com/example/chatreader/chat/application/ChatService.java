package com.example.chatreader.chat.application;

import com.example.chatreader.chat.domain.Chat;
import com.example.chatreader.chat.domain.ChatRepository;
import com.example.chatreader.chat.domain.Message;
import com.example.chatreader.shared.NotFoundException;
import com.example.chatreader.shared.PageResponse;
import com.example.chatreader.sync.application.ChangeLogWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Consultas e comandos de leitura sobre conversas. */
@Service
@Transactional(readOnly = true)
public class ChatService {

    private final ChatRepository chatRepository;
    private final ChangeLogWriter changeLogWriter;

    public ChatService(ChatRepository chatRepository, ChangeLogWriter changeLogWriter) {
        this.chatRepository = chatRepository;
        this.changeLogWriter = changeLogWriter;
    }

    public List<Chat> list(int page, int size, Boolean favorite, String tag) {
        if (tag != null && !tag.isBlank()) {
            return chatRepository.findByTag(tag.strip(), page, size);
        }
        if (Boolean.TRUE.equals(favorite)) {
            return chatRepository.findFavorites(page, size);
        }
        return chatRepository.findAll(page, size);
    }

    public long count(Boolean favorite, String tag) {
        if (tag != null && !tag.isBlank()) {
            return chatRepository.countByTag(tag.strip());
        }
        if (Boolean.TRUE.equals(favorite)) {
            return chatRepository.countFavorites();
        }
        return chatRepository.count();
    }

    public Chat get(String id) {
        return chatRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Chat nao encontrado: " + id));
    }

    /** Pagina de mensagens: o leitor do Kindle nao deve carregar a conversa toda. */
    public PageResponse<Message> messages(String id, int page, int size) {
        var chat = chatRepository.findMetadataById(id)
                .orElseThrow(() -> new NotFoundException("Chat nao encontrado: " + id));
        var items = chatRepository.findMessages(id, page, size);
        var total = chat.messageCount();
        var last = (long) (page + 1) * size >= total;
        return new PageResponse<>(items, page, size, total, last);
    }

    /** Marca/desmarca favorito (LWW — ADR-005). */
    @Transactional
    public Chat setFavorite(String id, boolean favorite) {
        var chat = get(id);
        if (chat.setFavorite(favorite)) {
            chatRepository.save(chat);
            changeLogWriter.chatUpdated(chat.id(), chat.contentVersion());
        }
        return chat;
    }

    /** Substitui o conjunto de tags do chat. */
    @Transactional
    public Chat setTags(String id, List<String> tags) {
        var chat = get(id);
        if (chat.setTags(tags)) {
            chatRepository.save(chat);
            changeLogWriter.chatUpdated(chat.id(), chat.contentVersion());
        }
        return chat;
    }

    /**
     * Tombstone: o chat some da biblioteca mas a linha (e as mensagens) ficam,
     * para que um cliente que sincronize depois ainda receba o CHAT_DELETED.
     */
    @Transactional
    public void delete(String id) {
        if (chatRepository.findMetadataById(id).isEmpty()) {
            throw new NotFoundException("Chat nao encontrado: " + id);
        }
        chatRepository.markDeleted(id);
        changeLogWriter.chatDeleted(id);
    }
}
