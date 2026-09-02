package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** 配置契约用于阻止生产 Daemon 在缺少身份或 gateway 凭证时接入。 */
class DaemonConfigTest {

  /** 所有显式连接输入，包括 registration token，都是必填且有界的。 */
  @Test
  void validatesExplicitConnectionConfiguration() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            config(
                URI.create("http://localhost/gateway"),
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
              "--gateway-uri",
              "wss://gateway.example/daemon",
              "--registration-token",
              "secret",
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
              "--environment-root",
              skillDir.toString(),
              "--skill-dir",
              skillDir.toString()
            });

    assertEquals(URI.create("wss://gateway.example/daemon"), config.gatewayUri());
    assertEquals("secret", config.registrationToken());
    assertEquals(Duration.ofSeconds(2), config.heartbeatInterval());
    assertEquals(Duration.ZERO, config.initialReconnectDelay());
    assertEquals(Duration.ofSeconds(3), config.maxReconnectDelay());
    assertEquals(Duration.ofSeconds(4), config.defaultToolTimeout());
    assertEquals("Custom local environment.", config.note());
    assertEquals(skillDir.toRealPath(), config.environmentRoot());
    assertEquals(List.of(skillDir.toAbsolutePath().normalize()), config.skillDirs());
  }

  /** 未显式配置时，Environment Root 使用启动用户 HOME 的 canonical 目录。 */
  @Test
  void defaultsWorkdirToCanonicalUserHome(@TempDir Path home) throws Exception {
    String oldHome = System.getProperty("user.home");
    try {
      System.setProperty("user.home", home.toString());
      DaemonConfig config =
          DaemonConfig.fromArgs(
              new String[] {
                "--gateway-uri", "ws://gateway.example/daemon",
                "--registration-token", "secret"
              });

      assertEquals(home.toRealPath(), config.environmentRoot());
    } finally {
      System.setProperty("user.home", oldHome);
    }
  }

  /** 显式 Environment Root 必须是唯一、已存在的目录，并在解析时 canonical 化。 */
  @Test
  void validatesExplicitWorkdir(@TempDir Path root) throws Exception {
    Path file = Files.writeString(root.resolve("file.txt"), "x");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DaemonConfig.fromArgs(
                new String[] {
                  "--gateway-uri", "ws://gateway.example/daemon",
                  "--registration-token", "secret",
                  "--environment-root", file.toString()
                }));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DaemonConfig.fromArgs(
                new String[] {
                  "--gateway-uri",
                  "ws://gateway.example/daemon",
                  "--registration-token",
                  "secret",
                  "--environment-root",
                  root.toString(),
                  "--environment-root",
                  root.toString()
                }));
  }

  /** {@code --note} 可省略或显式覆盖默认值，但显式值必须唯一、单行、无控制、无首尾空白且有界。 */
  @Test
  void validatesOptionalNoteCli() {
    DaemonConfig omitted =
        DaemonConfig.fromArgs(
            new String[] {
              "--gateway-uri", "ws://gateway.example/daemon",
              "--registration-token", "secret"
            });
    assertNull(omitted.note());

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
              "--gateway-uri", "ws://gateway.example/daemon",
              "--registration-token", "secret",
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
              "--gateway-uri", "ws://gateway.example/daemon",
              "--registration-token", "secret"
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
              "--gateway-uri", "ws://gateway.example/daemon",
              "--registration-token", "secret",
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
              "--gateway-uri",
              "ws://gateway.example/daemon",
              "--registration-token",
              "secret",
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
  void rejectsMissingRegistrationToken() {
    assertThrows(
        IllegalArgumentException.class,
        () -> DaemonConfig.fromArgs(new String[] {"--gateway-uri", "ws://gateway.example/daemon"}));
  }

  /** 未知的 CLI 参数立即失败，而不是被静默忽略。 */
  @Test
  void rejectsUnknownCliArgument() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DaemonConfig.fromArgs(
                new String[] {
                  "--gateway-uri", "ws://gateway.example/daemon",
                  "--registration-token", "secret",
                  "--unexpected", "x"
                }));
  }

  @Test
  void defaultSkillDirPointsAtUserAgentsSkills() {
    assertTrue(DaemonConfig.defaultSkillDir().endsWith(Path.of(".agents", "skills")));
  }

  private DaemonConfig config(
      URI gatewayUri,
      Duration heartbeatInterval,
      Duration initialReconnectDelay,
      Duration maxReconnectDelay,
      Duration defaultToolTimeout,
      String registrationToken,
      List<Path> skillDirs) {
    return new DaemonConfig(
        gatewayUri,
        registrationToken,
        heartbeatInterval,
        initialReconnectDelay,
        maxReconnectDelay,
        defaultToolTimeout,
        null,
        DaemonConfig.defaultEnvironmentRoot(),
        skillDirs);
  }

  private static void assertInvalidNoteArgs(String... noteArgs) {
    String[] args = new String[4 + noteArgs.length];
    args[0] = "--gateway-uri";
    args[1] = "ws://gateway.example/daemon";
    args[2] = "--registration-token";
    args[3] = "secret";
    System.arraycopy(noteArgs, 0, args, 4, noteArgs.length);
    assertThrows(IllegalArgumentException.class, () -> DaemonConfig.fromArgs(args));
  }
}
