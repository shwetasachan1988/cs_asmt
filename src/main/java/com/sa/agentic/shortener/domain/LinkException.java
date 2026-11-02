package com.sa.agentic.shortener.domain;

public class LinkException extends RuntimeException {

    public enum Kind { INVALID_URL, NOT_FOUND, RATE_LIMITED }

    private final Kind kind;

    public LinkException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
