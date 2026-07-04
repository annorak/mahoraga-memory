package dev.mahoraga.memory;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.core.Configuration;
import io.dropwizard.db.DataSourceFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Application configuration. */
public class MahoragaConfiguration extends Configuration {

  // No default instance: a configuration file without a database block must
  // fail validation at startup, not at first pool use.
  @Valid @NotNull private DataSourceFactory database;

  @JsonProperty("database")
  public DataSourceFactory getDatabase() {
    return database;
  }

  @JsonProperty("database")
  public void setDatabase(DataSourceFactory database) {
    this.database = database;
  }
}
