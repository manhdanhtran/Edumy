package com.edumy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class EdumyApplication {

    public static void main(String[] args) {
        SpringApplication.run(EdumyApplication.class, args);
    }
}
