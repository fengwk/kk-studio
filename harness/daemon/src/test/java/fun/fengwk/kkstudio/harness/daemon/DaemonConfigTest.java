package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonOperatingSystem;

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
  void readsCliArguments(@TempDir Path skillDir) throws Exception {
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
              "--note",
              "Custom local environment.",
              "--workdir",
              skillDir.toString(),
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
    assertEquals("Custom local environment.", config.note());
    assertEquals(skillDir.toRealPath(), config.workdir());
    assertEquals(List.of(skillDir.toAbsolutePath().normalize()), config.skillDirs());
  }

  /** 未显式配置时，workdir 使用启动用户 HOME 的 canonical 目录。 */
  @Test
  void defaultsWorkdirToCanonicalUserHome(@TempDir Path home) throws Exception {
    String oldHome = System.getProperty("user.home");
    try {
      System.setProperty("user.home", home.toString());
      DaemonConfig config =
          DaemonConfig.fromArgs(
              new String[] {
                "--environment-name", "env",
                "--gateway-uri", "ws://gateway.example/daemon",
                "--gateway-token", "secret"
              });

      assertEquals(home.toRealPath(), config.workdir());
    } finally {
      System.setProperty("user.home", oldHome);
    }
  }

  /** 显式 workdir 必须是唯一、已存在的目录，并在解析时 canonical 化。 */
  @Test
  void validatesExplicitWorkdir(@TempDir Path root) throws Exception {
    Path file = Files.writeString(root.resolve("file.txt"), "x");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DaemonConfig.fromArgs(
                new String[] {
                  "--environment-name", "env",
                  "--gateway-uri", "ws://gateway.example/daemon",
                  "--gateway-token", "secret",
                  "--workdir", file.toString()
                }));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DaemonConfig.fromArgs(
                new String[] {
                  "--environment-name", "env",
                  "--gateway-uri", "ws://gateway.example/daemon",
                  "--gateway-token", "secret",
                  "--workdir", root.toString(),
                  "--workdir", root.toString()
                }));
  }

  /** {@code --note} 可省略或显式覆盖默认值，但显式值必须唯一、单行、无控制、无首尾空白且有界。 */
  @Test
  void validatesOptionalNoteCli() {
    DaemonConfig omitted =
        DaemonConfig.fromArgs(
            new String[] {
              "--environment-name", "env",
              "--gateway-uri", "ws://gateway.example/daemon",
              "--gateway-token", "secret"
            });
    assertEquals(null, omitted.note());

    assertInvalidNoteArgs("--note", "first", "--note", "second");
    assertInvalidNoteArgs("--note", "");
    assertInvalidNoteArgs("--note", " ");
    assertInvalidNoteArgs("--note", " leading");
    assertInvalidNoteArgs("--note", "trailing ");
    assertInvalidNoteArgs("--note", "first\nsecond");
    assertInvalidNoteArgs("--note", "first\u2028second");
    assertInvalidNoteArgs("--note", "control\u0007value");
    assertInvalidNoteArgs("--note", "x".repeat(DaemonEnvironmentInfo.MAX_NOTE_CHARS + 1));

    DaemonConfig maxLength =
        DaemonConfig.fromArgs(
            new String[] {
              "--environment-name", "env",
              "--gateway-uri", "ws://gateway.example/daemon",
              "--gateway-token", "secret",
              "--note", "x".repeat(DaemonEnvironmentInfo.MAX_NOTE_CHARS)
            });
    assertEquals(DaemonEnvironmentInfo.MAX_NOTE_CHARS, maxLength.note().length());
  }

  /** 省略 note 时按实测 OS 生成固定说明，显式值对所有 OS 都优先。 */
  @Test
  void resolvesExactDefaultNotesForEveryOperatingSystem() {
    DaemonConfig defaults =
        DaemonConfig.fromArgs(
            new String[] {
              "--environment-name", "env",
              "--gateway-uri", "ws://gateway.example/daemon",
              "--gateway-token", "secret"
            });
    assertEquals("Windows environment.", defaults.effectiveNote(DaemonOperatingSystem.WINDOWS));
    assertEquals(
        "WSL environment. Windows files may be accessible under /mnt/<drive>, and some Windows"
            + " commands may be invocable from WSL.",
        defaults.effectiveNote(DaemonOperatingSystem.WSL));
    assertEquals("Linux environment.", defaults.effectiveNote(DaemonOperatingSystem.LINUX));
    assertEquals("macOS environment.", defaults.effectiveNote(DaemonOperatingSystem.MACOS));

    DaemonConfig explicit =
        DaemonConfig.fromArgs(
            new String[] {
              "--environment-name", "env",
              "--gateway-uri", "ws://gateway.example/daemon",
              "--gateway-token", "secret",
              "--note", "Explicit environment."
            });
    for (DaemonOperatingSystem operatingSystem : DaemonOperatingSystem.values()) {
      assertEquals("Explicit environment.", explicit.effectiveNote(operatingSystem));
    }
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
        null,
        DaemonConfig.defaultWorkdir(),
        skillDirs,
        null);
  }

  private static void assertInvalidNoteArgs(String... noteArgs) {
    String[] args = new String[6 + noteArgs.length];
    args[0] = "--environment-name";
    args[1] = "env";
    args[2] = "--gateway-uri";
    args[3] = "ws://gateway.example/daemon";
    args[4] = "--gateway-token";
    args[5] = "secret";
    System.arraycopy(noteArgs, 0, args, 6, noteArgs.length);
    assertThrows(IllegalArgumentException.class, () -> DaemonConfig.fromArgs(args));
  }
}
