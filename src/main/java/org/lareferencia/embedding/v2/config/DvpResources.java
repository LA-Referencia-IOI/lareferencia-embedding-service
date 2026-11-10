package org.lareferencia.embedding.v2.config;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration
@ConditionalOnProperty(name="app.dvp.enabled", havingValue="true")
public class DvpResources {
  private HikariDataSource pool(DataSourceProperties db, DvpProperties p, String name, int size, int seconds) {
    var ds = db.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    ds.setPoolName(name);
    ds.setMaximumPoolSize(size);
    ds.setMinimumIdle(0);
    ds.setConnectionTimeout(p.getConnectionTimeoutMs());
    ds.addDataSourceProperty("options", "-c statement_timeout=" + seconds * 1000L);
    return ds;
  }

  @Bean(destroyMethod="close")
  HikariDataSource readDataSource(DataSourceProperties db, DvpProperties p) {
    var ds = pool(db,p,"dvp-read",p.getReadConnections(),p.getExactTimeoutSeconds());
    ds.setReadOnly(true);
    return ds;
  }
  @Bean(destroyMethod="close")
  HikariDataSource coordinationDataSource(DataSourceProperties db, DvpProperties p) {
    return pool(db,p,"dvp-coordination",p.getCoordinationConnections(),p.getCoordinationTimeoutSeconds());
  }
  @Bean(destroyMethod="close")
  HikariDataSource lockDataSource(DataSourceProperties db, DvpProperties p) {
    return pool(db,p,"dvp-locks",p.getLockConnections(),p.getCoordinationTimeoutSeconds());
  }
  @Bean(destroyMethod="close")
  HikariDataSource maintenanceDataSource(DataSourceProperties db, DvpProperties p) {
    return pool(db,p,"dvp-maintenance",1,0);
  }
  @Bean JdbcTemplate readJdbc(@Qualifier("readDataSource") DataSource ds, DvpProperties p) {
    var jdbc = new JdbcTemplate(ds); jdbc.setQueryTimeout(p.getExactTimeoutSeconds()); return jdbc;
  }
  @Bean JdbcTemplate coordinationJdbc(@Qualifier("coordinationDataSource") DataSource ds, DvpProperties p) {
    var jdbc = new JdbcTemplate(ds); jdbc.setQueryTimeout(p.getCoordinationTimeoutSeconds()); return jdbc;
  }
  @Bean TransactionTemplate coordinationTransactions(@Qualifier("coordinationDataSource") DataSource ds,
      DvpProperties p) {
    var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(p.getCoordinationTimeoutSeconds());
    return tx;
  }
  @Bean TransactionTemplate readTransactions(@Qualifier("readDataSource") DataSource ds, DvpProperties p) {
    var tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
    tx.setReadOnly(true);
    tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    tx.setTimeout(p.getExactTimeoutSeconds());
    return tx;
  }
  @Bean TransactionTemplate writeTransactions(@Qualifier("transactionManager") PlatformTransactionManager manager,
      DvpProperties p) {
    var tx = new TransactionTemplate(manager);
    tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
    tx.setTimeout(p.getWriteTimeoutSeconds()); return tx;
  }
  @Bean(destroyMethod="shutdownNow")
  ThreadPoolExecutor inferenceExecutor(DvpProperties p) {
    return new ThreadPoolExecutor(p.getInferenceConcurrency(),p.getInferenceConcurrency(),0,TimeUnit.SECONDS,
        new SynchronousQueue<>(), Thread.ofPlatform().name("dvp-inference-",0).factory(),
        new ThreadPoolExecutor.AbortPolicy());
  }
}
