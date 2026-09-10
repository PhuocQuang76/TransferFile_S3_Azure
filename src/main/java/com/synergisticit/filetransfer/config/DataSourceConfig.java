package com.synergisticit.filetransfer.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
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

    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("secrets") Map<String, String> secrets) {
        String username = Optional.ofNullable(secrets)
                .map(map -> map.get("mysql_username"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElseThrow(() -> new IllegalStateException("mysql_username is missing from AWS Secrets Manager"));

        String password = Optional.ofNullable(secrets)
                .map(map -> map.get("mysql_password"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElseThrow(() -> new IllegalStateException("mysql_password is missing from AWS Secrets Manager"));

        String jdbcUrl = Optional.ofNullable(secrets)
                .map(map -> map.get("mysql_url"))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .orElseThrow(() -> new IllegalStateException("mysql_url is missing from AWS Secrets Manager"));

        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(jdbcUrl)
                .username(username)
                .password(password)
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .build();
    }
}