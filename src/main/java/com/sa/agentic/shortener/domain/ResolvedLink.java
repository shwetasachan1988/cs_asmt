package com.sa.agentic.shortener.domain;

/** What the redirect hot path needs; also the value cached in Redis as {@code id|url}. */
public record ResolvedLink(long id, String url) {

    public String serialize() {
        return id + "|" + url;
    }

    public static ResolvedLink parse(String raw) {
        int i = raw.indexOf('|');
        if (i < 1) {
            throw new IllegalArgumentException("corrupt cache entry");
        }
        return new ResolvedLink(Long.parseLong(raw.substring(0, i)), raw.substring(i + 1));
    }
}
