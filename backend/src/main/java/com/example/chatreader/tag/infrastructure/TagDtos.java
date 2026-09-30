package com.example.chatreader.tag.infrastructure;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/** DTOs do catalogo de tags. */
public final class TagDtos {

    private TagDtos() {
    }

    @Schema(description = "Catalogo de tags conhecidas")
    public record TagsResponse(List<String> tags) {
    }

    @Schema(description = "Nome da tag a criar")
    public record CreateTagRequest(
            @Schema(example = "Java") @NotBlank String name) {
    }

    public record TagResponse(
            @Schema(description = "id da tag no catalogo") String id,
            @Schema(description = "nome como gravado, grafia preservada") String name) {
    }
}
