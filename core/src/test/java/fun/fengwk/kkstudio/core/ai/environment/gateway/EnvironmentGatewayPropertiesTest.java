package fun.fengwk.kkstudio.core.ai.environment.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** 配置边界让 daemon 接入安全失败且文本载荷上限可表达。 */
class EnvironmentGatewayPropertiesTest {

  @Test
  void requiresBoundedMessageSizeAndNonBlankDaemonToken() {
    EnvironmentGatewayProperties properties = new EnvironmentGatewayProperties();

    assertThrows(IllegalArgumentException.class, properties::requireDaemonToken);
    properties.setDaemonToken("daemon-token");
    assertEquals("daemon-token", properties.requireDaemonToken());
    properties.setMaxResourceBytes(0);
    assertThrows(IllegalArgumentException.class, properties::requireMaxResourceBytes);
    assertEquals(16 * 1024 * 1024, properties.requireMaxMessageBytes());

    properties.setMaxMessageBytes(0);
    assertThrows(IllegalArgumentException.class, properties::requireMaxMessageBytes);
    properties.setMaxMessageBytes((long) Integer.MAX_VALUE + 1);
    assertThrows(IllegalArgumentException.class, properties::requireMaxMessageBytes);
  }
}
