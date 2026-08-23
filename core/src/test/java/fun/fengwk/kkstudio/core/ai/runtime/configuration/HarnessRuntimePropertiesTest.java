package fun.fengwk.kkstudio.core.ai.runtime.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

class HarnessRuntimePropertiesTest {

  /** 纯 bootstrap 默认值只包含 worker 开关与有界的沙箱根目录/工作目录。 */
  @Test
  void providesBootstrapDeploymentDefaults() {
    HarnessRuntimeProperties properties = new HarnessRuntimeProperties();

    assertEquals(true, properties.isWorkersEnabled());
    assertEquals(
        Path.of(System.getProperty("user.dir", ".")).toAbsolutePath(),
        properties.resolvedEnvironmentRoot());
    assertEquals(
        Path.of(System.getProperty("user.dir", ".")).toAbsolutePath(),
        properties.resolvedWorkdir());
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
