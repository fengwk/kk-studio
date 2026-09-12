package fun.fengwk.kkstudio.platform.environment.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;

/** {@link EnvironmentDaemonGateway} 产品适配契约测试。 */
class EnvironmentDaemonGatewayTest {

  private final EnvironmentDaemonServer server = mock(EnvironmentDaemonServer.class);
  private final EnvironmentRegistry registry = mock(EnvironmentRegistry.class);

  /** 测试意图：验证 EnvironmentDaemonGateway 的非空依赖约束与注入实例获取。 */
  @Test
  void requiresNonNullDependencies() {
    assertThrows(NullPointerException.class, () -> new EnvironmentDaemonGateway(null, registry));
    assertThrows(NullPointerException.class, () -> new EnvironmentDaemonGateway(server, null));
    EnvironmentDaemonGateway gateway =
        assertDoesNotThrow(() -> new EnvironmentDaemonGateway(server, registry));
    assertSame(server, gateway.server());
    assertSame(registry, gateway.environmentRegistry());
  }
}
