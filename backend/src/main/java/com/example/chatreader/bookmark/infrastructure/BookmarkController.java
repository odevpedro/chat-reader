package com.example.chatreader.bookmark.infrastructure;

import com.example.chatreader.bookmark.application.BookmarkService;
import com.example.chatreader.shared.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/bookmarks")
@Tag(name = "Bookmarks", description = "Bookmarks de mensagens")
public class BookmarkController {

    private final BookmarkService bookmarkService;

    public BookmarkController(BookmarkService bookmarkService) {
        this.bookmarkService = bookmarkService;
    }

    @GetMapping
    @Operation(summary = "Lista bookmarks, mais recentes primeiro; filtra por chat")
    public PageResponse<BookmarkDtos.BookmarkResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(required = false) String chatId) {

        var items = bookmarkService.list(chatId, page, size).stream()
                .map(BookmarkDtos.BookmarkResponse::from)
                .toList();
        var total = bookmarkService.count(chatId);
        var last = (long) (page + 1) * size >= total;
        return new PageResponse<>(items, page, size, total, last);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cria um bookmark para uma mensagem")
    public BookmarkDtos.BookmarkResponse create(
            @Valid @RequestBody BookmarkDtos.CreateBookmarkRequest request) {
        return BookmarkDtos.BookmarkResponse.from(
                bookmarkService.create(request.chatId(), request.messageId(), request.note()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove um bookmark")
    public void delete(@PathVariable String id) {
        bookmarkService.delete(id);
    }
}
