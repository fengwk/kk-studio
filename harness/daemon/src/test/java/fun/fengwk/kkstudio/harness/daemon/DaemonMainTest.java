package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * DaemonMain 信息命令与配置预检契约。
 *
 * <p>信息命令必须在接触数据目录或建立连接之前完成，因此本测试只断言入口自身的输出与返回值：输出流由调用方注入，不需要捕获进程全局 stdout，也不触发 {@code
 * System.exit}。预检失败同样通过 {@link DaemonMain#checkConfig(String[], PrintStream, PrintStream)}
 * 注入输出流验证，不退出 JVM。
 */
class DaemonMainTest {

  /**
   * 意图：单独一个 {@code --help}/{@code -h} 必须在打开数据目录之前打印用法并以状态 0 结束。
   *
   * <p>断言文本覆盖当前全部 CLI 选项与默认值：遗漏任何一个选项都会让操作者以为它不存在。
   */
  @Test
  void helpPrintsUsageWithEveryCliOption() {
    for (String flag : new String[] {"--help", "-h"}) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      assertTrue(
          DaemonMain.printInfoCommand(new String[] {flag}, new PrintStream(out, true)),
          flag + " must be an informational command");

      String usage = out.toString(StandardCharsets.UTF_8);
      assertEquals(DaemonMain.USAGE, usage, flag + " must print exactly the usage text");
      for (String option :
          new String[] {"--config", "--check-config", "--version", "--base64-args"}) {
        assertTrue(usage.contains(option), "usage must document " + option);
      }
      assertFalse(
          usage.contains("--environment-root"), "usage must not document removed environment root");
      for (String removed :
          new String[] {
            "--gateway-uri",
            "--registration-token-file",
            "--note",
            "--data-dir",
            "--lsp-config",
            "--bash-executable",
            "--heartbeat",
            "--reconnect-initial",
            "--reconnect-max"
          }) {
        assertFalse(usage.contains(removed), "usage must not document removed option " + removed);
      }
      // 默认值也是契约的一部分：操作者必须能从这里读出省略参数时的行为。
      for (String documentedDefault :
          new String[] {"PT15S", "PT1S", "PT30S", "~/.kk-studio", "bash"}) {
        assertTrue(
            usage.contains(documentedDefault), "usage must document default " + documentedDefault);
      }
    }
  }

  /** 意图：{@code --version} 只打印版本，并且直接运行测试 classes 时使用 development。 */
  @Test
  void versionPrintsImplementationVersionOrDevelopmentFallback() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    assertTrue(DaemonMain.printInfoCommand(new String[] {"--version"}, new PrintStream(out, true)));

    String expected =
        "kk-studio-daemon " + DaemonMain.implementationVersion() + System.lineSeparator();
    assertEquals(expected, out.toString(StandardCharsets.UTF_8));
    assertEquals("development", DaemonMain.implementationVersion());
  }

  /**
   * 意图：信息命令只接受唯一参数，混用或多余参数必须继续走 {@link DaemonConfig} 并失败关闭。
   *
   * <p>否则 {@code --help --gateway-uri ...} 这类输入会被静默当成合法调用，掩盖操作者的参数错误。
   */
  @Test
  void informationalCommandsRequireExactlyOneArgument() {
    assertFalse(
        DaemonMain.printInfoCommand(new String[0], new PrintStream(new ByteArrayOutputStream())));
    for (String[] args :
        new String[][] {
          {"--help", "--version"},
          {"--version", "--version"},
          {"--help", "--gateway-uri", "ws://localhost/gateway"},
          {"--version", "--data-dir", "/tmp"},
          {"--help=true"},
          {"--Version"},
        }) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      assertFalse(
          DaemonMain.printInfoCommand(args, new PrintStream(out, true)),
          String.join(" ", args) + " must not be treated as an informational command");
      assertEquals(
          "",
          out.toString(StandardCharsets.UTF_8),
          "no usage may be printed for " + String.join(" ", args));
    }
  }

  /** 意图：混用信息命令与真实参数时，DaemonConfig 必须失败关闭，而不是启动一个半配置的 Daemon。 */
  @Test
  void mixedInformationalAndRuntimeArgumentsFailClosed() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                DaemonConfig.fromArgs(
                    new String[] {"--help", "--gateway-uri", "ws://localhost/gateway"}));
    assertTrue(error.getMessage().contains("--config"), error.getMessage());
  }

  /** 意图：真实 main 最先解码；没有连接配置、HOME 是普通文件，信息命令仍成功，证明不进入运行时。 */
  @Test
  @ResourceLock(Resources.SYSTEM_OUT)
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  @ResourceLock("defaultProxySelector")
  void encodedInformationCommandsReturnBeforeOpeningDataOrConnecting(@TempDir Path root)
      throws Exception {
    Path home = Files.writeString(root.resolve("not-a-directory"), "unchanged");
    String previousHome = System.getProperty("user.home");
    PrintStream previousOut = System.out;
    ProxySelector previousSelector = ProxySelector.getDefault();
    String previousProxyProperty = System.getProperty("java.net.useSystemProxies");
    try {
      System.setProperty("user.home", home.toString());
      for (String flag : new String[] {"--version", "--help", "-h"}) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
        DaemonMain.main(DaemonArgumentsTest.encoded(flag));
        assertSame(previousSelector, ProxySelector.getDefault());
        assertEquals(previousProxyProperty, System.getProperty("java.net.useSystemProxies"));
        assertEquals(
            flag.equals("--version")
                ? "kk-studio-daemon development" + System.lineSeparator()
                : DaemonMain.USAGE,
            output.toString(StandardCharsets.UTF_8));
      }
      assertEquals("unchanged", Files.readString(home));
      assertFalse(Files.exists(root.resolve(".kk-studio")));
    } finally {
      ProxySelector.setDefault(previousSelector);
      if (previousProxyProperty == null) {
        System.clearProperty("java.net.useSystemProxies");
      } else {
        System.setProperty("java.net.useSystemProxies", previousProxyProperty);
      }
      System.setOut(previousOut);
      if (previousHome == null) {
        System.clearProperty("user.home");
      } else {
        System.setProperty("user.home", previousHome);
      }
    }
  }

  /** 意图：真实入口的错误/嵌套/混用参数在配置与资源初始化之前失败，而不是二次解码。 */
  @Test
  void mainRejectsInvalidAndNestedTransport() {
    for (String[] args :
        new String[][] {
          {"--base64-args", "%%%"},
          {"--base64-args", "--version"},
          DaemonArgumentsTest.encoded("--base64-args", "LS12ZXJzaW9u"),
          DaemonArgumentsTest.encoded("--help", "--version"),
          DaemonArgumentsTest.encoded("--note", "one", "--note", "two"),
          DaemonArgumentsTest.encoded("--note")
        }) {
      assertThrows(IllegalArgumentException.class, () -> DaemonMain.main(args));
    }
  }

  /** 真实 main 预检仅读取：即使数据目录锁已被占用，也不初始化代理、连接或启动 LSP。 */
  @Test
  @ResourceLock(Resources.SYSTEM_OUT)
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  @ResourceLock("defaultProxySelector")
  void preflightHasNoFilesystemNetworkOrProcessSideEffects(@TempDir Path root) throws Exception {
    PrintStream previousOut = System.out;
    ProxySelector previousSelector = ProxySelector.getDefault();
    String previousProxyProperty = System.getProperty("java.net.useSystemProxies");
    try (ServerSocket server = new ServerSocket(0)) {
      Path file = DaemonConfigTest.writeConfig(root, "http://127.0.0.1:" + server.getLocalPort());
      Files.writeString(
          file,
          "{\"studioUrl\":\"http://127.0.0.1:"
              + server.getLocalPort()
              + "\",\"lsp\":{\"servers\":{\"not-installed\":{\"command\":[\"/SECRET/not-installed\"],\"extensions\":[\".java\"]}}}}");
      List<String> before = DaemonConfigTest.entries(root);
      byte[] configBytes = Files.readAllBytes(file);
      byte[] tokenBytes = Files.readAllBytes(file.resolveSibling("daemon.token"));
      var configTime = Files.getLastModifiedTime(file);
      var tokenTime = Files.getLastModifiedTime(file.resolveSibling("daemon.token"));
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
      DaemonMain.main(new String[] {"--check-config", file.toString()});
      assertEquals(
          "Daemon configuration is valid" + System.lineSeparator(),
          output.toString(StandardCharsets.UTF_8));
      assertEquals(before, DaemonConfigTest.entries(root));
      assertArrayEquals(configBytes, Files.readAllBytes(file));
      assertArrayEquals(tokenBytes, Files.readAllBytes(file.resolveSibling("daemon.token")));
      assertEquals(configTime, Files.getLastModifiedTime(file));
      assertEquals(tokenTime, Files.getLastModifiedTime(file.resolveSibling("daemon.token")));
      assertEquals(List.of("daemon.json", "daemon.token"), DaemonConfigTest.entries(root));
      // 已占用的锁也不会被预检重新打开；Windows 编码入口仍使用同一配置源。
      try (DaemonDataDirectory locked = DaemonDataDirectory.open(root)) {
        var lockedEntries = DaemonConfigTest.entries(root);
        output.reset();
        DaemonMain.main(DaemonArgumentsTest.encoded("--check-config", file.toString()));
        assertEquals(
            "Daemon configuration is valid" + System.lineSeparator(),
            output.toString(StandardCharsets.UTF_8));
        assertEquals(lockedEntries, DaemonConfigTest.entries(root));
      }
      assertSame(previousSelector, ProxySelector.getDefault());
      assertEquals(previousProxyProperty, System.getProperty("java.net.useSystemProxies"));
      server.setSoTimeout(100);
      assertThrows(SocketTimeoutException.class, server::accept);
    } finally {
      System.setOut(previousOut);
    }
  }

  /**
   * 意图：预检失败只输出单行安全诊断（固定前缀 + 字段路径/规则或固定规则），保持输入不变且不泄漏取值。
   *
   * <p>空 token、非法 UTF-8、缺失 bash、重复字段均失败关闭；已知失败保留字段路径，未知失败收敛为固定规则。
   */
  @Test
  void failedPreflightReportsSafeDiagnosticWithoutSideEffects(@TempDir Path root) throws Exception {
    Path file = DaemonConfigTest.writeConfig(root, "http://localhost");
    Path token = file.resolveSibling("daemon.token");
    for (String[] args :
        new String[][] {
          {"--check-config"}, {"--check-config", file.toString(), "--version"},
          {"--check-config", "relative.json"}, {"--check-config", "--help"},
          {"--check-config", ""}
        }) {
      assertSafeDiagnostic(runCheck(args));
    }
    Files.writeString(token, " \n");
    assertSafeDiagnostic(runCheck(new String[] {"--check-config", file.toString()}));
    Files.write(token, new byte[] {(byte) 0xff});
    assertSafeDiagnostic(runCheck(new String[] {"--check-config", file.toString()}));
    DaemonConfigTest.writeToken(root);
    String absent = root.resolve("absent-bash").toString();
    Files.writeString(
        file, "{\"studioUrl\":\"http://host\",\"bashExecutable\":\"" + absent + "\"}");
    CheckResult bash = runCheck(new String[] {"--check-config", file.toString()});
    assertSafeDiagnostic(bash);
    assertTrue(bash.err().contains("daemon.bashExecutable"), bash.err());
    assertFalse(bash.err().contains(absent), "诊断不得回显配置取值: " + bash.err());
    DaemonConfigTest.writeToken(root);
    Files.writeString(file, "{\"studioUrl\":\"http://host\",\"studioUrl\":\"SECRET\"}");
    CheckResult duplicate = runCheck(new String[] {"--check-config", file.toString()});
    assertSafeDiagnostic(duplicate);
    assertFalse(duplicate.err().contains("SECRET"), duplicate.err());
    assertEquals(List.of("daemon.json", "daemon.token"), DaemonConfigTest.entries(root));
  }

  /** 意图：已知共享 codec/bash 失败保留字段路径与规则；未知或含换行的信息收敛为固定规则，绝不回显取值。 */
  @Test
  void checkConfigDiagnosticsAreSafeAndSingleLine() {
    assertEquals(
        "Invalid daemon configuration: daemon.lsp.servers.java: unknown field",
        DaemonMain.invalidConfigurationLine(
            new IllegalArgumentException("daemon.lsp.servers.java: unknown field")));
    assertTrue(
        DaemonMain.invalidConfigurationLine(
                new IllegalArgumentException(
                    "daemon.bashExecutable: must resolve to an executable"))
            .startsWith("Invalid daemon configuration: daemon.bashExecutable: "));
    assertEquals(
        "Invalid daemon configuration: configuration is invalid",
        DaemonMain.invalidConfigurationLine(
            new IllegalStateException("registration token file must exist: /SECRET/token")));
    assertEquals(
        "Invalid daemon configuration: configuration is invalid",
        DaemonMain.invalidConfigurationLine(
            new IllegalArgumentException("daemon.note: bad\nSECRET /private/path")));
  }

  /** 正常运行同样在代理初始化与数据目录打开之前完成 bash 解析；失败时不留任何运行期副作用。 */
  @Test
  @ResourceLock(Resources.SYSTEM_OUT)
  @ResourceLock(Resources.SYSTEM_PROPERTIES)
  @ResourceLock("defaultProxySelector")
  void normalRuntimeValidatesBashBeforeProxyOrData(@TempDir Path root) throws Exception {
    Path file = DaemonConfigTest.writeConfig(root, "http://localhost");
    Files.writeString(
        file,
        "{\"studioUrl\":\"http://localhost\",\"bashExecutable\":\""
            + root.resolve("absent-bash")
            + "\"}");
    ProxySelector previousSelector = ProxySelector.getDefault();
    String previousProxyProperty = System.getProperty("java.net.useSystemProxies");
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> DaemonMain.main(new String[] {"--config", file.toString()}));
    assertTrue(error.getMessage().contains("daemon.bashExecutable"));
    assertSame(previousSelector, ProxySelector.getDefault());
    assertEquals(previousProxyProperty, System.getProperty("java.net.useSystemProxies"));
    assertEquals(List.of("daemon.json", "daemon.token"), DaemonConfigTest.entries(root));
  }

  private static CheckResult runCheck(String[] args) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    boolean success =
        DaemonMain.checkConfig(
            args,
            new PrintStream(out, true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8));
    return new CheckResult(
        success, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  private static void assertSafeDiagnostic(CheckResult result) {
    assertFalse(result.success(), "预检必须失败");
    assertEquals("", result.out(), "失败时不得输出成功文本");
    assertTrue(
        result.err().startsWith(DaemonMain.INVALID_CONFIGURATION_MARKER + ": "), result.err());
    assertEquals(1, result.err().lines().count(), "必须只输出一行诊断: " + result.err());
    assertTrue(result.err().endsWith(System.lineSeparator()), result.err());
  }

  private record CheckResult(boolean success, String out, String err) {}
}
