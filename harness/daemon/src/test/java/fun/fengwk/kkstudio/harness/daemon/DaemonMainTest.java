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
import java.io.UncheckedIOException;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * DaemonMain 信息命令契约。
 *
 * <p>信息命令必须在接触数据目录或建立连接之前完成，因此本测试只断言入口自身的输出与返回值：输出流由调用方注入，不需要捕获进程全局 stdout，也不触发 {@code
 * System.exit}。
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
          new String[] {
            "--config", "--check-config", "--version", "--base64-args",
          }) {
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

  /** 失败预检不输出成功、不创建数据；空 token、非法 UTF-8、重复字段均失败关闭。 */
  @Test
  @ResourceLock(Resources.SYSTEM_OUT)
  void failedPreflightLeavesInputsUntouched(@TempDir Path root) throws Exception {
    Path file = DaemonConfigTest.writeConfig(root, "http://localhost");
    Path token = file.resolveSibling("daemon.token");
    PrintStream previousOut = System.out;
    try {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
      for (String[] args :
          new String[][] {
            {"--check-config"}, {"--check-config", file.toString(), "--version"},
            {"--check-config", "relative.json"}, {"--check-config", "--help"},
            {"--check-config", ""}
          }) {
        assertThrows(IllegalArgumentException.class, () -> DaemonMain.main(args));
      }
      Files.writeString(token, " \n");
      assertThrows(
          IllegalStateException.class,
          () -> DaemonMain.main(new String[] {"--check-config", file.toString()}));
      Files.write(token, new byte[] {(byte) 0xff});
      assertThrows(
          UncheckedIOException.class,
          () -> DaemonMain.main(new String[] {"--check-config", file.toString()}));
      DaemonConfigTest.writeToken(root);
      Files.writeString(file, "{\"studioUrl\":\"http://host\",\"studioUrl\":\"SECRET\"}");
      assertThrows(
          IllegalArgumentException.class,
          () -> DaemonMain.main(new String[] {"--check-config", file.toString()}));
      assertEquals("", output.toString(StandardCharsets.UTF_8));
      assertEquals(List.of("daemon.json", "daemon.token"), DaemonConfigTest.entries(root));
    } finally {
      System.setOut(previousOut);
    }
  }
}
