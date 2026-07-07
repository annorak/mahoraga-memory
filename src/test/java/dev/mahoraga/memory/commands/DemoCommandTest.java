package dev.mahoraga.memory.commands;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.mahoraga.memory.MahoragaApplication;
import dev.mahoraga.memory.MahoragaConfiguration;
import dev.mahoraga.memory.demo.DemoArmEvidence;
import dev.mahoraga.memory.demo.DemoArmEvidence.AmbiguityProof;
import dev.mahoraga.memory.demo.DemoArmEvidence.CompletionProbeResult;
import dev.mahoraga.memory.demo.DemoArmEvidence.ConflictProbeResult;
import dev.mahoraga.memory.demo.DemoArmEvidence.MemoryReportSummary;
import dev.mahoraga.memory.demo.DemoArmEvidence.StableIdentityProof;
import dev.mahoraga.memory.demo.DemoArmEvidence.StatelessReportSummary;
import dev.mahoraga.memory.demo.DemoEvidence;
import dev.mahoraga.memory.ingest.IngestResult;
import dev.mahoraga.memory.planning.SteeringArmEvidence.ArmMode;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.db.DataSourceFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import net.sourceforge.argparse4j.inf.Namespace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pure command, safety, strict-codec, and atomic-publication coverage. */
class DemoCommandTest {

  private static final String BUILD_FINGERPRINT = "a".repeat(64);
  private static final String DIGEST = "b".repeat(64);

  @TempDir Path temporaryDirectory;

  @Test
  void compareIsDatabaseFreeAndPublishesStableEvidenceAtomically() throws Exception {
    Path root = projectRoot("compare");
    Path controlFile = temporaryDirectory.resolve("control.json");
    Path memoryFile = temporaryDirectory.resolve("memory.json");
    Path output = root.resolve("target/demo/evidence.json");
    DemoEvidence.writeArm(controlFile, arm(ArmMode.CONTROL, 3));
    DemoEvidence.writeArm(memoryFile, arm(ArmMode.MEMORY, 1));
    DemoCommand command = command(root);

    command.run(null, compareNamespace(controlFile, memoryFile, output));
    byte[] first = Files.readAllBytes(output);
    command.run(null, compareNamespace(controlFile, memoryFile, output));

    DemoEvidence evidence = DemoEvidence.read(output);
    assertEquals(3, evidence.controlActionsBeforeRegression());
    assertEquals(1, evidence.memoryActionsBeforeRegression());
    assertArrayEquals(first, Files.readAllBytes(output));
    assertEquals(expectedEvidenceJson(), Files.readString(output));
    try (Stream<Path> files = Files.list(output.getParent())) {
      assertFalse(files.anyMatch(path -> path.toString().endsWith(".tmp")));
    }
  }

  @Test
  void strictArmCodecRejectsUnknownMissingAndTrailingEvidence() throws Exception {
    Path input = temporaryDirectory.resolve("strict-arm.json");
    ObjectMapper mapper = new ObjectMapper();
    String canonical = DemoEvidence.canonicalJson(arm(ArmMode.CONTROL, 3));
    ObjectNode json =
        (ObjectNode) mapper.readTree(canonical);

    json.put("unknown", true);
    Files.writeString(input, mapper.writeValueAsString(json));
    assertThrows(IOException.class, () -> DemoEvidence.readArm(input));

    json = (ObjectNode) mapper.readTree(canonical);
    json.remove("build_fingerprint");
    Files.writeString(input, mapper.writeValueAsString(json));
    assertThrows(IOException.class, () -> DemoEvidence.readArm(input));

    Files.writeString(input, canonical + " trailing", StandardCharsets.UTF_8);
    assertThrows(IOException.class, () -> DemoEvidence.readArm(input));
  }

  @Test
  void strictArmCodecRejectsDuplicateNullPrimitiveAndDeepEvidence() throws Exception {
    Path input = temporaryDirectory.resolve("strict-structure.json");
    ObjectMapper mapper = new ObjectMapper();
    String canonical = DemoEvidence.canonicalJson(arm(ArmMode.CONTROL, 3));

    Files.writeString(input, canonical.replaceFirst("\\{", "{\"evidence_schema_version\":1,"));
    assertThrows(IOException.class, () -> DemoEvidence.readArm(input));

    ObjectNode json = (ObjectNode) mapper.readTree(canonical);
    json.putNull("rollback_probe_has_partial_state");
    Files.writeString(input, mapper.writeValueAsString(json));
    assertThrows(IOException.class, () -> DemoEvidence.readArm(input));

    String nested = "{\"nested\":" + "[".repeat(31) + "0" + "]".repeat(31) + "}";
    Files.writeString(input, nested);
    assertThrows(IOException.class, () -> DemoEvidence.readArm(input));

    Files.write(input, new byte[1024 * 1024 + 1]);
    assertThrows(IOException.class, () -> DemoEvidence.readArm(input));
  }

  @Test
  void comparisonRejectsSameModeMetricAndMappingTampering() {
    DemoArmEvidence control = arm(ArmMode.CONTROL, 3);
    DemoArmEvidence memory = arm(ArmMode.MEMORY, 1);
    assertThrows(
        IllegalArgumentException.class,
        () -> DemoEvidence.compare(control, control, BUILD_FINGERPRINT));
    assertThrows(
        IllegalArgumentException.class,
        () -> DemoEvidence.compare(control, copy(memory, memory.executedOrder(),
            memory.candidateSourceEventIds(), 2, memory.e2FactSetDigest()), BUILD_FINGERPRINT));
    Map<String, List<String>> changed = new LinkedHashMap<>(memory.candidateSourceEventIds());
    changed.put("T-C", List.of("different-event"));
    assertThrows(
        IllegalArgumentException.class,
        () -> DemoEvidence.compare(control, copy(memory, memory.executedOrder(), changed, 1,
            memory.e2FactSetDigest()), BUILD_FINGERPRINT));
  }

  @Test
  void comparisonRejectsDigestPartialAndBuildTampering() {
    DemoArmEvidence control = arm(ArmMode.CONTROL, 3);
    DemoArmEvidence memory = arm(ArmMode.MEMORY, 1);
    assertThrows(
        IllegalArgumentException.class,
        () -> DemoEvidence.compare(control, copy(memory, memory.executedOrder(),
            memory.candidateSourceEventIds(), 1, "c".repeat(64)), BUILD_FINGERPRINT));
    List<String> partialOrder = List.of("T-C", "T-A");
    Map<String, List<String>> partialEvents = new LinkedHashMap<>();
    partialOrder.forEach(id -> partialEvents.put(id, memory.candidateSourceEventIds().get(id)));
    assertThrows(
        IllegalArgumentException.class,
        () -> DemoEvidence.compare(control,
            copy(memory, partialOrder, partialEvents, 1, memory.e2FactSetDigest()),
            BUILD_FINGERPRINT));
    assertThrows(
        IllegalArgumentException.class,
        () -> DemoEvidence.compare(control, memory, "c".repeat(64)));
  }

  @Test
  void armRequiresExplicitFlagAndSafeDirectOutput() throws Exception {
    Path root = projectRoot("arm-output");
    DemoCommand command = command(root);

    assertThrows(
        IllegalArgumentException.class,
        () -> command.run(null, armNamespace(false, root.resolve("target/demo/arm.json"))));
    assertThrows(
        IllegalArgumentException.class,
        () -> command.run(null, armNamespace(true, root.resolve("target/outside.json"))));

    Path outside = temporaryDirectory.resolve("outside.json");
    Files.writeString(outside, "outside");
    Path link = root.resolve("target/demo/link.json");
    Files.createSymbolicLink(link, outside);
    assertThrows(
        IllegalArgumentException.class,
        () -> command.run(null, armNamespace(true, link)));
  }

  @Test
  void databaseGuardRejectsRemoteAlternateMultiHostAndCredentialUrls() throws Exception {
    DemoCommand command = command(projectRoot("database"));
    Namespace namespace = armNamespace(true, temporaryDirectory.resolve("unused"));
    for (String url :
        List.of(
            "jdbc:postgresql://192.0.2.1:5432/mahoraga_demo",
            "jdbc:postgresql://127.0.0.1:5432/not_demo",
            "jdbc:postgresql://127.0.0.1:5432",
            "jdbc:postgresql://127.0.0.1:0/mahoraga_demo",
            "jdbc:postgresql://127.0.0.1:5432,localhost:5432/mahoraga_demo",
            "jdbc:postgresql://user:secret@127.0.0.1:5432/mahoraga_demo")) {
      IllegalArgumentException failure =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  command.run(
                      (Bootstrap<MahoragaConfiguration>) null,
                      namespace,
                      configuration(url)));
      assertFalse(failure.getMessage().contains(url));
      assertFalse(failure.getMessage().contains("secret"));
    }
  }

  private Path projectRoot(String name) throws IOException {
    Path root = Files.createDirectory(temporaryDirectory.resolve(name));
    Files.writeString(root.resolve("pom.xml"), "<project/>");
    Files.createDirectory(root.resolve("target"));
    Files.createDirectory(root.resolve("target/demo"));
    return root;
  }

  private static DemoCommand command(Path root) {
    return new DemoCommand(new MahoragaApplication(), root, () -> BUILD_FINGERPRINT);
  }

  private static String expectedEvidenceJson() throws IOException {
    try (InputStream input =
        DemoCommandTest.class.getResourceAsStream("/demo/expected-evidence.json")) {
      if (input == null) {
        throw new IOException("missing expected demo evidence");
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8).stripTrailing();
    }
  }

  private static Namespace armNamespace(boolean syntheticDemo, Path output) {
    return new Namespace(
        Map.of(
            "demoAction", "arm",
            "syntheticDemo", syntheticDemo,
            "armMode", "memory-off",
            "output", output.toString()));
  }

  private static Namespace compareNamespace(Path control, Path memory, Path output) {
    return new Namespace(
        Map.of(
            "demoAction", "compare",
            "control", control.toString(),
            "memory", memory.toString(),
            "output", output.toString()));
  }

  private static MahoragaConfiguration configuration(String url) {
    DataSourceFactory database = new DataSourceFactory();
    database.setUrl(url);
    MahoragaConfiguration configuration = new MahoragaConfiguration();
    configuration.setDatabase(database);
    return configuration;
  }

  private static DemoArmEvidence arm(ArmMode mode, int metric) {
    List<String> candidates = List.of("T-A", "T-B", "T-C");
    List<String> order =
        mode == ArmMode.CONTROL ? candidates : List.of("T-C", "T-A", "T-B");
    return new DemoArmEvidence(
        1,
        BUILD_FINGERPRINT,
        mode,
        DIGEST,
        candidates,
        order,
        candidateEvents(),
        metric,
        DIGEST,
        true,
        DIGEST,
        DIGEST,
        DIGEST,
        new StableIdentityProof(true, true),
        new AmbiguityProof("AMBIGUOUS", 0),
        new StatelessReportSummary(3, 1, 1),
        new MemoryReportSummary(1, 1, 1, 1, 1, 1),
        DIGEST,
        IngestResult.NO_OP,
        ConflictProbeResult.EVENT_CONTENT_REJECTED,
        CompletionProbeResult.UNFINALIZED_REPORT_BLOCKED,
        true,
        false);
  }

  private static Map<String, List<String>> candidateEvents() {
    Map<String, List<String>> events = new LinkedHashMap<>();
    events.put("T-A", List.of("event-a"));
    events.put("T-B", List.of("event-b"));
    events.put("T-C", List.of("event-c"));
    return events;
  }

  private static DemoArmEvidence copy(
      DemoArmEvidence base, List<String> order, Map<String, List<String>> events,
      int metric, String e2Digest) {
    return new DemoArmEvidence(
        base.evidenceSchemaVersion(), base.buildFingerprint(), base.armMode(),
        base.candidateInputDigest(), base.candidateIds(), order, events, metric,
        base.plannerBoundaryHash(), base.hasZeroE2EventsAtPlanning(), base.e1SemanticDigest(),
        e2Digest, base.memoryReportDigest(), base.stableIdentityProof(), base.ambiguityProof(),
        base.statelessReportSummary(), base.memoryReportSummary(), base.statelessReportDigest(),
        base.duplicateRetryResult(), base.conflictingDuplicateResult(),
        base.missingCompletionResult(), base.shuffleDigestEquality(),
        base.rollbackProbeHasPartialState());
  }

}
