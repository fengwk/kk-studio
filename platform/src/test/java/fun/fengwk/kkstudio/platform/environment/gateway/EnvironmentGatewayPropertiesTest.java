package fun.fengwk.kkstudio.platform.environment.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.time.Duration;

/** Environment Gateway 的部署级传输边界。 */
class EnvironmentGatewayPropertiesTest {

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

  /** 出站队列、字节与发送超时都是部署级正数边界，并提供可直接用于 WebSocket 的默认值。 */
  @Test
  void defaultsAndValidatesOutboundTransportBounds() {
    EnvironmentGatewayProperties properties = new EnvironmentGatewayProperties();
    assertEquals(256, properties.requireQueueCapacity());
    assertEquals(16 * 1024 * 1024, properties.requireMaxBytes());
    assertEquals(10_000, properties.requireSendTimeoutMillis());

    properties.setQueueCapacity(0);
    assertThrows(IllegalArgumentException.class, properties::requireQueueCapacity);
    properties.setMaxBytes(Integer.MAX_VALUE + 1L);
    assertThrows(IllegalArgumentException.class, properties::requireMaxBytes);
    properties.setSendTimeout(Duration.ZERO);
    assertThrows(IllegalArgumentException.class, properties::requireSendTimeoutMillis);

    properties.setQueueCapacity(8);
    properties.setMaxBytes(1024);
    properties.setSendTimeout(Duration.ofMillis(250));
    assertEquals(8, properties.requireQueueCapacity());
    assertEquals(1024, properties.requireMaxBytes());
    assertEquals(250, properties.requireSendTimeoutMillis());
  }
}
