package com.example.chatreader.importer.domain;

/**
 * Lancada quando um arquivo de importacao e ilegivel no nivel do arquivo inteiro
 * (nao de um chat individual). Resulta em HTTP 422 na API.
 */
public class ImportException extends RuntimeException {

    public ImportException(String message) {
        super(message);
    }

    public ImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
