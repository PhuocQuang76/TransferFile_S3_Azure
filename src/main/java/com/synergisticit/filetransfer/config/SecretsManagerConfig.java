package com.synergisticit.filetransfer.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;

import java.util.Collections;
import java.util.Map;

@Slf4j
//Generates a logger object (log) so you can log operational info and errors without writing boilerplates.
@Configuration
//Tells Spring Boot that this class contains bean definitions to be managed in the application context.
//Follows Single Responsibility Principle - only handles secrets parsing from AWS Secrets Manager.
public class SecretsManagerConfig {

    //Reads the target secret name (s3-to-azure/credentials_v2) from your application.properties.
    @Value("${aws.secrets.secret-name}")
    private String secretName;

    @Bean
    public Map<String, String> secrets(SecretsManagerClient secretsManagerClient, ObjectMapper objectMapper) {
        log.info("Fetching secrets from AWS Secrets Manager [secretId={}]", secretName);

        try {
            GetSecretValueRequest request = GetSecretValueRequest.builder()
                    .secretId(secretName)
                    .build();

            GetSecretValueResponse response = secretsManagerClient.getSecretValue(request);
            String secretJson = response.secretString();

            if (secretJson == null || secretJson.isBlank()) {
                log.warn("Secret string returned empty for secretId: {}", secretName);
                return Collections.emptyMap();
            }

            // Parses ALL key-value pairs dynamically directly from Secrets Manager JSON  / Jackson's
            //ObjectMapper is the primary class in the Jackson library for Java, used for converting Java objects to/from JSON (Serialization and Deserialization).
            Map<String, String> secrets = objectMapper.readValue(secretJson, new TypeReference<>() {});
            log.info("Successfully loaded {} secret keys from AWS Secrets Manager", secrets.size());
            return secrets;

        } catch (Exception e) {
            log.error("Failed to fetch secrets from AWS Secrets Manager", e);
            throw new IllegalStateException("Failed to initialize cloud secrets", e);
        }
    }
}