package dev.mahoraga.memory.commands;

import dev.mahoraga.memory.MahoragaApplication;
import dev.mahoraga.memory.MahoragaConfiguration;
import dev.mahoraga.memory.contract.CanonicalEncoding;
import dev.mahoraga.memory.demo.DemoArmEvidence;
import dev.mahoraga.memory.demo.DemoEvidence;
import dev.mahoraga.memory.demo.DemoRunner;
import dev.mahoraga.memory.planning.SteeringArmEvidence.ArmMode;
import io.dropwizard.core.cli.Cli;
import io.dropwizard.core.cli.EnvironmentCommand;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import net.sourceforge.argparse4j.inf.Subparsers;
import org.eclipse.jetty.util.component.ContainerLifeCycle;

public final class DemoCommand extends EnvironmentCommand<MahoragaConfiguration> {
  private static final String ARM = "arm";
  private static final String COMPARE = "compare";
  private static final String DATABASE_PATH = "/mahoraga_demo";
  private final MahoragaApplication application;
  private final Path projectRoot;
  private final Supplier<String> buildFingerprint;

  public DemoCommand(MahoragaApplication application) {
    this(
        application,
        Path.of("").toAbsolutePath().normalize(),
        DemoCommand::runningJarFingerprint);
  }

  DemoCommand(
      MahoragaApplication application, Path projectRoot, Supplier<String> buildFingerprint) {
    super(application, "demo", "Runs the guarded synthetic MVP demonstration");
    this.application = Objects.requireNonNull(application, "application");
    this.projectRoot = Objects.requireNonNull(projectRoot, "projectRoot");
    this.buildFingerprint = Objects.requireNonNull(buildFingerprint, "buildFingerprint");
  }

  @Override
  public void configure(Subparser parser) {
    parser.addArgument("file").help("application configuration file");
    Subparsers actions = parser.addSubparsers().dest("demoAction");
    configureArm(actions.addParser(ARM));
    configureCompare(actions.addParser(COMPARE));
  }

  private static void configureArm(Subparser arm) {
    arm.addArgument("--synthetic-demo")
        .dest("syntheticDemo")
        .action(Arguments.storeTrue())
        .required(true);
    arm.addArgument("--mode").dest("armMode").choices("memory-off", "memory-on").required(true);
    arm.addArgument("--output").dest("output").required(true);
  }

  private static void configureCompare(Subparser compare) {
    compare.addArgument("--control").dest("control").required(true);
    compare.addArgument("--memory").dest("memory").required(true);
    compare.addArgument("--output").dest("output").required(true);
  }

  @Override
  public void run(Bootstrap<?> bootstrap, Namespace namespace) throws Exception {
    String action = namespace.getString("demoAction");
    if (action == null) {
      throw new DemoCommandException("demo requires arm or compare mode");
    }
    switch (action) {
      case COMPARE -> compare(namespace);
      case ARM -> {
        requireSyntheticDemo(namespace);
        requireSafeOutput(namespace.getString("output"));
        super.run(bootstrap, namespace);
      }
      default -> throw new DemoCommandException("demo requires arm or compare mode");
    }
  }

  @Override
  protected void run(
      Bootstrap<MahoragaConfiguration> bootstrap,
      Namespace namespace,
      MahoragaConfiguration configuration)
      throws Exception {
    requireSafeDatabase(configuration.getDatabase().getUrl());
    super.run(bootstrap, namespace, configuration);
  }

  @Override
  protected void run(
      Environment environment,
      Namespace namespace,
      MahoragaConfiguration configuration)
      throws Exception {
    DemoArmEvidence evidence = runArm(environment, namespace);
    DemoEvidence.writeArm(requireSafeOutput(namespace.getString("output")), evidence);
  }

  private DemoArmEvidence runArm(Environment environment, Namespace namespace) throws Exception {
    ContainerLifeCycle lifecycle = new ContainerLifeCycle();
    Throwable failure = null;
    try {
      environment.lifecycle().attach(lifecycle);
      requireSafeOutput(namespace.getString("output"));
      lifecycle.start();
      return application
          .getInjector()
          .getInstance(DemoRunner.class)
          .runArm(armMode(namespace), buildFingerprint.get());
    } catch (Exception | Error armFailure) {
      failure = armFailure;
      throw armFailure;
    } finally {
      cleanupLifecycle(lifecycle, failure);
    }
  }

  private static void cleanupLifecycle(ContainerLifeCycle lifecycle, Throwable failure)
      throws Exception {
    Exception cleanupFailure = null;
    try {
      lifecycle.stop();
    } catch (Exception stopFailure) {
      cleanupFailure = stopFailure;
    }
    try {
      lifecycle.destroy();
    } catch (RuntimeException destroyFailure) {
      if (cleanupFailure == null) {
        cleanupFailure = destroyFailure;
      } else {
        cleanupFailure.addSuppressed(destroyFailure);
      }
    }
    if (cleanupFailure != null) {
      if (failure == null) {
        throw cleanupFailure;
      }
      failure.addSuppressed(cleanupFailure);
    }
  }

  private void compare(Namespace namespace) throws IOException {
    Path output = requireSafeOutput(namespace.getString("output"));
    DemoArmEvidence control = DemoEvidence.readArm(Path.of(namespace.getString("control")));
    DemoArmEvidence memory = DemoEvidence.readArm(Path.of(namespace.getString("memory")));
    DemoEvidence evidence = DemoEvidence.compare(control, memory, buildFingerprint.get());
    DemoEvidence.write(output, evidence);
  }

  private static void requireSyntheticDemo(Namespace namespace) {
    if (!Boolean.TRUE.equals(namespace.getBoolean("syntheticDemo"))) {
      throw new DemoCommandException("demo arm requires explicit --synthetic-demo");
    }
  }

  private static ArmMode armMode(Namespace namespace) {
    return switch (namespace.getString("armMode")) {
      case "memory-off" -> ArmMode.CONTROL;
      case "memory-on" -> ArmMode.MEMORY;
      default -> throw new DemoCommandException("demo arm requires a valid memory mode");
    };
  }

  private static void requireSafeDatabase(String jdbcUrl) {
    URI database = parseDatabaseUri(jdbcUrl);
    String authority = database.getRawAuthority();
    boolean hasSafeShape =
        "postgresql".equals(database.getScheme())
            && authority != null
            && !authority.contains(",")
            && database.getRawUserInfo() == null
            && database.getHost() != null
            && database.getPort() != 0
            && DATABASE_PATH.equals(database.getRawPath())
            && database.getRawFragment() == null;
    if (!hasSafeShape || !resolvesOnlyToLoopback(database.getHost())) {
      throw new DemoCommandException("demo arm requires the guarded local demo database");
    }
  }

  private static URI parseDatabaseUri(String jdbcUrl) {
    if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
      throw new DemoCommandException("demo arm requires the guarded local demo database");
    }
    try {
      return URI.create(jdbcUrl.substring("jdbc:".length()));
    } catch (IllegalArgumentException invalidUrl) {
      throw new DemoCommandException("demo arm requires the guarded local demo database");
    }
  }

  private static boolean resolvesOnlyToLoopback(String host) {
    try {
      InetAddress[] addresses = InetAddress.getAllByName(host);
      return addresses.length > 0
          && Arrays.stream(addresses).allMatch(InetAddress::isLoopbackAddress);
    } catch (UnknownHostException unresolved) {
      return false;
    }
  }

  private Path requireSafeOutput(String value) {
    try {
      Path root = projectRoot.toRealPath();
      Path pom = root.resolve("pom.xml");
      if (Files.isSymbolicLink(pom) || !Files.isRegularFile(pom, LinkOption.NOFOLLOW_LINKS)) {
        throw unsafeOutput();
      }
      Path target = requireDirectDirectory(root, "target", false);
      Path demo = requireDirectDirectory(target, "demo", true);
      Path supplied = Path.of(value);
      Path output =
          supplied.isAbsolute() ? supplied.normalize() : root.resolve(supplied).normalize();
      Path outputParent = output.getParent();
      boolean isUnsafeFile =
          Files.exists(output, LinkOption.NOFOLLOW_LINKS)
              && (Files.isSymbolicLink(output)
                  || !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS));
      if (outputParent == null || !demo.equals(outputParent.toRealPath()) || isUnsafeFile) {
        throw unsafeOutput();
      }
      return demo.resolve(output.getFileName());
    } catch (IOException | InvalidPathException invalidOutput) {
      throw new DemoCommandException("demo output must be a direct file under target/demo");
    }
  }

  private static Path requireDirectDirectory(Path parent, String name, boolean canCreate)
      throws IOException {
    Path directory = parent.resolve(name);
    if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
      if (!canCreate) {
        throw unsafeOutput();
      }
      Files.createDirectory(directory);
    }
    if (Files.isSymbolicLink(directory)
        || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw unsafeOutput();
    }
    Path realDirectory = directory.toRealPath();
    if (!parent.equals(realDirectory.getParent())) {
      throw unsafeOutput();
    }
    return realDirectory;
  }

  private static DemoCommandException unsafeOutput() {
    return new DemoCommandException("demo output must be a direct file under target/demo");
  }

  private static String runningJarFingerprint() {
    try {
      var codeSource = DemoCommand.class.getProtectionDomain().getCodeSource();
      if (codeSource == null) {
        throw new IOException("application artifact is unavailable");
      }
      Path artifact = Path.of(codeSource.getLocation().toURI());
      if (!Files.isRegularFile(artifact)) {
        throw new IOException("application artifact is not a file");
      }
      return CanonicalEncoding.sha256Hex(Files.readAllBytes(artifact));
    } catch (IOException | URISyntaxException fingerprintFailure) {
      throw new IllegalStateException("could not fingerprint the packaged application");
    }
  }

  @Override
  public void onError(Cli cli, Namespace namespace, Throwable failure) {
    String message =
        failure instanceof DemoCommandException
            ? failure.getMessage()
            : "demo command failed without publishing evidence";
    cli.getStdErr().println(message);
  }

  private static final class DemoCommandException extends IllegalArgumentException {

    private DemoCommandException(String message) {
      super(message);
    }
  }
}
