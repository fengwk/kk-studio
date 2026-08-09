package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** 配置契约用于阻止生产 Daemon 在缺少身份或 gateway 凭证时接入。 */
class DaemonConfigTest {

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

  /** CLI 是 daemon 连接、身份、超时与 skill roots 的唯一配置来源。 */
  @Test
  void readsCliArguments(@TempDir Path skillDir) {
    DaemonConfig config =
        DaemonConfig.fromArgs(
            new String[] {
              "--environment-name",
              "local-dev",
              "--gateway-uri",
              "wss://gateway.example/daemon",
              "--gateway-token",
              "secret",
              "--daemon-id",
              "daemon-a",
              "--heartbeat",
              "PT2S",
              "--reconnect-initial",
              "PT0S",
              "--reconnect-max",
              "PT3S",
              "--tool-timeout",
              "PT4S",
              "--skill-dir",
              skillDir.toString()
            });

    assertEquals(URI.create("wss://gateway.example/daemon"), config.gatewayUri());
    assertEquals(new EnvironmentName("local-dev"), config.environmentName());
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
    DaemonConfig config =
        DaemonConfig.fromArgs(
            new String[] {
              "--environment-name",
              "env",
              "--gateway-uri",
              "ws://gateway.example/daemon",
              "--gateway-token",
              "secret",
              "--skill-dir",
              first.toString(),
              "--skill-dir",
              second.toString()
            });

    assertEquals(
        List.of(first.toAbsolutePath().normalize(), second.toAbsolutePath().normalize()),
        config.skillDirs());
    assertTrue(config.mcpConfigPath() == null);
  }

  /** {@code --mcp-config} 是可选的 CLI 参数。 */
  @Test
  void acceptsMcpConfigCli(@TempDir Path configDir) throws Exception {
    Path configFile = configDir.resolve("mcp.json");
    Files.writeString(configFile, "{\"servers\":[]}");

    DaemonConfig cliConfig =
        DaemonConfig.fromArgs(
            new String[] {
              "--environment-name",
              "env",
              "--gateway-uri",
              "ws://gateway.example/daemon",
              "--gateway-token",
              "secret",
              "--mcp-config",
              configFile.toString()
            });
    assertEquals(configFile.toAbsolutePath().normalize(), cliConfig.mcpConfigPath());
  }

  /** 当部署缺少 gateway 所需的密钥时，启动失败关闭。 */
  @Test
  void rejectsMissingGatewayToken() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DaemonConfig.fromArgs(
                new String[] {
                  "--environment-name", "env",
                  "--gateway-uri", "ws://gateway.example/daemon"
                }));
  }

  /** 非 canonical 的 {@code --environment-name} 在配置解析期立即失败（名称是路由身份，不允许歧义）。 */
  @Test
  void rejectsNonCanonicalEnvironmentName() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DaemonConfig.fromArgs(
                new String[] {
                  "--environment-name", "My Env",
                  "--gateway-uri", "ws://gateway.example/daemon",
                  "--gateway-token", "secret"
                }));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DaemonConfig.fromArgs(
                new String[] {
                  "--environment-name", "my/env",
                  "--gateway-uri", "ws://gateway.example/daemon",
                  "--gateway-token", "secret"
                }));
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
        new EnvironmentName(environmentName),
        daemonId,
        heartbeatInterval,
        initialReconnectDelay,
        maxReconnectDelay,
        defaultToolTimeout,
        gatewayToken,
        skillDirs,
        null);
  }
}
