package fun.fengwk.kkstudio.core.ai.environment.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Environment Gateway 的部署级密钥边界：只有 {@code daemonToken} 保留在 bootstrap
 * properties（SystemSettings.environment 承载资源/消息边界与超时，见 {@code SystemSettingsTest}）。
 */
class EnvironmentGatewayPropertiesTest {

  @Test
  void requiresNonBlankDaemonToken() {
    EnvironmentGatewayProperties properties = new EnvironmentGatewayProperties();

    assertThrows(IllegalArgumentException.class, properties::requireDaemonToken);
    properties.setDaemonToken("daemon-token");
    assertEquals("daemon-token", properties.requireDaemonToken());
  }
}
