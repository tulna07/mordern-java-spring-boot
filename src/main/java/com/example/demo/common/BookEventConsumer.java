package com.example.demo.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class BookEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(BookEventConsumer.class);

    @KafkaListener(topics = "books", groupId = "demo-group")
    public void consume(String payload) {
        log.info("Book event received: {}", payload);
    }
}
