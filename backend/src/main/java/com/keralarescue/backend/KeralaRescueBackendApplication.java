package com.keralarescue.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class KeralaRescueBackendApplication {
    public static void main(String[] args) {
        SpringApplication.run(KeralaRescueBackendApplication.class, args);
    }
}
