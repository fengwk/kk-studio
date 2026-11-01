package fun.fengwk.kkstudio.platform.harness.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class HarnessRuntimePropertiesTest {

  /** 纯 bootstrap 默认值包含 worker 开关与优雅关闭排空超时：默认启动 worker，排空超时 60s。 */
  @Test
  void providesBootstrapDeploymentDefaults() {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    assertTrue(properties.isWorkersEnabled());
    assertEquals(Duration.ofSeconds(60), properties.getDrainTimeout());
  }
}
