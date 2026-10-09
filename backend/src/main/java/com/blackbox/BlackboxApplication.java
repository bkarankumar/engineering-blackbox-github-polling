package com.blackbox;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BlackboxApplication {

    public static void main(String[] args) {
        SpringApplication.run(BlackboxApplication.class, args);
    }
}
