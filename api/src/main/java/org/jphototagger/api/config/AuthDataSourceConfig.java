package org.jphototagger.api.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Configures a separate DataSource for authentication operations (login, registration,
 * email verification, OAuth2). This DataSource connects as the {@code jpt_auth} role
 * which has BYPASSRLS, allowing access to users and email_tokens tables without
 * Row-Level Security filtering.
 */
@Configuration
public class AuthDataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.auth-datasource")
    public DataSourceProperties authDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean("authDataSource")
    public DataSource authDataSource() {
        return authDataSourceProperties()
                .initializeDataSourceBuilder()
                .build();
    }

    @Bean("authJdbcTemplate")
    public JdbcTemplate authJdbcTemplate(@Qualifier("authDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
