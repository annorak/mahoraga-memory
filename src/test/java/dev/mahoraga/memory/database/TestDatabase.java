package dev.mahoraga.memory.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL container shared by the runtime and application tests, started once per test JVM
 * and reaped by Testcontainers on exit. Each test uses its own database inside the container so
 * migration state cannot leak between cases.
 */
public final class TestDatabase {

  private static final PostgreSQLContainer CONTAINER =
      new PostgreSQLContainer("postgres:18.4-alpine");

  static {
    CONTAINER.start();
  }

  private TestDatabase() {}

  public static String username() {
    return CONTAINER.getUsername();
  }

  public static String password() {
    return CONTAINER.getPassword();
  }

  public static String urlFor(String databaseName) {
    return "jdbc:postgresql://%s:%d/%s"
        .formatted(CONTAINER.getHost(), CONTAINER.getMappedPort(5432), databaseName);
  }

  /** Creates the database if absent and returns its JDBC URL. */
  public static String ensureDatabase(String databaseName) throws SQLException {
    try (Connection admin = adminConnection();
        PreparedStatement exists =
            admin.prepareStatement("SELECT 1 FROM pg_database WHERE datname = ?")) {
      exists.setString(1, databaseName);
      try (ResultSet found = exists.executeQuery()) {
        if (!found.next()) {
          try (Statement create = admin.createStatement()) {
            create.executeUpdate("CREATE DATABASE " + databaseName);
          }
        }
      }
    }
    return urlFor(databaseName);
  }

  /** Counts server-side connections to the named database, seen from the admin database. */
  public static int connectionCount(String databaseName) throws SQLException {
    try (Connection admin = adminConnection();
        PreparedStatement query =
            admin.prepareStatement(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = ?")) {
      query.setString(1, databaseName);
      try (ResultSet row = query.executeQuery()) {
        row.next();
        return row.getInt(1);
      }
    }
  }

  public static Connection connect(String databaseName) throws SQLException {
    return DriverManager.getConnection(urlFor(databaseName), username(), password());
  }

  private static Connection adminConnection() throws SQLException {
    return DriverManager.getConnection(CONTAINER.getJdbcUrl(), username(), password());
  }
}
