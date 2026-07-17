package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Configuration contracts keep a production Daemon from attaching without a gateway credential. */
class DaemonConfigTest {

  private static final String[] PROPERTY_NAMES = {
    "kkstudio.daemon.gateway-uri",
    "kkstudio.daemon.environment-id",
    "kkstudio.daemon.id",
    "kkstudio.daemon.gateway-token",
    "kkstudio.daemon.heartbeat",
    "kkstudio.daemon.reconnect-initial",
    "kkstudio.daemon.reconnect-max",
    "kkstudio.daemon.tool-timeout"
  };

  private final Map<String, String> originalProperties = new LinkedHashMap<>();

  @BeforeEach
  void captureProperties() {
    for (String name : PROPERTY_NAMES) {
      originalProperties.put(name, System.getProperty(name));
    }
  }

  /**
   * All explicit connection inputs, including the shared gateway token, are mandatory and bounded.
   */
  @Test
  void validatesExplicitConnectionConfiguration() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            config(
                URI.create("http://localhost/gateway"),
                "environment",
                "daemon",
                Duration.ofSeconds(1),
                Duration.ZERO,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                "token"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            config(
                URI.create("ws://localhost/gateway"),
                "environment",
                "daemon",
                Duration.ofSeconds(1),
                Duration.ZERO,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                " "));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            config(
                URI.create("ws://localhost/gateway"),
                "environment",
                "daemon",
                Duration.ZERO,
                Duration.ZERO,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                "token"));
  }

  /**
   * System-property startup reads an explicit token and keeps the documented duration overrides.
   */
  @Test
  void readsRequiredSystemProperties() {
    set("kkstudio.daemon.gateway-uri", "wss://gateway.example/daemon");
    set("kkstudio.daemon.environment-id", "42");
    set("kkstudio.daemon.id", "daemon-a");
    set("kkstudio.daemon.gateway-token", "secret");
    set("kkstudio.daemon.heartbeat", "PT2S");
    set("kkstudio.daemon.reconnect-initial", "PT0S");
    set("kkstudio.daemon.reconnect-max", "PT3S");
    set("kkstudio.daemon.tool-timeout", "PT4S");

    DaemonConfig config = DaemonConfig.fromSystemProperties();

    assertEquals(URI.create("wss://gateway.example/daemon"), config.gatewayUri());
    assertEquals("42", config.environmentId());
    assertEquals("daemon-a", config.daemonId());
    assertEquals("secret", config.gatewayToken());
    assertEquals(Duration.ofSeconds(2), config.heartbeatInterval());
    assertEquals(Duration.ZERO, config.initialReconnectDelay());
    assertEquals(Duration.ofSeconds(3), config.maxReconnectDelay());
    assertEquals(Duration.ofSeconds(4), config.defaultToolTimeout());
  }

  /** Startup fails closed when the deployment omits the secret required by the gateway. */
  @Test
  void rejectsMissingGatewayTokenSystemProperty() {
    set("kkstudio.daemon.gateway-uri", "ws://gateway.example/daemon");
    set("kkstudio.daemon.environment-id", "42");
    clear("kkstudio.daemon.gateway-token");

    assertThrows(IllegalArgumentException.class, DaemonConfig::fromSystemProperties);
  }

  @AfterEach
  void restoreProperties() {
    for (String name : PROPERTY_NAMES) {
      String original = originalProperties.get(name);
      if (original == null) {
        System.clearProperty(name);
      } else {
        System.setProperty(name, original);
      }
    }
  }

  private DaemonConfig config(
      URI gatewayUri,
      String environmentId,
      String daemonId,
      Duration heartbeatInterval,
      Duration initialReconnectDelay,
      Duration maxReconnectDelay,
      Duration defaultToolTimeout,
      String gatewayToken) {
    return new DaemonConfig(
        gatewayUri,
        environmentId,
        daemonId,
        heartbeatInterval,
        initialReconnectDelay,
        maxReconnectDelay,
        defaultToolTimeout,
        gatewayToken);
  }

  private void set(String name, String value) {
    System.setProperty(name, value);
  }

  private void clear(String name) {
    System.clearProperty(name);
  }
}
