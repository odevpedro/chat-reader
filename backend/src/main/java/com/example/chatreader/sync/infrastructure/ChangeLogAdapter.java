package com.example.chatreader.sync.infrastructure;

import com.example.chatreader.sync.domain.ChangeLog;
import com.example.chatreader.sync.domain.ChangeType;
import com.example.chatreader.sync.domain.SyncChange;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/** Implementacao JPA da porta {@link ChangeLog}. */
@Repository
public class ChangeLogAdapter implements ChangeLog {

    /** Nome criado pelo proprio Postgres para a coluna {@code version BIGSERIAL}. */
    private static final String VERSION_SEQUENCE = "sync_change_log_version_seq";

    private final ChangeLogJpaRepository changes;

    public ChangeLogAdapter(ChangeLogJpaRepository changes) {
        this.changes = changes;
    }

    @Override
    @Transactional
    public SyncChange append(ChangeType type, String entityId, String chatId, Long contentVersion) {
        var entity = new ChangeLogEntity();
        entity.setChangeType(type.name());
        entity.setEntityType(type.entityType());
        entity.setEntityId(entityId);
        entity.setChatId(chatId);
        entity.setContentVersion(contentVersion);
        entity.setCreatedAt(Instant.now());
        return toDomain(changes.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SyncChange> findSince(long since, int limit) {
        return changes.findByVersionGreaterThanOrderByVersionAsc(since, PageRequest.of(0, limit))
                .stream().map(ChangeLogAdapter::toDomain).toList();
    }

    /**
     * Maior versao ja emitida: a maior linha do log <b>ou</b> a ultima versao
     * consumida pela sequence. A sequence nao volta a zero quando a retencao
     * limpa a tabela, entao o token do cliente nunca "anda para tras".
     */
    @Override
    @Transactional(readOnly = true)
    public long currentVersion() {
        var max = Math.max(changes.maxVersion() == null ? 0L : changes.maxVersion(),
                changes.sequenceLastValue(VERSION_SEQUENCE));
        return max;
    }

    /**
     * Garante uma versao utilizavel como token, reservando uma da sequence se o
     * log estiver vazio. Sem isso, o snapshot de um backend sem nenhuma mudanca
     * devolveria {@code syncToken: 0} e o cliente nunca sairia do "preciso de
     * full sync" (docs/sync-protocol.md, secao 4.4).
     */
    @Override
    @Transactional
    public long reserveVersion() {
        var current = currentVersion();
        if (current > 0) {
            return current;
        }
        var reserved = changes.nextSequenceValue(VERSION_SEQUENCE);
        return reserved == null ? 0L : reserved;
    }

    @Override
    @Transactional(readOnly = true)
    public long oldestRetainedVersion() {
        return changes.minVersion() == null ? 0L : changes.minVersion();
    }

    @Override
    @Transactional
    public int deleteOlderThan(Instant cutoff) {
        return changes.deleteOlderThan(cutoff);
    }

    /**
     * Segunda retencao: mesmo que os 30 dias nunca venham (import em lote, por
     * exemplo), o log nao cresce sem limite.
     */
    @Override
    @Transactional
    public int keepLatest(int maxRows) {
        if (maxRows <= 0) {
            return 0;
        }
        var max = changes.maxVersion();
        if (max == null) {
            return 0;
        }
        var cutoff = max - maxRows;
        return cutoff <= 0 ? 0 : changes.deleteUpToVersion(cutoff);
    }

    private static SyncChange toDomain(ChangeLogEntity entity) {
        return new SyncChange(entity.getVersion(), ChangeType.valueOf(entity.getChangeType()),
                entity.getEntityId(), entity.getChatId(), entity.getContentVersion(),
                entity.getCreatedAt());
    }
}
