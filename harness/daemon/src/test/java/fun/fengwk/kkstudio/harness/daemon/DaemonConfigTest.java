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

/** 配置契约用于阻止生产 Daemon 在缺少身份、gateway 凭证或本地数据目录时接入。 */
class DaemonConfigTest {

  /** 所有显式连接输入，包括 registration token，都是必填且有界的。 */
  @Test
  void validatesExplicitConnectionConfiguration(@TempDir Path dataDir) {
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
                dataDir));
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
                dataDir));
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
                dataDir));
  }

  /** CLI 是 daemon 连接、身份、超时、environment root 与本地数据目录的唯一配置来源。 */
  @Test
  void readsCliArguments(@TempDir Path root) throws Exception {
    Path environmentRoot = Files.createDirectories(root.resolve("home"));
    Path dataDir = root.resolve("data");
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
              environmentRoot.toString(),
              "--data-dir",
              dataDir.toString()
            });

    assertEquals(URI.create("wss://gateway.example/daemon"), config.gatewayUri());
    assertEquals("secret", config.registrationToken());
    assertEquals(Duration.ofSeconds(2), config.heartbeatInterval());
    assertEquals(Duration.ZERO, config.initialReconnectDelay());
    assertEquals(Duration.ofSeconds(3), config.maxReconnectDelay());
    assertEquals(Duration.ofSeconds(4), config.defaultToolTimeout());
    assertEquals("Custom local environment.", config.note());
    assertEquals(environmentRoot.toRealPath(), config.environmentRoot());
    assertEquals(dataDir.toAbsolutePath().normalize(), config.dataDir());
  }

  /** {@code --data-dir} 必填：缺失时启动失败关闭，而不是回退到任何默认 skill 目录。 */
  @Test
  void requiresExplicitDataDir() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DaemonConfig.fromArgs(
                    new String[] {
                      "--gateway-uri", "ws://gateway.example/daemon",
                      "--registration-token", "secret"
                    }));
    assertTrue(error.getMessage().contains("data-dir"));
  }

  /** {@code --data-dir} 必须是绝对路径：相对路径会被解析为进程当前目录下的位置，因此明确拒绝。 */
  @Test
  void rejectsRelativeDataDir() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DaemonConfig.fromArgs(
                    new String[] {
                      "--gateway-uri", "ws://gateway.example/daemon",
                      "--registration-token", "secret",
                      "--data-dir", "relative/data"
                    }));
    assertTrue(error.getMessage().contains("absolute"));
  }

  /** {@code --data-dir} 只能出现一次，重复配置是操作者错误。 */
  @Test
  void rejectsRepeatedDataDir(@TempDir Path root) {
    String first = root.resolve("first").toString();
    String second = root.resolve("second").toString();
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DaemonConfig.fromArgs(
                    new String[] {
                      "--gateway-uri",
                      "ws://gateway.example/daemon",
                      "--registration-token",
                      "secret",
                      "--data-dir",
                      first,
                      "--data-dir",
                      second
                    }));
    assertTrue(error.getMessage().contains("only be specified once"));
  }

  /** 已删除的 {@code --skill-dir} 必须作为未知参数失败，避免操作者以为来源仍由 CLI 配置。 */
  @Test
  void rejectsRemovedSkillDirArgument(@TempDir Path dataDir) {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DaemonConfig.fromArgs(
                    new String[] {
                      "--gateway-uri",
                      "ws://gateway.example/daemon",
                      "--registration-token",
                      "secret",
                      "--data-dir",
                      dataDir.toString(),
                      "--skill-dir",
                      dataDir.toString()
                    }));
    assertTrue(error.getMessage().contains("unknown argument"));
  }

  /** 数据目录不必预先存在（Daemon 会创建它），但显式数据目录会被规范化。 */
  @Test
  void acceptsNotYetExistingDataDir(@TempDir Path root) {
    Path dataDir = root.resolve("nested/data");
    DaemonConfig config =
        DaemonConfig.fromArgs(
            new String[] {
              "--gateway-uri", "ws://gateway.example/daemon",
              "--registration-token", "secret",
              "--data-dir", dataDir.toString()
            });

    assertEquals(dataDir.toAbsolutePath().normalize(), config.dataDir());
  }

  /** 未显式配置时，Environment Root 使用启动用户 HOME 的 canonical 目录。 */
  @Test
  void defaultsEnvironmentRootToCanonicalUserHome(@TempDir Path home) throws Exception {
    String oldHome = System.getProperty("user.home");
    try {
      System.setProperty("user.home", home.toString());
      DaemonConfig config =
          DaemonConfig.fromArgs(
              new String[] {
                "--gateway-uri", "ws://gateway.example/daemon",
                "--registration-token", "secret",
                "--data-dir", home.resolve("data").toString()
              });

      assertEquals(home.toRealPath(), config.environmentRoot());
    } finally {
      System.setProperty("user.home", oldHome);
    }
  }

  /** 显式 Environment Root 必须是唯一、已存在的目录，并在解析时 canonical 化。 */
  @Test
  void validatesExplicitEnvironmentRoot(@TempDir Path root) throws Exception {
    Path file = Files.writeString(root.resolve("file.txt"), "x");
    String dataDir = root.resolve("data").toString();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DaemonConfig.fromArgs(
                new String[] {
                  "--gateway-uri",
                  "ws://gateway.example/daemon",
                  "--registration-token",
                  "secret",
                  "--data-dir",
                  dataDir,
                  "--environment-root",
                  file.toString()
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
                  "--data-dir",
                  dataDir,
                  "--environment-root",
                  root.toString(),
                  "--environment-root",
                  root.toString()
                }));
  }

  /** {@code --note} 可省略或显式覆盖默认值，但显式值必须唯一、单行、无控制、无首尾空白且有界。 */
  @Test
  void validatesOptionalNoteCli(@TempDir Path dataDir) {
    DaemonConfig omitted =
        DaemonConfig.fromArgs(
            new String[] {
              "--gateway-uri", "ws://gateway.example/daemon",
              "--registration-token", "secret",
              "--data-dir", dataDir.toString()
            });
    assertNull(omitted.note());

    assertInvalidNoteArgs(dataDir, "--note", "first", "--note", "second");
    assertInvalidNoteArgs(dataDir, "--note", "");
    assertInvalidNoteArgs(dataDir, "--note", " ");
    assertInvalidNoteArgs(dataDir, "--note", " leading");
    assertInvalidNoteArgs(dataDir, "--note", "trailing ");
    assertInvalidNoteArgs(dataDir, "--note", "first\nsecond");
    assertInvalidNoteArgs(dataDir, "--note", "first\u2028second");
    assertInvalidNoteArgs(dataDir, "--note", "control\u0007value");
    assertInvalidNoteArgs(dataDir, "--note", "x".repeat(DaemonEnvironmentInfo.MAX_NOTE_CHARS + 1));

    DaemonConfig maxLength =
        DaemonConfig.fromArgs(
            new String[] {
              "--gateway-uri",
              "ws://gateway.example/daemon",
              "--registration-token",
              "secret",
              "--data-dir",
              dataDir.toString(),
              "--note",
              "x".repeat(DaemonEnvironmentInfo.MAX_NOTE_CHARS)
            });
    assertEquals(DaemonEnvironmentInfo.MAX_NOTE_CHARS, maxLength.note().length());
  }

  /** 省略 note 时按实测 OS 生成固定说明，显式值对所有 OS 都优先。 */
  @Test
  void resolvesExactDefaultNotesForEveryOperatingSystem(@TempDir Path dataDir) {
    DaemonConfig defaults =
        DaemonConfig.fromArgs(
            new String[] {
              "--gateway-uri", "ws://gateway.example/daemon",
              "--registration-token", "secret",
              "--data-dir", dataDir.toString()
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
              "--data-dir", dataDir.toString(),
              "--note", "Explicit environment."
            });
    for (DaemonOperatingSystem operatingSystem : DaemonOperatingSystem.values()) {
      assertEquals("Explicit environment.", explicit.effectiveNote(operatingSystem));
    }
  }

  /** 当部署缺少 gateway 所需的密钥时，启动失败关闭。 */
  @Test
  void rejectsMissingRegistrationToken(@TempDir Path dataDir) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DaemonConfig.fromArgs(
                new String[] {
                  "--gateway-uri", "ws://gateway.example/daemon", "--data-dir", dataDir.toString()
                }));
  }

  /** 未知的 CLI 参数立即失败，而不是被静默忽略。 */
  @Test
  void rejectsUnknownCliArgument(@TempDir Path dataDir) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DaemonConfig.fromArgs(
                new String[] {
                  "--gateway-uri", "ws://gateway.example/daemon",
                  "--registration-token", "secret",
                  "--data-dir", dataDir.toString(),
                  "--unexpected", "x"
                }));
  }

  /** 缺少 flag 取值（其后直接是下一个 flag 或参数结束）失败，避免把 flag 名当值。 */
  @Test
  void rejectsMissingArgumentValue(@TempDir Path dataDir) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DaemonConfig.fromArgs(
                new String[] {
                  "--gateway-uri",
                  "ws://gateway.example/daemon",
                  "--registration-token",
                  "secret",
                  "--data-dir",
                  dataDir.toString(),
                  "--note"
                }));
  }

  private DaemonConfig config(
      URI gatewayUri,
      Duration heartbeatInterval,
      Duration initialReconnectDelay,
      Duration maxReconnectDelay,
      Duration defaultToolTimeout,
      String registrationToken,
      Path dataDir) {
    return new DaemonConfig(
        gatewayUri,
        registrationToken,
        heartbeatInterval,
        initialReconnectDelay,
        maxReconnectDelay,
        defaultToolTimeout,
        null,
        DaemonConfig.defaultEnvironmentRoot(),
        dataDir);
  }

  /** 构造携带合法必填参数的参数数组，仅让 note 相关参数成为失败原因。 */
  private static void assertInvalidNoteArgs(Path dataDir, String... noteArgs) {
    String[] args = new String[6 + noteArgs.length];
    args[0] = "--gateway-uri";
    args[1] = "ws://gateway.example/daemon";
    args[2] = "--registration-token";
    args[3] = "secret";
    args[4] = "--data-dir";
    args[5] = dataDir.toString();
    System.arraycopy(noteArgs, 0, args, 6, noteArgs.length);
    assertThrows(IllegalArgumentException.class, () -> DaemonConfig.fromArgs(args));
  }
}
