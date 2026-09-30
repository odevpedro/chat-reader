package com.example.chatreader.search.infrastructure;

import com.example.chatreader.search.application.SearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chats/search")
@Tag(name = "Busca", description = "Busca full-text em titulos e mensagens")
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping
    @Operation(summary = "Busca por titulo ou conteudo; devolve snippets em texto puro")
    public SearchDtos.SearchResponse search(
            @RequestParam String q,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {

        var hits = searchService.search(q, page, size).stream()
                .map(SearchDtos.SearchHitResponse::from)
                .toList();
        var total = searchService.count(q);
        var last = (long) (page + 1) * size >= total;
        return new SearchDtos.SearchResponse(q, hits, page, size, total, last);
    }
}
