package org.jphototagger.api.config;

import com.zaxxer.hikari.HikariDataSource;
import org.jphototagger.api.repository.ShareLookupRepository;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class ShareReaderDataSourceConfig implements DisposableBean {

    private HikariDataSource dataSource;

    @Bean
    ShareLookupRepository shareLookupRepository(
            @Value("${app.share-reader.jdbc-url}") String jdbcUrl,
            @Value("${app.share-reader.username}") String username,
            @Value("${app.share-reader.password}") String password) {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(jdbcUrl);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        dataSource.setMaximumPoolSize(3);
        dataSource.setConnectionInitSql("SET application_name = 'share_reader'");
        return new ShareLookupRepository(dataSource);
    }

    @Override
    public void destroy() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }
}
