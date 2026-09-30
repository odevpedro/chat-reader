package com.example.chatreader.importer.domain;

import java.util.List;

/**
 * Resultado de uma importacao. Nao lanca excecao para item malformado: um chat que
 * falha e contabilizado em {@code failed} e os demais sao persistidos (R7).
 */
public record ImportResult(
        int total,
        int created,
        int updated,
        int skipped,
        int failed,
        List<String> failures
) {
    public ImportResult {
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    public static ImportResult empty() {
        return new ImportResult(0, 0, 0, 0, 0, List.of());
    }

    public boolean isClean() {
        return failed == 0;
    }
}
