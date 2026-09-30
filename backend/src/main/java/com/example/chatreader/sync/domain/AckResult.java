package com.example.chatreader.sync.domain;

import java.util.List;

/**
 * Resposta do ACK. Cada mudanca local e aplicada <b>independentemente</b>: uma
 * rejeicao nao desfaz as demais (docs/sync-protocol.md, secao 5).
 *
 * @param ackVersion    o que o cliente disse ter aplicado (informativo)
 * @param newSyncVersion versao do servidor depois de aplicar as escritas
 * @param accepted      mudancas aplicadas (ou ja convergentes)
 * @param rejections    mudancas recusadas, com o indice original
 */
public record AckResult(
        long ackVersion,
        long newSyncVersion,
        int accepted,
        List<Rejection> rejections
) {

    /** Motivo de uma recusa. {@code index} volta a posicao em {@code localChanges}. */
    public record Rejection(int index, String reason, String message) {
    }

    public int rejected() {
        return rejections.size();
    }
}
