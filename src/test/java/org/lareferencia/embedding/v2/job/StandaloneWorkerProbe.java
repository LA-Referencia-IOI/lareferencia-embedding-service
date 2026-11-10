package org.lareferencia.embedding.v2.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import java.util.UUID;
import org.lareferencia.embedding.v2.config.DvpProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Separate JVM used only by the real-database multi-process lease test. */
public final class StandaloneWorkerProbe {
  public static void main(String[] args) {
    var ds=new DriverManagerDataSource(args[0],"postgres","");
    var jdbc=new JdbcTemplate(ds);var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
    var repo=new JobRepository(jdbc,jdbc,jdbc,tx,tx,ds,new ObjectMapper(),new DvpProperties());
    System.out.println(repo.claim(UUID.randomUUID().toString(),Set.of("maintenance")).isPresent()?"CLAIMED":"EMPTY");
  }
}
