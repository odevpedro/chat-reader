package com.example.chatreader.shared;

/** Conflito de estado (ex.: bookmark duplicado). Mapeado para HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
