package com.example.chatreader.bookmark.application;

import com.example.chatreader.bookmark.domain.Bookmark;
import com.example.chatreader.bookmark.domain.BookmarkRepository;
import com.example.chatreader.chat.domain.ChatRepository;
import com.example.chatreader.shared.ConflictException;
import com.example.chatreader.shared.NotFoundException;
import com.example.chatreader.sync.application.ChangeLogWriter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bookmarks de mensagens: criar, listar e remover (secao 10). */
@Service
@Transactional(readOnly = true)
public class BookmarkService {

    private static final int MAX_NOTE_LENGTH = 1000;

    private final BookmarkRepository bookmarkRepository;
    private final ChatRepository chatRepository;
    private final ChangeLogWriter changeLogWriter;

    public BookmarkService(BookmarkRepository bookmarkRepository, ChatRepository chatRepository,
                           ChangeLogWriter changeLogWriter) {
        this.bookmarkRepository = bookmarkRepository;
        this.chatRepository = chatRepository;
        this.changeLogWriter = changeLogWriter;
    }

    @Transactional
    public Bookmark create(String chatId, String messageId, String note) {
        requireMessageBelongsToChat(chatId, messageId);
        if (bookmarkRepository.existsByChatIdAndMessageId(chatId, messageId)) {
            throw new ConflictException("A mensagem ja possui bookmark");
        }
        return persist(new Bookmark(UUID.randomUUID().toString(), chatId, messageId, normalizeNote(note),
                Instant.now()));
    }

    /**
     * Bookmark vindo do ACK, com id gerado no cliente. Totalmente idempotente:
     * reenviar o mesmo item (ou um item ja convergido em outro id) devolve o
     * bookmark existente em vez de recusar — reenvio seguro e o cliente so
     * descarta a escrita pendente quando recebe 2xx sem rejeicao.
     */
    @Transactional
    public Bookmark createFromClient(String id, String chatId, String messageId, String note) {
        var byId = bookmarkRepository.findById(id);
        if (byId.isPresent()) {
            return byId.get();
        }
        var byMessage = bookmarkRepository.findByChatIdAndMessageId(chatId, messageId);
        if (byMessage.isPresent()) {
            return byMessage.get();
        }
        requireMessageBelongsToChat(chatId, messageId);
        return persist(new Bookmark(id, chatId, messageId, normalizeNote(note), Instant.now()));
    }

    public List<Bookmark> list(String chatId, int page, int size) {
        return chatId == null || chatId.isBlank()
                ? bookmarkRepository.findAll(page, size)
                : bookmarkRepository.findByChatId(chatId, page, size);
    }

    public long count(String chatId) {
        return chatId == null || chatId.isBlank()
                ? bookmarkRepository.count()
                : bookmarkRepository.countByChatId(chatId);
    }

    @Transactional
    public void delete(String id) {
        var bookmark = bookmarkRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Bookmark nao encontrado: " + id));
        bookmarkRepository.deleteById(id);
        changeLogWriter.bookmarkDeleted(bookmark.id(), bookmark.chatId());
    }

    /** Remocao idempotente vinda do ACK: item ja ausente e aceito. */
    @Transactional
    public void deleteFromClient(String id) {
        bookmarkRepository.findById(id).ifPresent(bookmark -> {
            bookmarkRepository.deleteById(id);
            changeLogWriter.bookmarkDeleted(bookmark.id(), bookmark.chatId());
        });
    }

    private Bookmark persist(Bookmark bookmark) {
        var saved = bookmarkRepository.save(bookmark);
        changeLogWriter.bookmarkCreated(saved.id(), saved.chatId());
        return saved;
    }

    private void requireMessageBelongsToChat(String chatId, String messageId) {
        var chat = chatRepository.findById(chatId)
                .orElseThrow(() -> new NotFoundException("Chat nao encontrado: " + chatId));
        var messageExists = chat.messages().stream().anyMatch(m -> m.id().equals(messageId));
        if (!messageExists) {
            throw new NotFoundException("Mensagem nao encontrada no chat " + chatId + ": " + messageId);
        }
    }

    private static String normalizeNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        var trimmed = note.strip();
        return trimmed.length() > MAX_NOTE_LENGTH ? trimmed.substring(0, MAX_NOTE_LENGTH) : trimmed;
    }
}
