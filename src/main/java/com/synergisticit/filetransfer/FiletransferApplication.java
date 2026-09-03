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
	public CommandLineRunner verifySecretsConnection(Map<String, String> secrets) {
		return args -> {
			log.info("========== AWS SECRETS MANAGER CONNECTION TEST ==========");
			if (secrets.isEmpty()) {
				log.warn("Connection succeeded, but no keys were found in the secret!");
			} else {
				log.info("Connection SUCCESSFUL! Loaded {} secret keys.", secrets.size());
				// Logs key names ONLY to avoid printing sensitive passwords/connection strings
				secrets.keySet().forEach(key -> log.info("  Loaded key: {}", key));
			}
			log.info("=========================================================");
		};
	}
}