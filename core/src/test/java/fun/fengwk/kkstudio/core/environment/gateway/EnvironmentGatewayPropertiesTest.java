package fun.fengwk.kkstudio.core.environment.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Configuration bounds keep daemon attachment fail-closed and text payload limits representable.
 */
class EnvironmentGatewayPropertiesTest {

  @Test
  void requiresBoundedMessageSizeAndNonBlankDaemonToken() {
    EnvironmentGatewayProperties properties = new EnvironmentGatewayProperties();

    assertThrows(IllegalArgumentException.class, properties::requireDaemonToken);
    properties.setDaemonToken("daemon-token");
    assertEquals("daemon-token", properties.requireDaemonToken());
    properties.setMaxArtifactBytes(0);
    assertThrows(IllegalArgumentException.class, properties::requireMaxArtifactBytes);
    assertEquals(16 * 1024 * 1024, properties.requireMaxMessageBytes());

    properties.setMaxMessageBytes(0);
    assertThrows(IllegalArgumentException.class, properties::requireMaxMessageBytes);
    properties.setMaxMessageBytes((long) Integer.MAX_VALUE + 1);
    assertThrows(IllegalArgumentException.class, properties::requireMaxMessageBytes);
  }
}
