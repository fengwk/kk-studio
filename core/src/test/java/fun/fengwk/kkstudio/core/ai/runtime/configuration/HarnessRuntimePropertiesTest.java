package fun.fengwk.kkstudio.core.ai.runtime.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

class HarnessRuntimePropertiesTest {

  /** 部署默认值保持 worker 开关、资源边界、Redis overlay 与 processor/dispatcher 参数有界。 */
  @Test
  void providesRuntimeDeploymentDefaults() {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();

    assertEquals(true, properties.isWorkersEnabled());
    assertEquals(16 * 1024 * 1024, properties.getResourceMaxBytes());
    assertEquals("kk-studio:harness:realtime:", properties.getRedisPrefix());
    assertEquals(Duration.ofSeconds(30), properties.getProcessorLeaseDuration());
    assertEquals(Duration.ofSeconds(10), properties.getProcessorHeartbeatInterval());
    assertEquals(16, properties.getThreadStepLimit());
    assertEquals(Duration.ofSeconds(1), properties.getThreadResolveFailureDelay());
    assertEquals(Duration.ofSeconds(1), properties.getModelDispatchBusyFallbackDelay());
    assertEquals(Duration.ofSeconds(1), properties.getToolPreflightFailureDelay());
    assertEquals(Duration.ofSeconds(1), properties.getToolDispatchBusyFallbackDelay());
    assertEquals(Duration.ofSeconds(30), properties.getDispatcherLeaseDuration());
    assertEquals(Duration.ofSeconds(1), properties.getDispatcherPollInterval());
    assertEquals(Duration.ofSeconds(1), properties.getDispatcherRejectionDelay());
    assertEquals(64, properties.getDispatcherMaxDispatchTasks());
    assertEquals(16, properties.getDispatcherWorkerConcurrency());
    assertEquals(64, properties.getDispatcherWorkerQueueCapacity());
    // 自动对话压缩默认值对齐上游 Pi。
    assertEquals(true, properties.isCompactionEnabled());
    assertEquals(16_384, properties.getCompactionReserveTokens());
    assertEquals(20_000, properties.getCompactionMaxRecentTokens());
  }

  /** 压缩字段可显式覆盖（属性绑定路径 kk-studio.harness.runtime.compaction-*）。 */
  @Test
  void supportsCompactionOverrides() {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    properties.setCompactionEnabled(false);
    properties.setCompactionReserveTokens(4_096);
    properties.setCompactionMaxRecentTokens(8_192);

    assertEquals(false, properties.isCompactionEnabled());
    assertEquals(4_096, properties.getCompactionReserveTokens());
    assertEquals(8_192, properties.getCompactionMaxRecentTokens());
  }

  /** 相对 workdir 在 environmentRoot 下解析，越界与绝对路径逃逸必须失败。 */
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
