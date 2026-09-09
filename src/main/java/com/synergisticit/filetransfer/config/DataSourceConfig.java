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

@Configuration
@Profile("!test")
public class DataSourceConfig {

    @Bean
    @Primary
    public DataSource dataSource(@Qualifier("secrets") Map<String, String> secrets) {
        String username = secrets.get("mysql_username");
        String password = secrets.get("mysql_password");

        //for deployment using rds, the database URL is constructed dynamically using the username and password from AWS Secrets Manager.
        // Pull the dynamic JDBC URL from Secrets Manager
        String jdbcUrl = secrets.get("mysql_url");

        //for local development, the database URL is hardcoded to connect to a local MySQL instance.
        // String jdbcUrl = ("jdbc:mysql://localhost:3306/filetransfer?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true")


        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url(jdbcUrl)
                .username(username)
                .password(password)
                .driverClassName("com.mysql.cj.jdbc.Driver")
                .build();
    }
}
