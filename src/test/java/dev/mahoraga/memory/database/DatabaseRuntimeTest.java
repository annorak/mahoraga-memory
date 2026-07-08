package dev.mahoraga.memory.database;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.google.inject.ConfigurationException;
import com.google.inject.Injector;
import dev.mahoraga.memory.MahoragaApplication;
import dev.mahoraga.memory.config.MahoragaConfiguration;
import io.dropwizard.db.ManagedDataSource;
import io.dropwizard.lifecycle.JettyManaged;
import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.DropwizardTestSupport;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.validation.BaseValidator;
import jakarta.validation.ConstraintViolation;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/**
 * Proves the database startup, shutdown, and failure lifecycle against real PostgreSQL: one
 * managed pool, synchronous Flyway before application SQL, fail-closed startup, and scoped JDBI
 * handles that always return their connections.
 */
class DatabaseRuntimeTest {

  private static final long CLOSE_WAIT_MILLIS = 10_000;
  private static final String COUNT_APPLIED_MIGRATIONS =
      "SELECT count(*) FROM flyway_schema_history WHERE success";
  private static final String INSERT_ENGAGEMENT =
      "INSERT INTO engagements (tenant_id, engagement_id, source_stream_id)"
          + " VALUES ('t-tx', 'eng-1', 'stream-tx')";
  private static final String COUNT_TX_ENGAGEMENTS =
      "SELECT count(*) FROM engagements WHERE tenant_id = 't-tx'";

  @Test
  void startupOnEmptyDatabaseMigratesBeforeServingOneManagedPool() throws Exception {
    String url = TestDatabase.ensureDatabase("runtime_startup");
    DropwizardTestSupport<MahoragaConfiguration> support = support(url);
    support.before();
    try {
      Injector injector = application(support).getInjector();
      Jdbi jdbi = injector.getInstance(Jdbi.class);
      assertEquals(0, count(jdbi, "SELECT count(*) FROM engagements"));
      assertEquals(1, count(jdbi, COUNT_APPLIED_MIGRATIONS));
      assertEquals(1, managedDataSourceCount(support), "exactly one lifecycle-owned pool");
      assertThrows(ConfigurationException.class, () -> injector.getInstance(JsonMapper.class));
    } finally {
      support.after();
    }
    assertConnectionsClose("runtime_startup");
  }

  @Test
  void restartRevalidatesAndAppliesZeroMigrations() throws Exception {
    String url = TestDatabase.ensureDatabase("runtime_restart");
    startAndStop(url);
    DropwizardTestSupport<MahoragaConfiguration> support = support(url);
    support.before();
    try {
      Jdbi jdbi = application(support).getInjector().getInstance(Jdbi.class);
      assertEquals(1, count(jdbi, COUNT_APPLIED_MIGRATIONS));
    } finally {
      support.after();
    }
  }

  @Test
  void checksumMismatchAbortsStartupAndClosesPool() throws Exception {
    String url = TestDatabase.ensureDatabase("runtime_checksum");
    startAndStop(url);
    try (Connection connection = TestDatabase.connect("runtime_checksum");
        Statement statement = connection.createStatement()) {
      statement.executeUpdate("UPDATE flyway_schema_history SET checksum = checksum + 1");
    }

    DropwizardTestSupport<MahoragaConfiguration> support = support(url);
    assertThrows(Exception.class, support::before, "changed V1 checksum must abort startup");
    assertConnectionsClose("runtime_checksum");
  }

  @Test
  void unavailableDatabaseFailsStartupWithinConfiguredBound() {
    // TEST-NET-1 (RFC 5737) is unroutable, so this exercises the configured
    // connect timeout rather than an instant local connection refusal.
    DropwizardTestSupport<MahoragaConfiguration> support =
        support("jdbc:postgresql://192.0.2.1:5432/unreachable");
    long startedAt = System.currentTimeMillis();
    assertThrows(Exception.class, support::before);
    long elapsedMillis = System.currentTimeMillis() - startedAt;
    assertTrue(
        elapsedMillis < 15_000,
        "startup failure took " + elapsedMillis + "ms; expected a bounded fast failure");
  }

  @Test
  void missingDatabaseBlockFailsConfigurationValidation() {
    Set<ConstraintViolation<MahoragaConfiguration>> violations =
        BaseValidator.newValidator().validate(new MahoragaConfiguration());

    assertEquals(1, violations.size(), "a configuration without a database block must not validate");
    assertEquals("database", violations.iterator().next().getPropertyPath().toString());
  }

  @Test
  void transactionRollsBackAndHandlesReturnConnectionsToSmallPool() throws Exception {
    String url = TestDatabase.ensureDatabase("runtime_tx");
    DropwizardTestSupport<MahoragaConfiguration> support =
        support(
            url,
            ConfigOverride.config("database.initialSize", "1"),
            ConfigOverride.config("database.minSize", "1"),
            ConfigOverride.config("database.maxSize", "2"));
    support.before();
    try {
      Jdbi jdbi = application(support).getInjector().getInstance(Jdbi.class);
      assertThrows(
          IllegalStateException.class,
          () ->
              jdbi.useTransaction(
                  handle -> {
                    handle.execute(INSERT_ENGAGEMENT);
                    throw new IllegalStateException("forced rollback");
                  }));
      assertEquals(0, count(jdbi, COUNT_TX_ENGAGEMENTS), "failed transaction must roll back");

      jdbi.useTransaction(handle -> handle.execute(INSERT_ENGAGEMENT));
      assertEquals(1, count(jdbi, COUNT_TX_ENGAGEMENTS), "new transaction must succeed");

      // More sequential handle uses than maxSize: passes only when failed and
      // successful callbacks both return their connections to the pool.
      for (int i = 0; i < 5; i++) {
        assertThrows(
            IllegalStateException.class,
            () ->
                jdbi.withHandle(
                    handle -> {
                      throw new IllegalStateException("forced handle failure");
                    }));
        assertEquals(1, count(jdbi, COUNT_TX_ENGAGEMENTS));
      }
    } finally {
      support.after();
    }
  }

  private static DropwizardTestSupport<MahoragaConfiguration> support(
      String url, ConfigOverride... extraOverrides) {
    List<ConfigOverride> overrides = new ArrayList<>();
    overrides.add(ConfigOverride.config("database.url", url));
    overrides.add(ConfigOverride.config("database.user", TestDatabase.username()));
    overrides.add(ConfigOverride.config("database.password", TestDatabase.password()));
    overrides.addAll(List.of(extraOverrides));
    return new DropwizardTestSupport<>(
        MahoragaApplication.class,
        ResourceHelpers.resourceFilePath("mahoraga-test.yml"),
        overrides.toArray(ConfigOverride[]::new));
  }

  private static void startAndStop(String url) throws Exception {
    DropwizardTestSupport<MahoragaConfiguration> support = support(url);
    support.before();
    support.after();
  }

  private static MahoragaApplication application(
      DropwizardTestSupport<MahoragaConfiguration> support) {
    return (MahoragaApplication) support.getApplication();
  }

  private static int count(Jdbi jdbi, String sql) {
    return jdbi.withHandle(handle -> handle.createQuery(sql).mapTo(Integer.class).one());
  }

  private static long managedDataSourceCount(DropwizardTestSupport<MahoragaConfiguration> support) {
    return support.getEnvironment().lifecycle().getManagedObjects().stream()
        .filter(
            candidate ->
                candidate instanceof JettyManaged managed
                    && managed.getManaged() instanceof ManagedDataSource)
        .count();
  }

  /** The pool closes synchronously; PostgreSQL may lag briefly in reaping its backends. */
  private static void assertConnectionsClose(String databaseName)
      throws SQLException, InterruptedException {
    long deadline = System.currentTimeMillis() + CLOSE_WAIT_MILLIS;
    int remaining;
    do {
      remaining = TestDatabase.connectionCount(databaseName);
      if (remaining == 0) {
        return;
      }
      Thread.sleep(100);
    } while (System.currentTimeMillis() < deadline);
    fail("expected the pool to close every connection but " + remaining + " remain");
  }
}
