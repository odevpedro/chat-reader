package com.example.chatreader.shared;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Envelope padrao de paginacao da API. */
@Schema(description = "Pagina de resultados")
public record PageResponse<T>(
        List<T> items,
        int page,
        int size,
        long total,
        boolean last
) {
    @JsonProperty("totalPages")
    public int totalPages() {
        return size <= 0 ? 0 : (int) Math.ceil((double) total / size);
    }
}
