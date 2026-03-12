package com.example.demo.book;

import java.time.Instant;

public record BookResponse(
        Long id,
        String title,
        String author,
        Double price,
        Instant createdAt,
        Instant updatedAt
) {
    public static BookResponse from(Book book) {
        return new BookResponse(
                book.getId(),
                book.getTitle(),
                book.getAuthor(),
                book.getPrice(),
                book.getCreatedAt(),
                book.getUpdatedAt()
        );
    }
}
