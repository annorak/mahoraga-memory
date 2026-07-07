package dev.mahoraga.memory;

import com.google.inject.Guice;
import com.google.inject.Injector;
import dev.mahoraga.memory.commands.DemoCommand;
import dev.mahoraga.memory.database.DatabaseMigrator;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.db.ManagedDataSource;
import io.dropwizard.jdbi3.JdbiFactory;
import java.util.Objects;
import org.jdbi.v3.core.Jdbi;

/** Entry point for the Mahoraga memory service. */
public class MahoragaApplication extends Application<MahoragaConfiguration> {

  private Injector injector;

  public static void main(String[] args) throws Exception {
    new MahoragaApplication().run(args);
  }

  @Override
  public String getName() {
    return "mahoraga-memory";
  }

  @Override
  public void initialize(Bootstrap<MahoragaConfiguration> bootstrap) {
    // Database URL and credentials come from environment variables. Strict
    // substitution makes a missing variable fail configuration parsing instead
    // of starting half-configured; database-free commands never parse config.
    bootstrap.setConfigurationSourceProvider(
        new SubstitutingSourceProvider(
            bootstrap.getConfigurationSourceProvider(), new EnvironmentVariableSubstitutor()));
    bootstrap.addCommand(new DemoCommand(this));
  }

  @Override
  public void run(MahoragaConfiguration configuration, Environment environment) throws Exception {
    DataSourceFactory database = configuration.getDatabase();
    ManagedDataSource dataSource = database.build(environment.metrics(), "postgresql");
    try {
      // Migration must succeed before the same pool is handed to JDBI, so no
      // application SQL can ever run against an unmigrated schema.
      DatabaseMigrator.migrate(dataSource);
      Jdbi jdbi = new JdbiFactory().build(environment, database, dataSource, "postgresql");
      injector =
          Guice.createInjector(
              new MahoragaModule(
                  configuration, environment.getObjectMapper(), environment.getValidator(), jdbi));
    } catch (Exception startupFailure) {
      // Dropwizard has not started the lifecycle yet (JdbiFactory registers
      // the pool with it, but nothing is running), so this manual stop is the
      // only cleanup path; an aborted bootstrap would otherwise leak its
      // connections.
      closeQuietly(dataSource, startupFailure);
      throw startupFailure;
    }
  }

  private static void closeQuietly(ManagedDataSource dataSource, Exception startupFailure) {
    try {
      dataSource.stop();
    } catch (Exception cleanupFailure) {
      startupFailure.addSuppressed(cleanupFailure);
    }
  }

  /** The single application injector; later tasks and tests obtain components from it. */
  public Injector getInjector() {
    return Objects.requireNonNull(injector, "injector requested before run() completed");
  }
}
