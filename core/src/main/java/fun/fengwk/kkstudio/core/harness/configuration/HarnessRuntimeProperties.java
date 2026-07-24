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

  /** 自动重试到期与进程恢复扫描间隔。 */
  private Duration threadRecoveryInterval = Duration.ofSeconds(1);

  /** 单次恢复扫描最多 kick 的 Thread 数。 */
  private int threadRecoveryBatchSize = 100;

  /** Thread reconcile processor lease；该 lease 只保护单次 activation，不改变 stop epoch。 */
  private Duration threadProcessorLeaseDuration = Duration.ofSeconds(30);

  /** 本进程 Thread activation 的有界并发度。 */
  private int threadWorkerConcurrency = 8;

  /** 单次 Thread reconcile activation 最多推进的 durable step 数。 */
  private int threadReconcilerMaxSteps = 16;

  /** Model worker 持有 Invocation 的 deployment lease 时长。 */
  private Duration modelWorkerLeaseDuration = Duration.ofSeconds(30);

  /** Model worker lease heartbeat 间隔。 */
  private Duration modelWorkerHeartbeatInterval = Duration.ofSeconds(10);

  /** Model worker 持久化真实 Provider activity 的最小 cadence。 */
  private Duration modelWorkerActivityFlushInterval = Duration.ofMillis(100);

  /** Model Invocation PostgreSQL 恢复扫描间隔。 */
  private Duration modelRecoveryInterval = Duration.ofSeconds(1);

  /** 单次 Model Invocation 恢复扫描最多领取的任务数。 */
  private int modelRecoveryBatchSize = 100;

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
