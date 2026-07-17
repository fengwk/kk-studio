package fun.fengwk.kkstudio.core.harness.configuration;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/** Process-local Harness resource boundary and durable worker polling settings. */
@Data
@ConfigurationProperties(prefix = "kk-studio.harness.runtime")
public class HarnessRuntimeProperties {

  private boolean workersEnabled = true;
  private String workerId = "kk-studio-harness-" + UUID.randomUUID();
  private Duration pollInterval = Duration.ofMillis(100);
  private Path environmentRoot = Path.of(System.getProperty("user.dir", "."));
  private Path workdir = Path.of(".");

  public String requireWorkerId() {
    if (workerId == null || workerId.isBlank()) {
      throw new IllegalArgumentException("kk-studio.harness.runtime.worker-id must not be blank");
    }
    return workerId;
  }

  public Duration requirePollInterval() {
    if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
      throw new IllegalArgumentException(
          "kk-studio.harness.runtime.poll-interval must be positive");
    }
    return pollInterval;
  }

  public Path resolvedEnvironmentRoot() {
    if (environmentRoot == null) {
      throw new IllegalArgumentException(
          "kk-studio.harness.runtime.environment-root must not be null");
    }
    return environmentRoot.toAbsolutePath().normalize();
  }

  public Path resolvedWorkdir() {
    if (workdir == null) {
      throw new IllegalArgumentException("kk-studio.harness.runtime.workdir must not be null");
    }
    Path root = resolvedEnvironmentRoot();
    Path resolved = workdir.isAbsolute() ? workdir.normalize() : root.resolve(workdir).normalize();
    if (!resolved.startsWith(root)) {
      throw new IllegalArgumentException(
          "kk-studio.harness.runtime.workdir must stay within environment-root");
    }
    return resolved;
  }
}
