package com.example.chatreader.search.application;

import com.example.chatreader.search.domain.SearchHit;
import com.example.chatreader.search.domain.SearchRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Busca textual sobre titulo e conteudo das mensagens (secao 13). */
@Service
@Transactional(readOnly = true)
public class SearchService {

    private static final String MARK = "<mark>";

    private final SearchRepository searchRepository;

    public SearchService(SearchRepository searchRepository) {
        this.searchRepository = searchRepository;
    }

    public List<SearchHit> search(String query, int page, int size) {
        var normalized = requireQuery(query);
        return searchRepository.search(normalized, page, size).stream()
                .map(SearchService::stripHighlights)
                .toList();
    }

    public long count(String query) {
        return searchRepository.count(requireQuery(query));
    }

    private static String requireQuery(String query) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Parametro 'q' e obrigatorio");
        }
        return query.strip();
    }

    /** O cliente Kindle renderiza o snippet como texto puro: removemos o marcacao. */
    private static SearchHit stripHighlights(SearchHit hit) {
        var snippet = hit.snippet() == null ? null : hit.snippet().replace(MARK, "").replace("</mark>", "");
        return new SearchHit(hit.chatId(), hit.title(), hit.messageId(), hit.role(), snippet, hit.createdAt());
    }
}
