package com.example.chatreader.shared;

/** Recurso inexistente → HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
