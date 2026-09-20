package fun.fengwk.kkstudio.harness.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

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
            "--gateway-uri",
            "--registration-token-file",
            "--heartbeat",
            "--reconnect-initial",
            "--reconnect-max",
            "--note",
            "--data-dir",
            "--bash-executable",
            "--lsp-bridge-command",
            "--javap-executable",
            "--version",
          }) {
        assertTrue(usage.contains(option), "usage must document " + option);
      }
      assertFalse(
          usage.contains("--environment-root"), "usage must not document removed environment root");
      // 默认值也是契约的一部分：操作者必须能从这里读出省略参数时的行为。
      for (String documentedDefault :
          new String[] {"PT15S", "PT1S", "PT30S", "~/.kk-studio", "bash", "javap"}) {
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
    assertTrue(error.getMessage().contains("--help"), error.getMessage());
  }
}
