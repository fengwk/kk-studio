package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.Set;

/**
 * 配置契约用于阻止生产 Daemon 在缺少身份、gateway 凭证或本地数据目录时接入。
 *
 * <p>本测试同时冻结凭证的存在形态：CLI 只接受 {@code --registration-token-file}，凭证文本只存在于 owner-only 文件里，并且不进入 record
 * 的 {@code toString}/{@code equals}。
 */
class DaemonConfigTest {

  private static final String TOKEN_TEXT = "secret";

  /** 所有显式连接输入都是必填且有界的：仅当全部合法时构造才成功。 */
  @Test
  void validatesExplicitConnectionConfiguration(@TempDir Path root) throws Exception {
    Path tokenFile = ownerOnlyTokenFile(root, TOKEN_TEXT);

    // 前三个参数各自非法：非 ws/wss scheme、零值 heartbeat、零值初始重连延迟。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            config(
                URI.create("http://localhost/gateway"),
                Duration.ofSeconds(1),
                Duration.ZERO,
                Duration.ofSeconds(1),
                tokenFile,
                root.resolve("data-a")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            config(
                URI.create("ws://localhost/gateway"),
                Duration.ZERO,
                Duration.ZERO,
                Duration.ofSeconds(1),
                tokenFile,
                root.resolve("data-b")));

    // 全部合法时必须成功，证明上面的失败确实来自被断言的那一项。
    DaemonConfig valid =
        config(
            URI.create("ws://localhost/gateway"),
            Duration.ofSeconds(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            tokenFile,
            root.resolve("data-c"));
    assertEquals(TOKEN_TEXT, valid.registrationToken());
  }

  /** CLI 是 daemon 连接、身份与本地数据目录的唯一配置来源。 */
  @Test
  void readsCliArguments(@TempDir Path root) throws Exception {
    Path dataDir = root.resolve("data");
    Path tokenFile = ownerOnlyTokenFile(root, TOKEN_TEXT);
    DaemonConfig config =
        DaemonConfig.fromArgs(
            new String[] {
              "--gateway-uri",
              "wss://gateway.example/daemon",
              "--registration-token-file",
              tokenFile.toString(),
              "--heartbeat",
              "PT2S",
              "--reconnect-initial",
              "PT0S",
              "--reconnect-max",
              "PT3S",
              "--note",
              "Custom local environment.",
              "--data-dir",
              dataDir.toString()
            });

    assertEquals(URI.create("wss://gateway.example/daemon"), config.gatewayUri());
    // 凭证文本按需从文件读取，而不是固化在配置里。
    assertEquals(tokenFile.toAbsolutePath().normalize(), config.registrationTokenFile());
    assertEquals(TOKEN_TEXT, config.registrationToken());
    assertEquals(Duration.ofSeconds(2), config.heartbeatInterval());
    assertEquals(Duration.ZERO, config.initialReconnectDelay());
    assertEquals(Duration.ofSeconds(3), config.maxReconnectDelay());
    assertEquals("Custom local environment.", config.note());
    assertEquals(dataDir.toAbsolutePath().normalize(), config.dataDir());
  }

  /** 已删除的配置参数必须作为未知参数 fail closed，不提供任何兼容回退。 */
  @Test
  void rejectsRemovedEnvironmentRootArgument(@TempDir Path root) throws Exception {
    Path tokenFile = ownerOnlyTokenFile(root, TOKEN_TEXT);
    String removedArg = "--environment-root";
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DaemonConfig.fromArgs(
                    new String[] {
                      "--gateway-uri",
                      "ws://gateway.example/daemon",
                      "--registration-token-file",
                      tokenFile.toString(),
                      removedArg,
                      root.toString()
                    }));
    assertTrue(error.getMessage().contains("unknown argument: " + removedArg));
  }

  /** 已删除的 {@code --registration-token} 必须作为未知参数 fail closed，不提供任何兼容回退。 */
  @Test
  void rejectsRemovedRegistrationTokenArgument(@TempDir Path root) throws Exception {
    Path tokenFile = ownerOnlyTokenFile(root, TOKEN_TEXT);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DaemonConfig.fromArgs(
                    new String[] {
                      "--gateway-uri",
                      "ws://gateway.example/daemon",
                      "--registration-token",
                      TOKEN_TEXT,
                      "--data-dir",
                      root.resolve("data").toString()
                    }));
    assertTrue(error.getMessage().contains("unknown argument"), error.getMessage());
    assertTrue(error.getMessage().contains("--registration-token"), error.getMessage());

    // 新参数本身仍然可用，证明上面失败的原因确是被删除的旧参数。
    assertEquals(
        TOKEN_TEXT,
        DaemonConfig.fromArgs(
                new String[] {
                  "--gateway-uri",
                  "ws://gateway.example/daemon",
                  "--registration-token-file",
                  tokenFile.toString(),
                  "--data-dir",
                  root.resolve("data").toString()
                })
            .registrationToken());
  }

  /** 凭证文本不得进入 record 的 toString/equals：日志与诊断输出不能扩散秘密。 */
  @Test
  void registrationTokenTextNeverLeaksThroughRecordSurfaces(@TempDir Path root) throws Exception {
    Path tokenA = ownerOnlyTokenFile(root.resolve("a"), "token-alpha");
    Path tokenB = ownerOnlyTokenFile(root.resolve("b"), "token-beta");
    DaemonConfig first =
        config(
            URI.create("ws://localhost/gateway"),
            Duration.ofSeconds(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            tokenA,
            root.resolve("data"));
    DaemonConfig second =
        config(
            URI.create("ws://localhost/gateway"),
            Duration.ofSeconds(1),
            Duration.ZERO,
            Duration.ofSeconds(1),
            tokenB,
            root.resolve("data"));

    assertEquals("token-alpha", first.registrationToken());
    assertEquals("token-beta", second.registrationToken());
    assertFalse(first.toString().contains("token-alpha"), first.toString());
    assertFalse(second.toString().contains("token-beta"), second.toString());
    assertNotEquals(first, second, "不同凭证文件必须是不相等的配置");
    assertNotEquals(first.hashCode(), second.hashCode());
  }

  /** 缺失凭证文件参数时启动失败关闭，而不是带着空凭证继续握手。 */
  @Test
  void rejectsMissingRegistrationTokenFile(@TempDir Path dataDir) {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DaemonConfig.fromArgs(
                new String[] {
                  "--gateway-uri", "ws://gateway.example/daemon", "--data-dir", dataDir.toString()
                }));
  }

  /** 凭证文件必须是绝对路径：相对路径会随 daemon 的启动目录漂移，因此明确拒绝。 */
  @Test
  void rejectsRelativeRegistrationTokenFile(@TempDir Path root) {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DaemonConfig.fromArgs(
                    new String[] {
                      "--gateway-uri",
                      "ws://gateway.example/daemon",
                      "--registration-token-file",
                      "relative/token",
                      "--data-dir",
                      root.resolve("data").toString()
                    }));
    assertTrue(error.getMessage().contains("absolute"), error.getMessage());
  }

  /** 凭证文件必须是现存普通文件：缺失路径在启动期就失败关闭。 */
  @Test
  void rejectsMissingRegistrationTokenFileOnDisk(@TempDir Path root) {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DaemonConfig.fromArgs(
                    new String[] {
                      "--gateway-uri",
                      "ws://gateway.example/daemon",
                      "--registration-token-file",
                      root.resolve("absent.token").toString(),
                      "--data-dir",
                      root.resolve("data").toString()
                    }));
    assertTrue(error.getMessage().contains("existing regular file"), error.getMessage());
  }

  /** 凭证文件必须 owner-only：group/other 可读的文件在 POSIX 上 fail closed。 */
  @Test
  void rejectsWorldReadableRegistrationTokenFile(@TempDir Path root) throws Exception {
    assumeTrue(
        FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        "需要 POSIX 文件系统验证权限位");
    Path tokenFile = root.resolve("token");
    Files.writeString(tokenFile, TOKEN_TEXT);
    Files.setPosixFilePermissions(
        tokenFile,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.GROUP_READ));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DaemonConfig.fromArgs(
                    new String[] {
                      "--gateway-uri",
                      "ws://gateway.example/daemon",
                      "--registration-token-file",
                      tokenFile.toString(),
                      "--data-dir",
                      root.resolve("data").toString()
                    }));
    assertTrue(error.getMessage().contains("group/other"), error.getMessage());
    assertFalse(error.getMessage().contains(TOKEN_TEXT), "错误信息不得回显凭证");
  }

  /** 空的凭证文件在按需读取时失败关闭，而不是把空串发给 Gateway。 */
  @Test
  void rejectsEmptyRegistrationTokenFileContent(@TempDir Path root) throws Exception {
    Path tokenFile = ownerOnlyTokenFile(root, "   \n");

    DaemonConfig config =
        DaemonConfig.fromArgs(
            new String[] {
              "--gateway-uri",
              "ws://gateway.example/daemon",
              "--registration-token-file",
              tokenFile.toString(),
              "--data-dir",
              root.resolve("data").toString()
            });
    IllegalStateException error =
        assertThrows(IllegalStateException.class, config::registrationToken);
    assertTrue(error.getMessage().contains("must not be empty"), error.getMessage());
    assertFalse(error.getMessage().contains(TOKEN_TEXT));
  }

  /** {@code --data-dir} 可省略，省略时回退到启动用户 HOME 下的 {@code .kk-studio}。 */
  @Test
  void defaultsDataDirToUserHomeKkStudio(@TempDir Path root) throws Exception {
    Path tokenFile = ownerOnlyTokenFile(root, TOKEN_TEXT);
    String oldHome = System.getProperty("user.home");
    try {
      Path home = Files.createDirectories(root.resolve("home"));
      System.setProperty("user.home", home.toString());
      DaemonConfig config =
          DaemonConfig.fromArgs(
              new String[] {
                "--gateway-uri",
                "ws://gateway.example/daemon",
                "--registration-token-file",
                tokenFile.toString()
              });

      assertEquals(home.resolve(".kk-studio").toAbsolutePath().normalize(), config.dataDir());
      assertFalse(Files.exists(config.dataDir()), "解析配置本身不得创建数据目录");
    } finally {
      System.setProperty("user.home", oldHome);
    }
  }

  /** {@code --data-dir} 必须是绝对路径：相对路径会被解析为进程当前目录下的位置，因此明确拒绝。 */
  @Test
  void rejectsRelativeDataDir(@TempDir Path root) throws Exception {
    Path tokenFile = ownerOnlyTokenFile(root, TOKEN_TEXT);
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DaemonConfig.fromArgs(
                    new String[] {
                      "--gateway-uri",
                      "ws://gateway.example/daemon",
                      "--registration-token-file",
                      tokenFile.toString(),
                      "--data-dir",
                      "relative/data"
                    }));
    assertTrue(error.getMessage().contains("absolute"), error.getMessage());
  }

  /** {@code --data-dir} 只能出现一次，重复配置是操作者错误。 */
  @Test
  void rejectsRepeatedDataDir(@TempDir Path root) throws Exception {
    Path tokenFile = ownerOnlyTokenFile(root, TOKEN_TEXT);
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
                      "--registration-token-file",
                      tokenFile.toString(),
                      "--data-dir",
                      first,
                      "--data-dir",
                      second
                    }));
    assertTrue(error.getMessage().contains("only be specified once"), error.getMessage());
  }

  /** {@code --registration-token-file} 也只能出现一次，避免后一个值静默覆盖前一个。 */
  @Test
  void rejectsRepeatedRegistrationTokenFile(@TempDir Path root) throws Exception {
    Path first = ownerOnlyTokenFile(root.resolve("first"), "token-one");
    Path second = ownerOnlyTokenFile(root.resolve("second"), "token-two");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DaemonConfig.fromArgs(
                    new String[] {
                      "--gateway-uri",
                      "ws://gateway.example/daemon",
                      "--registration-token-file",
                      first.toString(),
                      "--registration-token-file",
                      second.toString(),
                      "--data-dir",
                      root.resolve("data").toString()
                    }));
    assertTrue(error.getMessage().contains("only be specified once"), error.getMessage());
  }

  /** 已删除的 {@code --skill-dir} 必须作为未知参数失败，避免操作者以为来源仍由 CLI 配置。 */
  @Test
  void rejectsRemovedSkillDirArgument(@TempDir Path root) throws Exception {
    Path tokenFile = ownerOnlyTokenFile(root, TOKEN_TEXT);
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DaemonConfig.fromArgs(
                    new String[] {
                      "--gateway-uri",
                      "ws://gateway.example/daemon",
                      "--registration-token-file",
                      tokenFile.toString(),
                      "--data-dir",
                      root.resolve("data").toString(),
                      "--skill-dir",
                      root.toString()
                    }));
    assertTrue(error.getMessage().contains("unknown argument"), error.getMessage());
  }

  /** 已删除的 {@code --tool-timeout} 必须作为未知参数失败：执行超时只由 definition 默认值与调用显式值决定。 */
  @Test
  void rejectsRemovedToolTimeoutArgument(@TempDir Path root) throws Exception {
    Path tokenFile = ownerOnlyTokenFile(root, TOKEN_TEXT);
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DaemonConfig.fromArgs(
                    new String[] {
                      "--gateway-uri",
                      "ws://gateway.example/daemon",
                      "--registration-token-file",
                      tokenFile.toString(),
                      "--data-dir",
                      root.resolve("data").toString(),
                      "--tool-timeout",
                      "PT5M"
                    }));
    assertTrue(error.getMessage().contains("unknown argument"), error.getMessage());
    assertTrue(error.getMessage().contains("--tool-timeout"), error.getMessage());
  }

  /** 三个本地执行程序参数都有默认值，显式给出时覆盖默认值。 */
  @Test
  void resolvesLocalExecutableArguments(@TempDir Path root) throws Exception {
    Path tokenFile = ownerOnlyTokenFile(root, TOKEN_TEXT);
    String dataDir = root.resolve("data").toString();

    DaemonConfig defaults =
        DaemonConfig.fromArgs(
            new String[] {
              "--gateway-uri",
              "ws://gateway.example/daemon",
              "--registration-token-file",
              tokenFile.toString(),
              "--data-dir",
              dataDir
            });
    assertEquals(DaemonConfig.DEFAULT_BASH_EXECUTABLE, defaults.bashExecutable());
    assertEquals(DaemonConfig.DEFAULT_JAVAP_EXECUTABLE, defaults.javapExecutable());
    assertNull(defaults.lspBridgeCommand(), "LSP bridge 省略时必须处于禁用状态");

    DaemonConfig explicit =
        DaemonConfig.fromArgs(
            new String[] {
              "--gateway-uri", "ws://gateway.example/daemon",
              "--registration-token-file", tokenFile.toString(),
              "--data-dir", dataDir,
              "--bash-executable", "/usr/bin/bash",
              "--lsp-bridge-command", "lsp-bridge --stdio",
              "--javap-executable", "/opt/jdk/bin/javap"
            });
    assertEquals("/usr/bin/bash", explicit.bashExecutable());
    assertEquals("lsp-bridge --stdio", explicit.lspBridgeCommand());
    assertEquals("/opt/jdk/bin/javap", explicit.javapExecutable());
  }

  /** 已删除的系统属性不再是配置来源：{@code kkstudio.daemon.*} 必须完全失效。 */
  @Test
  void systemPropertiesAreNoLongerAConfigurationSource(@TempDir Path root) throws Exception {
    Path tokenFile = ownerOnlyTokenFile(root, TOKEN_TEXT);
    String[] removed = {
      "kkstudio.daemon.resource-directory",
      "kkstudio.daemon.max-resource-bytes",
      "kkstudio.daemon.bash"
    };
    String[] previous = new String[removed.length];
    try {
      for (int index = 0; index < removed.length; index++) {
        previous[index] = System.getProperty(removed[index]);
        System.setProperty(removed[index], "must-be-ignored");
      }

      DaemonConfig config =
          DaemonConfig.fromArgs(
              new String[] {
                "--gateway-uri", "ws://gateway.example/daemon",
                "--registration-token-file", tokenFile.toString(),
                "--data-dir", root.resolve("data").toString()
              });
      // 唯一权威来源是 CLI：被删除的属性既不能改写 bash，也不能改写任何其它取值。
      assertEquals(DaemonConfig.DEFAULT_BASH_EXECUTABLE, config.bashExecutable());
    } finally {
      for (int index = 0; index < removed.length; index++) {
        if (previous[index] == null) {
          System.clearProperty(removed[index]);
        } else {
          System.setProperty(removed[index], previous[index]);
        }
      }
    }
  }

  /** 数据目录不必预先存在（Daemon 会创建它），但显式数据目录会被规范化。 */
  @Test
  void acceptsNotYetExistingDataDir(@TempDir Path root) throws Exception {
    Path tokenFile = ownerOnlyTokenFile(root, TOKEN_TEXT);
    Path dataDir = root.resolve("nested/data");
    DaemonConfig config =
        DaemonConfig.fromArgs(
            new String[] {
              "--gateway-uri", "ws://gateway.example/daemon",
              "--registration-token-file", tokenFile.toString(),
              "--data-dir", dataDir.toString()
            });

    assertEquals(dataDir.toAbsolutePath().normalize(), config.dataDir());
  }

  /** {@code --note} 可省略或显式覆盖默认值，但显式值必须唯一、单行、无控制、无首尾空白且有界。 */
  @Test
  void validatesOptionalNoteCli(@TempDir Path root) throws Exception {
    Path tokenFile = ownerOnlyTokenFile(root, TOKEN_TEXT);
    DaemonConfig omitted =
        DaemonConfig.fromArgs(
            new String[] {
              "--gateway-uri", "ws://gateway.example/daemon",
              "--registration-token-file", tokenFile.toString(),
              "--data-dir", root.resolve("data").toString()
            });
    assertNull(omitted.note());

    assertInvalidNoteArgs(tokenFile, root, "--note", "first", "--note", "second");
    assertInvalidNoteArgs(tokenFile, root, "--note", "");
    assertInvalidNoteArgs(tokenFile, root, "--note", " ");
    assertInvalidNoteArgs(tokenFile, root, "--note", " leading");
    assertInvalidNoteArgs(tokenFile, root, "--note", "trailing ");
    assertInvalidNoteArgs(tokenFile, root, "--note", "first\nsecond");
    assertInvalidNoteArgs(tokenFile, root, "--note", "first\u2028second");
    assertInvalidNoteArgs(tokenFile, root, "--note", "control\u0007value");
    assertInvalidNoteArgs(
        tokenFile, root, "--note", "x".repeat(DaemonEnvironmentInfo.MAX_NOTE_CHARS + 1));

    DaemonConfig maxLength =
        DaemonConfig.fromArgs(
            new String[] {
              "--gateway-uri",
              "ws://gateway.example/daemon",
              "--registration-token-file",
              tokenFile.toString(),
              "--data-dir",
              root.resolve("data").toString(),
              "--note",
              "x".repeat(DaemonEnvironmentInfo.MAX_NOTE_CHARS)
            });
    assertEquals(DaemonEnvironmentInfo.MAX_NOTE_CHARS, maxLength.note().length());
  }

  /** 省略 note 时按实测 OS 生成固定说明，显式值对所有 OS 都优先。 */
  @Test
  void resolvesExactDefaultNotesForEveryOperatingSystem(@TempDir Path root) throws Exception {
    Path tokenFile = ownerOnlyTokenFile(root, TOKEN_TEXT);
    String dataDir = root.resolve("data").toString();
    DaemonConfig defaults =
        DaemonConfig.fromArgs(
            new String[] {
              "--gateway-uri",
              "ws://gateway.example/daemon",
              "--registration-token-file",
              tokenFile.toString(),
              "--data-dir",
              dataDir
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
              "--gateway-uri",
              "ws://gateway.example/daemon",
              "--registration-token-file",
              tokenFile.toString(),
              "--data-dir",
              dataDir,
              "--note",
              "Explicit environment."
            });
    for (DaemonOperatingSystem operatingSystem : DaemonOperatingSystem.values()) {
      assertEquals("Explicit environment.", explicit.effectiveNote(operatingSystem));
    }
  }

  /** 未知的 CLI 参数立即失败，而不是被静默忽略。 */
  @Test
  void rejectsUnknownCliArgument(@TempDir Path root) throws Exception {
    Path tokenFile = ownerOnlyTokenFile(root, TOKEN_TEXT);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DaemonConfig.fromArgs(
                new String[] {
                  "--gateway-uri",
                  "ws://gateway.example/daemon",
                  "--registration-token-file",
                  tokenFile.toString(),
                  "--data-dir",
                  root.resolve("data").toString(),
                  "--unexpected",
                  "x"
                }));
  }

  /** 缺少 flag 取值（其后直接是下一个 flag 或参数结束）失败，避免把 flag 名当值。 */
  @Test
  void rejectsMissingArgumentValue(@TempDir Path root) throws Exception {
    Path tokenFile = ownerOnlyTokenFile(root, TOKEN_TEXT);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            DaemonConfig.fromArgs(
                new String[] {
                  "--gateway-uri",
                  "ws://gateway.example/daemon",
                  "--registration-token-file",
                  tokenFile.toString(),
                  "--data-dir",
                  root.resolve("data").toString(),
                  "--note"
                }));
  }

  private DaemonConfig config(
      URI gatewayUri,
      Duration heartbeatInterval,
      Duration initialReconnectDelay,
      Duration maxReconnectDelay,
      Path registrationTokenFile,
      Path dataDir) {
    return new DaemonConfig(
        gatewayUri,
        registrationTokenFile,
        heartbeatInterval,
        initialReconnectDelay,
        maxReconnectDelay,
        null,
        dataDir);
  }

  /** 在给定目录下创建 owner-only 凭证文件，模拟真实的部署前准备步骤。 */
  private static Path ownerOnlyTokenFile(Path directory, String token) throws IOException {
    Files.createDirectories(directory);
    Path tokenFile = directory.resolve("registration.token");
    Files.writeString(tokenFile, token);
    if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
      Files.setPosixFilePermissions(
          tokenFile, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }
    return tokenFile;
  }

  /** 构造携带合法必填参数的参数数组，仅让 note 相关参数成为失败原因。 */
  private static void assertInvalidNoteArgs(Path tokenFile, Path root, String... noteArgs) {
    String[] args = new String[6 + noteArgs.length];
    args[0] = "--gateway-uri";
    args[1] = "ws://gateway.example/daemon";
    args[2] = "--registration-token-file";
    args[3] = tokenFile.toString();
    args[4] = "--data-dir";
    args[5] = root.resolve("data").toString();
    System.arraycopy(noteArgs, 0, args, 6, noteArgs.length);
    assertThrows(IllegalArgumentException.class, () -> DaemonConfig.fromArgs(args));
  }
}
