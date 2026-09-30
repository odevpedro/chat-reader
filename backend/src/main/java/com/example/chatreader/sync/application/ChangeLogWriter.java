package com.example.chatreader.sync.application;

import com.example.chatreader.sync.domain.ChangeLog;
import com.example.chatreader.sync.domain.ChangeType;
import com.example.chatreader.sync.domain.SyncChange;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ponto unico de escrita do change log (docs/sync-protocol.md, secao 2).
 *
 * <p>Os modulos que produzem mudanca (importer, chat, bookmark, tag) dependem
 * apenas desta classe: nao conhecem a tabela nem a entidade JPA. A gravacao roda
 * na transacao de quem chamou ({@code REQUIRED}), para que a mudanca e o dado que
 * a motivou facam commit — ou nao facam — juntos.
 */
@Component
public class ChangeLogWriter {

    private final ChangeLog changeLog;

    public ChangeLogWriter(ChangeLog changeLog) {
        this.changeLog = changeLog;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public SyncChange chatCreated(String chatId, long contentVersion) {
        return changeLog.append(ChangeType.CHAT_CREATED, chatId, chatId, contentVersion);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public SyncChange chatUpdated(String chatId, long contentVersion) {
        return changeLog.append(ChangeType.CHAT_UPDATED, chatId, chatId, contentVersion);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public SyncChange chatDeleted(String chatId) {
        return changeLog.append(ChangeType.CHAT_DELETED, chatId, chatId, null);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public SyncChange bookmarkCreated(String bookmarkId, String chatId) {
        return changeLog.append(ChangeType.BOOKMARK_CREATED, bookmarkId, chatId, null);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public SyncChange bookmarkDeleted(String bookmarkId, String chatId) {
        return changeLog.append(ChangeType.BOOKMARK_DELETED, bookmarkId, chatId, null);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public SyncChange tagCreated(String tagId) {
        return changeLog.append(ChangeType.TAG_CREATED, tagId, null, null);
    }
}
