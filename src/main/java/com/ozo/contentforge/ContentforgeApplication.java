package com.ozo.contentforge;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class ContentforgeApplication {

	@PostConstruct
	public void init() {
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
	}

	public static void main(String[] args) {
		TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
		SpringApplication app = new SpringApplication(ContentforgeApplication.class);
		// One database session per transaction, not per request: background jobs and admin actions switch brand
		// within a request, and each brand needs its own session (see BrandContext). Overridable in properties.
		app.setDefaultProperties(java.util.Map.of("spring.jpa.open-in-view", "false"));
		app.run(args);
	}

}
