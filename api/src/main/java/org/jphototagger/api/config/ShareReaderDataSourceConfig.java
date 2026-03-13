package org.jphototagger.api.config;

import com.zaxxer.hikari.HikariDataSource;
import org.jphototagger.api.repository.ShareLookupRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ShareReaderDataSourceConfig {

    @Bean
    ShareLookupRepository shareLookupRepository(
            @Value("${app.share-reader.jdbc-url}") String jdbcUrl,
            @Value("${app.share-reader.username}") String username,
            @Value("${app.share-reader.password}") String password) {
        var ds = new HikariDataSource();
        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername(username);
        ds.setPassword(password);
        ds.setMaximumPoolSize(3);
        ds.setConnectionInitSql("SET application_name = 'share_reader'");
        return new ShareLookupRepository(ds);
    }
}
