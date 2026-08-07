package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 配置契约用于阻止生产 Daemon 在缺少身份或 gateway 凭证时接入。 */
class DaemonConfigTest {

  private static final String[] PROPERTY_NAMES = {
    "kkstudio.daemon.gateway-uri",
    "kkstudio.daemon.environment-name",
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
      System.clearProperty(name);
    }
  }

  /** 所有显式连接输入，包括共享的 gateway token，都是必填且有界的。 */
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
                "token",
                List.of()));
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
                " ",
                List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            config(
                URI.create("ws://localhost/gateway"),
                " ",
                "daemon",
                Duration.ofSeconds(1),
                Duration.ZERO,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                "token",
                List.of()));
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
                "token",
                List.of()));
  }

  /** CLI 对 environment name 和 skill dirs 是权威的；连接配置可以回退到 properties。 */
  @Test
  void readsCliArgumentsWithPropertyFallback(@TempDir Path skillDir) {
    set("kkstudio.daemon.gateway-uri", "wss://gateway.example/daemon");
    set("kkstudio.daemon.gateway-token", "secret");
    set("kkstudio.daemon.heartbeat", "PT2S");
    set("kkstudio.daemon.reconnect-initial", "PT0S");
    set("kkstudio.daemon.reconnect-max", "PT3S");
    set("kkstudio.daemon.tool-timeout", "PT4S");

    DaemonConfig config =
        DaemonConfig.fromArgs(
            new String[] {
              "--environment-name",
              "local-dev",
              "--daemon-id",
              "daemon-a",
              "--skill-dir",
              skillDir.toString()
            });

    assertEquals(URI.create("wss://gateway.example/daemon"), config.gatewayUri());
    assertEquals("local-dev", config.environmentName());
    assertEquals("daemon-a", config.daemonId());
    assertEquals("secret", config.gatewayToken());
    assertEquals(Duration.ofSeconds(2), config.heartbeatInterval());
    assertEquals(Duration.ZERO, config.initialReconnectDelay());
    assertEquals(Duration.ofSeconds(3), config.maxReconnectDelay());
    assertEquals(Duration.ofSeconds(4), config.defaultToolTimeout());
    assertEquals(List.of(skillDir.toAbsolutePath().normalize()), config.skillDirs());
  }

  /** 显式 skill dirs 会替换默认的发现根目录。 */
  @Test
  void acceptsRepeatableSkillDirs(@TempDir Path first, @TempDir Path second) {
    set("kkstudio.daemon.gateway-uri", "ws://gateway.example/daemon");
    set("kkstudio.daemon.gateway-token", "secret");

    DaemonConfig config =
        DaemonConfig.fromArgs(
            new String[] {
              "--environment-name",
              "env",
              "--skill-dir",
              first.toString(),
              "--skill-dir",
              second.toString()
            });

    assertEquals(
        List.of(first.toAbsolutePath().normalize(), second.toAbsolutePath().normalize()),
        config.skillDirs());
  }

  /** 当部署缺少 gateway 所需的密钥时，启动失败关闭。 */
  @Test
  void rejectsMissingGatewayToken() {
    set("kkstudio.daemon.gateway-uri", "ws://gateway.example/daemon");

    assertThrows(
        IllegalArgumentException.class,
        () -> DaemonConfig.fromArgs(new String[] {"--environment-name", "env"}));
  }

  /** 未知的 CLI 参数立即失败，而不是被静默忽略。 */
  @Test
  void rejectsUnknownCliArgument() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DaemonConfig.fromArgs(
                new String[] {
                  "--environment-name", "env",
                  "--gateway-uri", "ws://gateway.example/daemon",
                  "--gateway-token", "secret",
                  "--unexpected", "x"
                }));
  }

  @Test
  void defaultSkillDirPointsAtUserAgentsSkills() {
    assertTrue(DaemonConfig.defaultSkillDir().endsWith(Path.of(".agents", "skills")));
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
      String environmentName,
      String daemonId,
      Duration heartbeatInterval,
      Duration initialReconnectDelay,
      Duration maxReconnectDelay,
      Duration defaultToolTimeout,
      String gatewayToken,
      List<Path> skillDirs) {
    return new DaemonConfig(
        gatewayUri,
        environmentName,
        daemonId,
        heartbeatInterval,
        initialReconnectDelay,
        maxReconnectDelay,
        defaultToolTimeout,
        gatewayToken,
        skillDirs);
  }

  private void set(String name, String value) {
    System.setProperty(name, value);
  }
}
