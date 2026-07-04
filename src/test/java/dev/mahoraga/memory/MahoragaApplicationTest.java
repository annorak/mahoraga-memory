package dev.mahoraga.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.mahoraga.memory.database.TestDatabase;
import io.dropwizard.testing.ConfigOverride;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit5.DropwizardAppExtension;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import jakarta.ws.rs.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(DropwizardExtensionsSupport.class)
class MahoragaApplicationTest {

  private static final String DATABASE_URL = ensureApplicationDatabase();

  private static final DropwizardAppExtension<MahoragaConfiguration> APP =
      new DropwizardAppExtension<>(
          MahoragaApplication.class,
          ResourceHelpers.resourceFilePath("mahoraga-test.yml"),
          ConfigOverride.config("database.url", DATABASE_URL),
          ConfigOverride.config("database.user", TestDatabase.username()),
          ConfigOverride.config("database.password", TestDatabase.password()));

  @Test
  void startsOnEphemeralPorts() {
    assertTrue(APP.getLocalPort() > 0);
    assertTrue(APP.getAdminPort() > 0);
    assertNotEquals(APP.getLocalPort(), APP.getAdminPort());
  }

  @Test
  void registersNoCustomerFacingResource() throws Exception {
    boolean anyPathAnnotated =
        APP.getEnvironment().jersey().getResourceConfig().getSingletons().stream()
                .anyMatch(singleton -> singleton.getClass().isAnnotationPresent(Path.class))
            || APP.getEnvironment().jersey().getResourceConfig().getClasses().stream()
                .anyMatch(clazz -> clazz.isAnnotationPresent(Path.class));
    assertTrue(!anyPathAnnotated, "no Jersey resource may be registered yet");

    try (HttpClient client = HttpClient.newHttpClient()) {
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://localhost:" + APP.getLocalPort() + "/"))
              .GET()
              .build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(404, response.statusCode());
    }
  }

  private static String ensureApplicationDatabase() {
    try {
      return TestDatabase.ensureDatabase("app_smoke");
    } catch (SQLException e) {
      throw new IllegalStateException("could not create the application test database", e);
    }
  }
}
