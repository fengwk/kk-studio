package fun.fengwk.kkstudio.harness.daemon.process;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * {@link WindowsCommandLine} 的拼装规则测试：Windows 上没有原生的 argv，子进程按 MSVC 运行时与 {@code CommandLineToArgvW}
 * 的规则重新切分命令行，因此拼装必须严格遵守同一套转义规则。
 *
 * <p>纯字符串变换，可以在任何平台验证；这些用例锁住三类最容易写错的规则：空白参数必须加引号、引号本身要按 MSVC 规则
 * 转义、结束引号之前的反斜杠必须加倍，否则会吃掉结束引号并把后续参数并进同一个值。
 */
class WindowsCommandLineTest {

  @Test
  void joinsSafeArgumentsWithoutAddingQuotes() {
    assertEquals("cmd", WindowsCommandLine.join(List.of("cmd")));
    assertEquals("cmd /c exit", WindowsCommandLine.join(List.of("cmd", "/c", "exit")));
  }

  @Test
  void quotesArgumentsContainingWhitespace() {
    assertEquals("cmd /c \"exit 3\"", WindowsCommandLine.join(List.of("cmd", "/c", "exit 3")));
    assertEquals("\"tab\tsep\"", WindowsCommandLine.join(List.of("tab\tsep")));
  }

  @Test
  void quotesEmptyArgumentAsEmptyPair() {
    assertEquals("\"\"", WindowsCommandLine.join(List.of("")));
    assertEquals("a \"\" b", WindowsCommandLine.join(List.of("a", "", "b")));
  }

  @Test
  void keepsBackslashesThatAreNotBeforeAQuote() {
    assertEquals("a\\b", WindowsCommandLine.join(List.of("a\\b")));
    assertEquals("\"C:\\Program Files\"", WindowsCommandLine.join(List.of("C:\\Program Files")));
  }

  @Test
  void doublesBackslashesBeforeTheClosingQuote() {
    assertEquals(
        "\"C:\\Program Files\\\\\"", WindowsCommandLine.join(List.of("C:\\Program Files\\")));
  }

  @Test
  void escapesEmbeddedQuotesWithBackslashes() {
    assertEquals("\"say \\\"hi\\\"\"", WindowsCommandLine.join(List.of("say \"hi\"")));
    assertEquals("\"\\\\\\\"\"", WindowsCommandLine.join(List.of("\\\"")));
  }
}
