package com.edumy;

import com.edumy.support.TestcontainersConfiguration;
import org.springframework.boot.SpringApplication;

/** Runs the app locally against a throw-away Testcontainers MySQL instead of compose.yaml. */
public class TestEdumyApplication {

    public static void main(String[] args) {
        SpringApplication.from(EdumyApplication::main).with(TestcontainersConfiguration.class).run(args);
    }
}
