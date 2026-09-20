package com.synergisticit.filetransfer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Map;

@Slf4j
@SpringBootApplication
public class FiletransferApplication {

	public static void main(String[] args) {
		SpringApplication.run(FiletransferApplication.class, args);
	}

	@Bean
	public CommandLineRunner verifyCredentials(Map<String, String> secrets) {
		return args -> {
			log.info("========== CREDENTIAL CONFIGURATION CHECK ==========");
			if (secrets.isEmpty()) {
				log.warn("No credentials were configured!");
			} else {
				// Logs key names and whether each is populated, never the values themselves
				secrets.forEach((key, value) ->
						log.info("  {} : {}", key, (value == null || value.isBlank()) ? "NOT SET" : "set"));
			}
			log.info("===================================================");
		};
	}
}