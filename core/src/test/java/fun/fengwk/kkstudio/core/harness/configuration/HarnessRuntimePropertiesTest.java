package fun.fengwk.kkstudio.core.harness.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

class HarnessRuntimePropertiesTest {

  /** Deployment defaults keep the durable Model worker's leases and recovery polling bounded. */
  @Test
  void providesModelWorkerDeploymentDefaults() {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();

    assertEquals(Duration.ofSeconds(30), properties.getModelWorkerLeaseDuration());
    assertEquals(Duration.ofSeconds(10), properties.getModelWorkerHeartbeatInterval());
    assertEquals(Duration.ofMillis(100), properties.getModelWorkerActivityFlushInterval());
    assertEquals(Duration.ofSeconds(1), properties.getModelRecoveryInterval());
    assertEquals(100, properties.getModelRecoveryBatchSize());
  }

  /** Relative workdirs resolve under environmentRoot while traversal and absolute escapes fail. */
  @Test
  void enforcesEnvironmentRootBoundary() {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    properties.setEnvironmentRoot(Path.of("/tmp/harness-environment"));
    properties.setWorkdir(Path.of("repository/module"));

    assertEquals(
        Path.of("/tmp/harness-environment/repository/module"), properties.resolvedWorkdir());

    properties.setWorkdir(Path.of("../escape"));
    assertThrows(IllegalArgumentException.class, properties::resolvedWorkdir);
    properties.setWorkdir(Path.of("/tmp/other"));
    assertThrows(IllegalArgumentException.class, properties::resolvedWorkdir);
  }
}
