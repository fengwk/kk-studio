package fun.fengwk.kkstudio.core.ai.environment.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Environment Gateway 的部署级边界：{@code daemonToken} 与 WebSocket 单帧上限留在 bootstrap
 * properties（心跳/目录/资源预算仍由 SystemSettings.environment 承载）。
 */
class EnvironmentGatewayPropertiesTest {

  @Test
  void requiresNonBlankDaemonToken() {
    EnvironmentGatewayProperties properties = new EnvironmentGatewayProperties();

    assertThrows(IllegalArgumentException.class, properties::requireDaemonToken);
    properties.setDaemonToken("daemon-token");
    assertEquals("daemon-token", properties.requireDaemonToken());
  }

  @Test
  void defaultsAndRejectsInvalidMaxMessageBytes() {
    EnvironmentGatewayProperties properties = new EnvironmentGatewayProperties();
    assertEquals(16 * 1024 * 1024, properties.requireMaxMessageBytes());
    properties.setMaxMessageBytes(0L);
    assertThrows(IllegalArgumentException.class, properties::requireMaxMessageBytes);
    properties.setMaxMessageBytes(Integer.MAX_VALUE + 1L);
    assertThrows(IllegalArgumentException.class, properties::requireMaxMessageBytes);
    properties.setMaxMessageBytes(1024L);
    assertEquals(1024, properties.requireMaxMessageBytes());
  }
}
