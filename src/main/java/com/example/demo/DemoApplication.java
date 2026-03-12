package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@SpringBootApplication
@EnableCaching
@EnableJpaAuditing
public class DemoApplication {

    public static void main(String[] args) {
        System.setProperty("org.jboss.logging.provider", "slf4j");
        SpringApplication.run(DemoApplication.class, args);
    }
}
