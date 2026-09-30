package com.example.chatreader.tag.application;

import com.example.chatreader.sync.application.ChangeLogWriter;
import com.example.chatreader.tag.domain.TagRef;
import com.example.chatreader.tag.domain.TagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Catalogo de tags (secao 8). A associacao com chats e feita no agregado chat. */
@Service
@Transactional(readOnly = true)
public class TagService {

    private final TagRepository tagRepository;
    private final ChangeLogWriter changeLogWriter;

    public TagService(TagRepository tagRepository, ChangeLogWriter changeLogWriter) {
        this.tagRepository = tagRepository;
        this.changeLogWriter = changeLogWriter;
    }

    public List<String> list() {
        return tagRepository.findAllNames();
    }

    /** Catalogo com id, usado no full snapshot. */
    public List<TagRef> all() {
        return tagRepository.findAll();
    }

    public long count() {
        return tagRepository.count();
    }

    /**
     * Idempotente por nome case-insensitive: se a tag ja existe, devolve a mesma
     * e nao gera evento de sync. Semeando o change log na mesma transacao, o
     * evento nunca sobrevive a um rollback do catalogo.
     */
    @Transactional
    public TagRef create(String name) {
        var existing = tagRepository.findByName(name);
        if (existing.isPresent()) {
            return existing.get();
        }
        var created = tagRepository.create(name);
        changeLogWriter.tagCreated(created.id());
        return created;
    }
}
