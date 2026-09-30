package com.example.chatreader.bookmark.infrastructure;

import com.example.chatreader.bookmark.domain.Bookmark;
import com.example.chatreader.bookmark.domain.BookmarkRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Implementacao JPA da porta {@link BookmarkRepository}. */
@Repository
public class BookmarkRepositoryAdapter implements BookmarkRepository {

    private final BookmarkJpaRepository bookmarks;
    private final BookmarkMapper mapper;

    public BookmarkRepositoryAdapter(BookmarkJpaRepository bookmarks, BookmarkMapper mapper) {
        this.bookmarks = bookmarks;
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public Bookmark save(Bookmark bookmark) {
        return mapper.toDomain(bookmarks.save(mapper.toEntity(bookmark)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Bookmark> findById(String id) {
        return bookmarks.findById(id).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Bookmark> findByChatIdAndMessageId(String chatId, String messageId) {
        return bookmarks.findByChatIdAndMessageId(chatId, messageId).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Bookmark> findAll(int page, int size) {
        return bookmarks.findAllByOrderByCreatedAtDescIdDesc(pageable(page, size))
                .stream().map(mapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Bookmark> findByChatId(String chatId, int page, int size) {
        return bookmarks.findByChatIdOrderByCreatedAtDescIdDesc(chatId, pageable(page, size))
                .stream().map(mapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        return bookmarks.count();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByChatId(String chatId) {
        return bookmarks.countByChatId(chatId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByChatIdAndMessageId(String chatId, String messageId) {
        return bookmarks.existsByChatIdAndMessageId(chatId, messageId);
    }

    @Override
    @Transactional
    public void deleteById(String id) {
        bookmarks.deleteById(id);
    }

    private static PageRequest pageable(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.max(size, 1));
    }
}
