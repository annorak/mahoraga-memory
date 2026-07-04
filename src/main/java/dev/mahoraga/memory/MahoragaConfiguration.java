package dev.mahoraga.memory;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.core.Configuration;
import io.dropwizard.db.DataSourceFactory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/** Application configuration. */
public class MahoragaConfiguration extends Configuration {

  @Valid @NotNull private DataSourceFactory database = new DataSourceFactory();

  @JsonProperty("database")
  public DataSourceFactory getDatabase() {
    return database;
  }

  @JsonProperty("database")
  public void setDatabase(DataSourceFactory database) {
    this.database = database;
  }
}
