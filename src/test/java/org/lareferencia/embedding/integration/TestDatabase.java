package org.lareferencia.embedding.integration;

import java.sql.DriverManager;
import java.util.UUID;

/** Creates a new database on an explicitly selected disposable test server. Never reuses application data. */
public final class TestDatabase {
  public static String create() { return create(true); }
  public static String createWithoutExtension() { return create(false); }
  private static String create(boolean extension) {
    String root = System.getenv("DVP_TEST_DB_URL");
    if (root == null || !root.startsWith("jdbc:postgresql:") || !root.endsWith("/dvp_test")) {
      throw new IllegalStateException("Set DVP_TEST_DB_URL to a disposable PostgreSQL server's /dvp_test database");
    }
    String name = "dvp_test_" + UUID.randomUUID().toString().replace("-", "");
    try (var c = DriverManager.getConnection(root, "postgres", ""); var statement = c.createStatement()) {
      statement.execute("CREATE DATABASE " + name);
      String url = root.substring(0, root.lastIndexOf('/') + 1) + name;
      try (var db = DriverManager.getConnection(url, "postgres", ""); var s = db.createStatement()) {
        if (extension) s.execute("CREATE EXTENSION vector");
      }
      return url;
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("Cannot create disposable integration database", e);
    }
  }
}
