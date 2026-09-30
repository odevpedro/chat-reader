package com.example.chatreader.tag.infrastructure;

import com.example.chatreader.tag.application.TagService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tags")
@Tag(name = "Tags", description = "Catalogo de tags conhecidas")
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    @GetMapping
    @Operation(summary = "Lista todas as tags conhecidas")
    public TagDtos.TagsResponse list() {
        return new TagDtos.TagsResponse(tagService.list());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cria uma tag no catalogo (idempotente por nome case-insensitive)")
    public TagDtos.TagResponse create(@Valid @RequestBody TagDtos.CreateTagRequest request) {
        var created = tagService.create(request.name());
        return new TagDtos.TagResponse(created.id(), created.name());
    }
}
