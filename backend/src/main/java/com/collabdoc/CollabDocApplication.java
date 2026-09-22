package com.collabdoc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CollabDocApplication {

    public static void main(String[] args) {
        SpringApplication.run(CollabDocApplication.class, args);
    }
}
