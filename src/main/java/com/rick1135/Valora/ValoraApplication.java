package com.rick1135.Valora;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ValoraApplication {

	public static void main(String[] args) {
		SpringApplication.run(ValoraApplication.class, args);
	}

}
