package com.luky.nexusmind;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.neo4j.Neo4jAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = Neo4jAutoConfiguration.class)
@EnableAsync
@EnableScheduling
public class NexusMindApplication {

    public static void main(String[] args) {
        SpringApplication.run(NexusMindApplication.class, args);
    }

}
