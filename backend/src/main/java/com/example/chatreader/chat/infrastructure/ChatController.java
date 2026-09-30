package com.example.chatreader.chat.infrastructure;

import com.example.chatreader.chat.application.ChatService;
import com.example.chatreader.shared.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chats")
@Tag(name = "Chats", description = "Catalogo de conversas importadas")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping
    @Operation(summary = "Lista conversas, mais recentes primeiro; filtra por favorito ou tag")
    public PageResponse<ChatDtos.ChatSummaryResponse> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @Parameter(description = "true lista apenas favoritos")
            @RequestParam(required = false) Boolean favorite,
            @Parameter(description = "filtra por tag (case-insensitive)")
            @RequestParam(required = false) String tag) {

        var items = chatService.list(page, size, favorite, tag).stream()
                .map(ChatDtos.ChatSummaryResponse::from)
                .toList();
        var total = chatService.count(favorite, tag);
        var last = (long) (page + 1) * size >= total;
        return new PageResponse<>(items, page, size, total, last);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Obtem uma conversa com todas as mensagens")
    public ChatDtos.ChatDetailResponse get(@PathVariable String id) {
        return ChatDtos.ChatDetailResponse.from(chatService.get(id));
    }

    @GetMapping("/{id}/messages")
    @Operation(summary = "Pagina as mensagens de uma conversa (leitor do Kindle)")
    public PageResponse<ChatDtos.MessageResponse> messages(
            @PathVariable String id,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(500) int size) {

        var result = chatService.messages(id, page, size);
        return new PageResponse<>(result.items().stream()
                .map(ChatDtos.MessageResponse::from)
                .toList(), result.page(), result.size(), result.total(), result.last());
    }

    @PutMapping("/{id}/favorite")
    @Operation(summary = "Marca ou desmarca a conversa como favorita")
    public ChatDtos.ChatSummaryResponse setFavorite(@PathVariable String id,
                                                    @Valid @RequestBody ChatDtos.FavoriteRequest request) {
        return ChatDtos.ChatSummaryResponse.from(chatService.setFavorite(id, request.favorite()));
    }

    @PutMapping("/{id}/tags")
    @Operation(summary = "Substitui o conjunto de tags da conversa")
    public ChatDtos.ChatSummaryResponse setTags(@PathVariable String id,
                                                @Valid @RequestBody ChatDtos.SetTagsRequest request) {
        return ChatDtos.ChatSummaryResponse.from(chatService.setTags(id, request.tags()));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove permanentemente uma conversa")
    public void delete(@PathVariable String id) {
        chatService.delete(id);
    }
}
