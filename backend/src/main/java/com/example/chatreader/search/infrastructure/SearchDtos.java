package com.example.chatreader.search.infrastructure;

import com.example.chatreader.search.domain.SearchHit;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/** DTOs da API de busca. */
public final class SearchDtos {

    private SearchDtos() {
    }

    @Schema(description = "Trecho que casou com a busca (snippet em texto puro)")
    public record SearchHitResponse(
            String chatId,
            String title,
            String messageId,
            String role,
            String snippet,
            Instant createdAt
    ) {
        public static SearchHitResponse from(SearchHit hit) {
            return new SearchHitResponse(hit.chatId(), hit.title(), hit.messageId(),
                    hit.role(), hit.snippet(), hit.createdAt());
        }
    }

    @Schema(description = "Resultado paginado da busca")
    public record SearchResponse(
            String query,
            List<SearchHitResponse> results,
            int page,
            int size,
            long total,
            boolean last
    ) {
    }
}
