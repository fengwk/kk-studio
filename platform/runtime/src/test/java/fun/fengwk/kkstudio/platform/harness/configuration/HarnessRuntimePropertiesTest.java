package fun.fengwk.kkstudio.platform.harness.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

class HarnessRuntimePropertiesTest {

  /** 纯 bootstrap 默认值只包含 worker 开关与 resourceRoot：Backend 不再持有默认 cwd 或 Environment 根。 */
  @Test
  void providesBootstrapDeploymentDefaults() {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();

    assertEquals(true, properties.isWorkersEnabled());
    assertEquals(
        Path.of(System.getProperty("user.dir", "."), ".kkstudio", "resources")
            .toAbsolutePath()
            .normalize(),
        properties.resolvedResourceRoot());
  }

  /** resourceRoot 显式缺失是部署错误，必须确定性拒绝。 */
  @Test
  void rejectsNullResourceRoot() {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();
    properties.setResourceRoot(null);

    assertThrows(IllegalArgumentException.class, properties::resolvedResourceRoot);
  }
}
