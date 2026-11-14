package fun.fengwk.kkstudio.platform.environment.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.platform.daemon.server.EnvironmentDaemonServer;

/** {@link EnvironmentDaemonGateway} 产品适配契约测试。 */
class EnvironmentDaemonGatewayTest {

  private final EnvironmentDaemonServer server = mock(EnvironmentDaemonServer.class);

  /** 测试意图：验证 EnvironmentDaemonGateway 的非空依赖约束与注入实例获取。 */
  @Test
  void requiresNonNullServer() {
    assertThrows(NullPointerException.class, () -> new EnvironmentDaemonGateway(null));
    EnvironmentDaemonGateway gateway =
        assertDoesNotThrow(() -> new EnvironmentDaemonGateway(server));
    assertSame(server, gateway.server());
  }
}
