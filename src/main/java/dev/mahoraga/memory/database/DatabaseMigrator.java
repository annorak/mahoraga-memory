package dev.mahoraga.memory.database;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Validates and applies the classpath Flyway migrations synchronously at startup. */
public final class DatabaseMigrator {

  private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseMigrator.class);
  private static final String MIGRATION_LOCATION = "classpath:db/migration";

  private DatabaseMigrator() {}

  /**
   * Flyway defaults keep validate-on-migrate on and clean, baseline, and out-of-order off, so a
   * checksum mismatch or failed migration aborts startup instead of being repaired implicitly.
   */
  public static void migrate(DataSource dataSource) {
    MigrateResult result =
        Flyway.configure().dataSource(dataSource).locations(MIGRATION_LOCATION).load().migrate();
    LOGGER.info("Database migrations validated; {} applied", result.migrationsExecuted);
  }
}
