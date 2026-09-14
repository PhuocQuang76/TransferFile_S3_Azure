package com.synergisticit.filetransfer.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.util.Map;
import java.util.Optional;

@Configuration
@Profile("!test")
public class DataSourceConfig {

    @Value("${SPRING_DATASOURCE_URL:jdbc:mysql://filetransfer-dev-db.chss8042ypu2.us-east-1.rds.amazonaws.com:3306/filetransfer?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true}")
    private String fallbackJdbcUrl;

    @Value("${SPRING_DATASOURCE_USERNAME:admin}")
    private String fallbackUsername;

    @Value("${SPRING_DATASOURCE_PASSWORD:}")
    private String fallbackPassword;

    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("secrets") ObjectProvider<Map<String, String>> secretsProvider) {
        Map<String, String> secrets = secretsProvider.getIfAvailable();

        String username = Optional.ofNullable(secrets)
                .map(map -> map.get("mysql_username"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElseGet(() -> Optional.ofNullable(System.getenv("SPRING_DATASOURCE_USERNAME")).filter(v -> !v.isBlank()).orElse(fallbackUsername));

        String password = Optional.ofNullable(secrets)
                .map(map -> map.get("mysql_password"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElseGet(() -> Optional.ofNullable(System.getenv("SPRING_DATASOURCE_PASSWORD")).filter(v -> !v.isBlank()).orElse(fallbackPassword));

        String jdbcUrl = Optional.ofNullable(secrets)
                .map(map -> map.get("mysql_url"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElseGet(() -> Optional.ofNullable(System.getenv("SPRING_DATASOURCE_URL")).filter(v -> !v.isBlank()).orElse(fallbackJdbcUrl));

        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(jdbcUrl)
                .username(username)
                .password(password)
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .build();
    }
}