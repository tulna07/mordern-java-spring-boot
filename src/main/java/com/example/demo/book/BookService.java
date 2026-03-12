package com.example.demo.book;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class BookService {

    private static final String TOPIC_BOOKS = "books";
    private static final String CACHE_BOOKS = "books";

    private final BookRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public BookService(BookRepository repository, KafkaTemplate<String, String> kafkaTemplate) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
    }

    public Page<BookResponse> findAll(Pageable pageable) {
        return repository.findAll(pageable).map(BookResponse::from);
    }

    public Page<BookResponse> findByAuthor(String author, Pageable pageable) {
        return repository.findByAuthorContainingIgnoreCase(author, pageable).map(BookResponse::from);
    }

    public Page<BookResponse> findByTitle(String title, Pageable pageable) {
        return repository.findByTitleContainingIgnoreCase(title, pageable).map(BookResponse::from);
    }

    @Cacheable(value = CACHE_BOOKS, key = "#id")
    public BookResponse findById(Long id) {
        return repository.findById(id)
                .map(BookResponse::from)
                .orElseThrow(() -> new BookNotFoundException(id));
    }

    @Transactional
    @CacheEvict(value = CACHE_BOOKS, allEntries = true)
    public BookResponse create(BookRequest request) {
        Book saved = repository.save(new Book(request.title(), request.author(), request.price()));
        kafkaTemplate.send(TOPIC_BOOKS, saved.getId().toString(), BookEvent.of("created", saved).toJson());
        return BookResponse.from(saved);
    }

    @Transactional
    @CacheEvict(value = CACHE_BOOKS, allEntries = true)
    public BookResponse update(Long id, BookRequest request) {
        Book book = repository.findById(id).orElseThrow(() -> new BookNotFoundException(id));
        book.setTitle(request.title());
        book.setAuthor(request.author());
        book.setPrice(request.price());
        Book saved = repository.save(book);
        kafkaTemplate.send(TOPIC_BOOKS, saved.getId().toString(), BookEvent.of("updated", saved).toJson());
        return BookResponse.from(saved);
    }

    @Transactional
    @CacheEvict(value = CACHE_BOOKS, allEntries = true)
    public void deleteById(Long id) {
        if (!repository.existsById(id)) throw new BookNotFoundException(id);
        repository.deleteById(id);
        kafkaTemplate.send(TOPIC_BOOKS, id.toString(), BookEvent.deleted(id).toJson());
    }
}
