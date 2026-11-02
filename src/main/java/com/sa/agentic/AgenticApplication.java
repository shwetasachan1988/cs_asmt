package com.sa.agentic;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableAsync
public class AgenticApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgenticApplication.class, args);
    }
}
