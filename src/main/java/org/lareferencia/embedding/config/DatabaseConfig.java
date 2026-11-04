package org.lareferencia.embedding.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** The primary pool owns legacy JPA and atomic document/result writes. */
@Configuration
@EnableConfigurationProperties(DataSourceProperties.class)
public class DatabaseConfig {
  @Bean(name="dataSource", destroyMethod="close")
  @Primary
  @ConfigurationProperties("spring.datasource.hikari")
  HikariDataSource dataSource(DataSourceProperties properties) {
    return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
  }

  @Bean @Primary
  JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
    return new JdbcTemplate(dataSource);
  }

  @Bean(name="transactionManager") @Primary
  PlatformTransactionManager transactionManager(EntityManagerFactory factory,
      @Qualifier("dataSource") DataSource dataSource) {
    var manager = new JpaTransactionManager(factory);
    manager.setDataSource(dataSource);
    return manager;
  }
}
