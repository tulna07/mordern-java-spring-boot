package com.example.demo.book;

import java.time.Instant;

public record BookEvent(String action, Long id, String title, String author, Instant timestamp) {

    public static BookEvent of(String action, Book book) {
        return new BookEvent(action, book.getId(), book.getTitle(), book.getAuthor(), Instant.now());
    }

    public static BookEvent deleted(Long id) {
        return new BookEvent("deleted", id, null, null, Instant.now());
    }

    public String toJson() {
        return "{\"action\":\"%s\",\"id\":%d,\"title\":%s,\"author\":%s,\"timestamp\":\"%s\"}"
                .formatted(action, id,
                        title != null ? "\"" + title + "\"" : "null",
                        author != null ? "\"" + author + "\"" : "null",
                        timestamp);
    }
}
