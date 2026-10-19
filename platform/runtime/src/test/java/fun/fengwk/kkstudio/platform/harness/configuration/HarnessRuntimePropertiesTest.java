package fun.fengwk.kkstudio.platform.harness.configuration;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HarnessRuntimePropertiesTest {

  /** 纯 bootstrap 默认值只包含 worker 开关：Backend 不持有默认 cwd、资源目录或 Environment 根。 */
  @Test
  void providesBootstrapDeploymentDefaults() {
    assertTrue(new HarnessRuntimeProperties().isWorkersEnabled());
  }
}
