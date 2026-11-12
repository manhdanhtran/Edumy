package com.edumy;

import org.springframework.boot.SpringApplication;

public class TestEdumyBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(EdumyBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
