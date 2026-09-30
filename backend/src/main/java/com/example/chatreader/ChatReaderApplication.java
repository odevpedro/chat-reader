package com.example.chatreader;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ChatReaderApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatReaderApplication.class, args);
    }
}
