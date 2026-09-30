package com.example.chatreader.sync.infrastructure;

import com.example.chatreader.metrics.ChatReaderMetrics;
import com.example.chatreader.sync.application.SyncProperties;
import com.example.chatreader.sync.application.SyncService;
import com.example.chatreader.sync.domain.LocalChange;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/sync")
@Tag(name = "sync", description = "Sincronizacao incremental (docs/sync-protocol.md)")
public class SyncController {

    private final SyncService syncService;
    private final SyncChangeMapper mapper;
    private final SyncProperties properties;
    private final ChatReaderMetrics metrics;

    public SyncController(SyncService syncService, SyncChangeMapper mapper,
                          SyncProperties properties, ChatReaderMetrics metrics) {
        this.syncService = syncService;
        this.mapper = mapper;
        this.properties = properties;
        this.metrics = metrics;
    }

    @GetMapping
    @Operation(summary = "Mudancas posteriores ao token do cliente")
    public SyncDtos.SyncDeltaResponse sync(
            @RequestParam(defaultValue = "0") @Min(0) long since,
            @RequestParam(required = false) @Min(1) @Max(1000) Integer limit) {

        var delta = syncService.pull(since, limit == null ? properties.defaultLimit() : limit);
        metrics.recordSyncRequest(delta.changes().size());

        var changes = delta.changes().stream().map(mapper::toChange).toList();
        return new SyncDtos.SyncDeltaResponse(delta.syncVersion(), delta.hasMore(),
                delta.fullResyncRequired(), delta.serverTime(), changes);
    }

    @GetMapping("/snapshot")
    @Operation(summary = "Estado completo, paginado por chat (usado quando fullResyncRequired = true)")
    public SyncDtos.SyncSnapshotResponse snapshot(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(required = false) @Min(1) @Max(500) Integer size) {

        var snapshot = syncService.snapshot(page, size == null ? properties.snapshotDefaultPageSize() : size);
        return new SyncDtos.SyncSnapshotResponse(
                snapshot.syncVersion(),
                snapshot.page(),
                snapshot.size(),
                snapshot.total(),
                snapshot.last(),
                snapshot.tags().stream().map(mapper::tagPayload).toList(),
                snapshot.chats().stream().map(mapper::toChatPayload).toList(),
                snapshot.bookmarks().stream().map(mapper::bookmarkPayload).toList(),
                snapshot.bookmarksOmitted());
    }

    @PostMapping("/ack")
    @Operation(summary = "Confirma o token aplicado e envia as escritas feitas offline")
    public ResponseEntity<SyncDtos.AckResponse> ack(@Valid @RequestBody SyncDtos.AckRequest request) {
        var localChanges = request.localChanges() == null
                ? List.<LocalChange>of()
                : request.localChanges().stream().map(mapper::toLocalChange).toList();

        var result = syncService.ack(request.ackVersion(), localChanges);
        var rejections = result.rejections().stream()
                .map(r -> new SyncDtos.RejectionResponse(r.index(), r.reason(), r.message()))
                .toList();

        return ResponseEntity.ok(new SyncDtos.AckResponse(
                result.ackVersion(), result.newSyncVersion(),
                result.accepted(), result.rejected(), rejections));
    }
}
