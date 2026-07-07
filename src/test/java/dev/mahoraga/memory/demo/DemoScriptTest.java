package dev.mahoraga.memory.demo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DemoScriptTest {

  private static final String JAR = "mahoraga-memory-0.1.0-SNAPSHOT.jar";

  @TempDir Path temporaryDirectory;

  @Test
  void preflightIsReadOnlyAndFailsClosed() throws Exception {
    Project successful = project("preflight-success");
    assertEquals(0, successful.run("preflight", Map.of()).exitCode());
    assertTrue(successful.events().isEmpty());
    assertFalse(Files.exists(successful.transcript()));

    for (Map<String, String> failure :
        List.of(
            Map.of("FAKE_JAVA_VERSION", "17"),
            Map.of("FAKE_DOCKER_INFO", "down"),
            Map.of("FAKE_PORT_BUSY", "1"))) {
      Project project = project("preflight-" + failure.values().iterator().next());
      assertFailed(project.run("preflight", failure));
      assertTrue(project.events().isEmpty());
    }

    Project missingJar = project("missing-jar");
    Files.delete(missingJar.root().resolve("target").resolve(JAR));
    assertFailed(missingJar.run("preflight", Map.of()));

    Project missingWrapper = project("missing-wrapper");
    Files.delete(missingWrapper.root().resolve("mvnw"));
    assertFailed(missingWrapper.run("preflight", Map.of()));
  }

  @Test
  void everyContainerMismatchRefusesMutation() throws Exception {
    for (String mismatch :
        List.of(
            "name", "label", "image-reference", "image-identity",
            "port-count", "binding", "binds", "mount-specifications",
            "volumes-from", "tmpfs-count", "tmpfs-options", "runtime-mounts",
            "restart", "restart-count", "auto-remove", "network",
            "network-count", "network-attached", "entrypoint", "command",
            "database-environment", "state")) {
      Project project = project("mismatch-" + mismatch);
      project.setState("running");
      project.setMismatch(mismatch);

      Result result = project.run("preflight", Map.of());

      assertFailed(result);
      assertTrue(project.events().isEmpty(), mismatch);
      assertEquals("running", project.state(), mismatch);
    }
  }

  @Test
  void existingRunLockRefusesBeforeMutation() throws Exception {
    Project project = project("locked");
    Files.createDirectory(project.root().resolve("target/demo/.run.lock"));

    assertFailed(project.run("rehearse", Map.of()));
    assertTrue(project.events().isEmpty());
    assertEquals("none", project.state());
  }

  @Test
  void rehearsalRecoversOrphanAndProducesRepeatableProof() throws Exception {
    Project project = project("rehearsal");
    project.setState("running");
    assertEquals(0, project.run("preflight", Map.of()).exitCode());
    assertTrue(project.events().isEmpty());

    Result first = project.run("rehearse", Map.of());
    assertEquals(0, first.exitCode(), first.output());
    assertEquals(
        List.of(
            "docker stop 0", "docker rm 0",
            "docker run 1", "java control 1", "docker stop 1", "docker rm 1",
            "docker run 2", "java memory 2", "java compare 2",
            "docker stop 2", "docker rm 2"),
        project.events());
    DemoEvidence.read(project.evidence());
    byte[] evidence = Files.readAllBytes(project.evidence());
    byte[] transcript = Files.readAllBytes(project.transcript());
    assertEquals(expectedTranscript(), Files.readString(project.transcript()));

    Result second = project.run("rehearse", Map.of());
    assertEquals(0, second.exitCode(), second.output());
    assertArrayEquals(evidence, Files.readAllBytes(project.evidence()));
    assertArrayEquals(transcript, Files.readAllBytes(project.transcript()));

    Result presentation = project.run("present", Map.of());
    assertEquals(0, presentation.exitCode(), presentation.output());
    assertArrayEquals(transcript, Files.readAllBytes(project.transcript()));
    String normalized = Files.readString(project.transcript());
    assertFalse(normalized.contains("55432"));
    assertFalse(normalized.contains(project.root().toString()));
    assertFalse(normalized.contains("fake-container"));
    assertFalse(normalized.contains("\u001B"));
  }

  @Test
  void failuresAndSignalsCleanUpWithoutPublishingEvidence() throws Exception {
    for (String stage : List.of("control", "memory", "compare")) {
      Project project = project("failure-" + stage);
      assertFailed(project.run("rehearse", Map.of("FAKE_JAVA_FAIL", stage)));
      assertEquals("none", project.state(), stage);
      assertNoArtifacts(project);
    }

    Project fingerprint = project("fingerprint");
    assertFailed(
        fingerprint.run("rehearse", Map.of("FAKE_CHANGE_JAR", "control")));
    assertEquals("none", fingerprint.state());
    assertNoArtifacts(fingerprint);

    Project afterOutput = project("failure-after-output");
    assertFailed(
        afterOutput.run(
            "rehearse", Map.of("FAKE_JAVA_FAIL_AFTER_OUTPUT", "control")));
    assertEquals("none", afterOutput.state());
    assertNoArtifacts(afterOutput);

    for (Map.Entry<String, String> signal :
        Map.of("HUP", "129", "INT", "130", "TERM", "143").entrySet()) {
      Project project = project("signal-" + signal.getKey());
      Result result =
          project.run("rehearse", Map.of("FAKE_SIGNAL", signal.getKey()));
      assertEquals(Integer.parseInt(signal.getValue()), result.exitCode(), result.output());
      assertEquals("none", project.state());
      assertNoArtifacts(project);
    }
  }

  @Test
  void unsafeOrFailedCleanupRequiresManualRecovery() throws Exception {
    Project tampered = project("tampered");
    Result refused =
        tampered.run("rehearse", Map.of("FAKE_TAMPER", "image-reference"));
    assertFailed(refused);
    assertEquals("running", tampered.state());
    assertTrue(refused.output().contains("Manual recovery required"));
    assertFalse(tampered.events().stream().anyMatch(event -> event.startsWith("docker stop")));

    Project failedStop = project("failed-stop");
    Result failure =
        failedStop.run(
            "rehearse",
            Map.of("FAKE_JAVA_FAIL", "control", "FAKE_STOP_FAIL", "1"));
    assertFailed(failure);
    assertEquals("running", failedStop.state());
    assertTrue(failure.output().contains("Manual recovery required"));
    assertNoArtifacts(failedStop);
  }

  private Project project(String name) throws Exception {
    Path root = Files.createDirectories(temporaryDirectory.resolve(name));
    Path scripts = Files.createDirectories(root.resolve("scripts"));
    Path demo = Files.createDirectories(root.resolve("target/demo"));
    Path tools = Files.createDirectories(root.resolve("fake-tools"));
    Files.createDirectories(root.resolve("config"));
    Files.createDirectories(root.resolve("caller"));
    Files.copy(Path.of("scripts/demo.sh"), scripts.resolve("demo.sh"));
    Files.writeString(root.resolve("pom.xml"), "<project/>\n");
    writeExecutable(root.resolve("mvnw"), "#!/bin/sh\nexit 0\n");
    Files.writeString(root.resolve("config/mahoraga.yml"), "database: {}\n");
    Files.writeString(root.resolve("target").resolve(JAR), "artifact");
    Files.write(demo.resolve("fake-final.json"), resource("/demo/expected-evidence.json"));
    installFakeTools(tools);
    Files.writeString(root.resolve("state"), "none");
    Files.writeString(root.resolve("mismatch"), "none");
    Files.writeString(root.resolve("generation"), "0");
    Files.writeString(root.resolve("events"), "");
    return new Project(root, tools);
  }

  private static void installFakeTools(Path tools) throws IOException {
    byte[] script = resource("/demo/fake-demo-tools.sh");
    for (String name : List.of("java", "docker", "lsof", "shasum", "sleep")) {
      Path tool = tools.resolve(name);
      Files.write(tool, script);
      assertTrue(tool.toFile().setExecutable(true));
    }
  }

  private static void writeExecutable(Path path, String content) throws IOException {
    Files.writeString(path, content);
    assertTrue(path.toFile().setExecutable(true));
  }

  private static byte[] resource(String path) throws IOException {
    try (InputStream input = DemoScriptTest.class.getResourceAsStream(path)) {
      if (input == null) {
        throw new IOException("missing resource " + path);
      }
      return input.readAllBytes();
    }
  }

  private static String expectedTranscript() throws IOException {
    return new String(resource("/demo/expected-transcript.txt"), StandardCharsets.UTF_8);
  }

  private static void assertFailed(Result result) {
    assertNotEquals(0, result.exitCode(), result.output());
    assertFalse(result.output().contains("jdbc:postgresql"));
    assertFalse(result.output().contains("POSTGRES_PASSWORD"));
  }

  private static void assertNoArtifacts(Project project) throws IOException {
    try (var files = Files.list(project.root().resolve("target/demo"))) {
      assertEquals(
          List.of("fake-final.json"),
          files.map(path -> path.getFileName().toString()).sorted().toList());
    }
  }

  private record Project(Path root, Path tools) {

    Result run(String action, Map<String, String> overrides) throws Exception {
      ProcessBuilder builder =
          new ProcessBuilder("/bin/bash", root.resolve("scripts/demo.sh").toString(), action)
              .directory(root.resolve("caller").toFile())
              .redirectErrorStream(true);
      Map<String, String> environment = builder.environment();
      environment.put("PATH", tools + ":" + environment.getOrDefault("PATH", ""));
      environment.put("FAKE_ROOT", root.toString());
      environment.put("FAKE_STATE", root.resolve("state").toString());
      environment.put("FAKE_MISMATCH", root.resolve("mismatch").toString());
      environment.put("FAKE_GENERATION", root.resolve("generation").toString());
      environment.put("FAKE_EVENTS", root.resolve("events").toString());
      environment.put("FAKE_FINAL", root.resolve("target/demo/fake-final.json").toString());
      environment.put("FAKE_JAVA_VERSION", "21");
      environment.put("FAKE_DOCKER_INFO", "up");
      environment.put("FAKE_PORT_BUSY", "0");
      environment.put("FAKE_READY", "1");
      environment.putAll(overrides);
      Process process = builder.start();
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new AssertionError("demo script did not terminate");
      }
      String output =
          new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      return new Result(process.exitValue(), output);
    }

    void setState(String state) throws IOException {
      Files.writeString(root.resolve("state"), state);
    }

    void setMismatch(String mismatch) throws IOException {
      Files.writeString(root.resolve("mismatch"), mismatch);
    }

    String state() throws IOException {
      return Files.readString(root.resolve("state")).strip();
    }

    List<String> events() throws IOException {
      return Files.readAllLines(root.resolve("events")).stream()
          .filter(line -> !line.isBlank())
          .toList();
    }

    Path evidence() {
      return root.resolve("target/demo/evidence.json");
    }

    Path transcript() {
      return root.resolve("target/demo/transcript.txt");
    }
  }

  private record Result(int exitCode, String output) {}
}
