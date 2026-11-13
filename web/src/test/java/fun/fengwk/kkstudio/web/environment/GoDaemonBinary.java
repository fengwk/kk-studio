package fun.fengwk.kkstudio.web.environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Builds and inspects the production Go daemon for process-level integration tests. */
public final class GoDaemonBinary {

  private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(120);
  private static final String REQUIRED_GO_VERSION = "go1.27.4";

  private GoDaemonBinary() {}

  public static Path build(Path repositoryRoot, Path output) throws IOException {
    String configured = System.getProperty("go.bin");
    if (configured == null || configured.isBlank()) {
      configured = System.getenv("GO_BIN");
    }
    if (configured == null || configured.isBlank()) {
      configured = "go";
    }
    Path go = Path.of(configured);
    String version = run(go.toString(), "version").trim();
    if (!version.startsWith("go version " + REQUIRED_GO_VERSION + " ")) {
      throw new IllegalStateException(
          "Go " + REQUIRED_GO_VERSION + " is required; found: " + version);
    }
    Files.createDirectories(output.toAbsolutePath().getParent());
    ProcessBuilder builder =
        new ProcessBuilder(
            go.toString(), "build", "-o", output.toString(), "./cmd/kk-studio-daemon");
    builder.directory(repositoryRoot.resolve("daemon-go").toFile());
    builder.environment().put("CGO_ENABLED", "0");
    Path log = Files.createTempFile(output.toAbsolutePath().getParent(), "go-build-", ".log");
    builder.redirectErrorStream(true).redirectOutput(log.toFile());
    Process process = builder.start();
    try {
      if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("Go daemon build timed out after " + COMMAND_TIMEOUT);
      }
      if (process.exitValue() != 0) {
        throw new IllegalStateException("Go daemon build failed: " + Files.readString(log));
      }
      return output;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while building Go daemon", interrupted);
    } finally {
      Files.deleteIfExists(log);
    }
  }

  public static String version(Path binary) throws IOException {
    return run(binary.toString(), "--version").trim().replaceFirst("^kk-studio-daemon\\s+", "");
  }

  private static String run(String... command) throws IOException {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = readProcess(process);
    if (process.exitValue() != 0) {
      throw new IllegalStateException(String.join(" ", command) + " failed: " + output);
    }
    return output;
  }

  private static String readProcess(Process process) throws IOException {
    try {
      if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        throw new IllegalStateException("command timed out after " + COMMAND_TIMEOUT);
      }
      return new String(process.getInputStream().readAllBytes());
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while running Go command", interrupted);
    }
  }
}
