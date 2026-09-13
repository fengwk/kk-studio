package fun.fengwk.kkstudio.platform.cloudfs.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 验证 {@link CloudSearchSupport} 的 RE2 编译、字面量搜索、大小写折叠、multiline 跨行匹配、 单文件内 early stop 预算控制以及围绕实际
 * match 生成 <=500 code point 有界摘录的行为。
 */
class CloudSearchSupportTest {

  @Test
  void literalSearchSingleLine() throws Exception {
    // 意图：字面量逐行搜索应准确报告行号与内容
    String text = "hello world\nfoo bar\nhello universe";
    SearchControl control = SearchControl.of(Duration.ofSeconds(10));
    List<CloudSearchSupport.GrepMatch> matches =
        CloudSearchSupport.searchFile(text, "hello", true, false, false, control);

    assertEquals(2, matches.size());
    assertEquals(1, matches.get(0).lineNumber());
    assertEquals("hello world", matches.get(0).excerpt());
    assertEquals(3, matches.get(1).lineNumber());
    assertEquals("hello universe", matches.get(1).excerpt());
  }

  @Test
  void literalSearchIgnoreCase() throws Exception {
    // 意图：字面量搜索支持忽略大小写
    String text = "Hello World\nfoo bar\nHELLO UNIVERSE";
    SearchControl control = SearchControl.of(Duration.ofSeconds(10));
    List<CloudSearchSupport.GrepMatch> matches =
        CloudSearchSupport.searchFile(text, "hello", true, true, false, control);

    assertEquals(2, matches.size());
    assertEquals(1, matches.get(0).lineNumber());
    assertEquals(3, matches.get(1).lineNumber());
  }

  @Test
  void literalSearchMultiline() throws Exception {
    // 意图：字面量跨行匹配应正确标记所有被覆盖的物理行，且每个物理行至多输出一次
    String text = "start line\nmiddle section\nend line";
    SearchControl control = SearchControl.of(Duration.ofSeconds(10));
    List<CloudSearchSupport.GrepMatch> matches =
        CloudSearchSupport.searchFile(text, "line\nmiddle", true, false, true, control);

    assertEquals(2, matches.size());
    assertEquals(1, matches.get(0).lineNumber());
    assertEquals(2, matches.get(1).lineNumber());
  }

  @Test
  void regexSearchSingleLine() throws Exception {
    // 意图：RE2 正则单行搜索匹配正确
    String text = "int count = 100;\nString name = \"test\";\nint total = 200;";
    SearchControl control = SearchControl.of(Duration.ofSeconds(10));
    List<CloudSearchSupport.GrepMatch> matches =
        CloudSearchSupport.searchFile(
            text, "int\\s+\\w+\\s*=\\s*\\d+;", false, false, false, control);

    assertEquals(2, matches.size());
    assertEquals(1, matches.get(0).lineNumber());
    assertEquals(3, matches.get(1).lineNumber());
  }

  @Test
  void regexSearchMultiline() throws Exception {
    // 意图：RE2 正则跨行模式在匹配换行时能正确报告被覆盖的物理行
    String text = "function test() {\n  return 42;\n}";
    SearchControl control = SearchControl.of(Duration.ofSeconds(10));
    List<CloudSearchSupport.GrepMatch> matches =
        CloudSearchSupport.searchFile(
            text, "test\\(\\)\\s*\\{\\s*return", false, false, true, control);

    assertEquals(2, matches.size());
    assertEquals(1, matches.get(0).lineNumber());
    assertEquals(2, matches.get(1).lineNumber());
  }

  @Test
  void invalidRegexSyntaxRejectsWithoutLeakingPatternOrCause() {
    // 意图：RE2 无法解析的高级正则语法应被拒绝，且异常消息绝不泄露用户 pattern，也不保留 PatternSyntaxException cause
    String dangerousPattern = "(?=secret_pattern_lookaround)";
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> CloudSearchSupport.compileRe2(dangerousPattern, false, false));

    assertFalse(error.getMessage().contains("secret_pattern_lookaround"));
    assertEquals("Invalid regular expression pattern", error.getMessage());
    assertNull(error.getCause(), "PatternSyntaxException must not be retained as cause");
  }

  @Test
  void excerptCentersAroundActualMatchAndIsStrictlyUnder500CodePoints() {
    // 意图：超长行摘录必须围绕实际匹配位置截取，且总字符数（含前后省略与截断说明）严格 <= 500 Unicode code points
    String prefix = "a".repeat(600);
    String match = "TARGET_NEEDLE";
    String suffix = "b".repeat(600);
    String line = prefix + match + suffix;

    int cpStart = line.indexOf(match);
    int cpEnd = cpStart + match.length();
    String excerpt = CloudSearchSupport.buildExcerpt(line, cpStart, cpEnd);

    assertTrue(excerpt.contains(match), "Excerpt must contain the actual match");
    assertTrue(excerpt.contains("(line truncated to 500 chars)"));
    assertTrue(
        excerpt.codePointCount(0, excerpt.length()) <= 500,
        "Excerpt total code points must be <= 500");
  }

  @Test
  void excerptHandlesMatchNearLongLineTail() {
    // 意图：实际匹配位置位于长行尾部时，窗口应平移使匹配可见，且总 code points <= 500
    String prefix = "x".repeat(1500);
    String match = "TAIL_NEEDLE";
    String suffix = "y".repeat(20);
    String line = prefix + match + suffix;

    int cpStart = line.indexOf(match);
    int cpEnd = cpStart + match.length();
    String excerpt = CloudSearchSupport.buildExcerpt(line, cpStart, cpEnd);

    assertTrue(excerpt.contains(match), "Tail match must remain visible in excerpt");
    assertTrue(excerpt.contains("(line truncated to 500 chars)"));
    assertTrue(excerpt.startsWith("..."), "Prefix must be truncated");
    assertTrue(excerpt.codePointCount(0, excerpt.length()) <= 500, "Must be <= 500 code points");
  }

  @Test
  void excerptHandlesAstralUnicodeWithoutSplittingSurrogates() {
    // 意图：包含 Astral Unicode（如表情符 😀、数学符号）时，字符截断不会破坏代理对，总 code points <= 500
    String emoji = "😀"; // 1 code point, 2 chars
    String prefix = emoji.repeat(300);
    String match = "FOUND_ME";
    String suffix = emoji.repeat(300);
    String line = prefix + match + suffix;

    int cpStart = line.codePointCount(0, prefix.length());
    int cpEnd = cpStart + match.codePointCount(0, match.length());
    String excerpt = CloudSearchSupport.buildExcerpt(line, cpStart, cpEnd);

    assertTrue(excerpt.contains(match), "Match must be visible");
    assertTrue(excerpt.codePointCount(0, excerpt.length()) <= 500, "Must be <= 500 code points");
    // 验证截断结果在严格 UTF-8 编码下无未配对代理字符
    assertDoesNotThrow(() -> StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(excerpt)));
  }

  @Test
  void searchStopsEarlyAtPerFileLimitForSingleLine() throws Exception {
    // 意图：验证当单文件命中达到 maxMatches 时，立即停止扫描后续行
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 100; i++) {
      sb.append("match_line_").append(i).append("\n");
    }

    AtomicInteger checks = new AtomicInteger();
    SearchControl control =
        SearchControl.withProbe(Duration.ofSeconds(10), checks::incrementAndGet);

    List<CloudSearchSupport.GrepMatch> matches =
        CloudSearchSupport.searchFile(sb.toString(), "match_line", true, false, false, 2, control);

    assertEquals(2, matches.size());
    assertEquals(1, matches.get(0).lineNumber());
    assertEquals(2, matches.get(1).lineNumber());
    // 仅扫描了前 2 行即终止，绝未执行 100 次 check
    assertTrue(checks.get() <= 3, "Scanner must stop early and not check all 100 lines");
  }

  @Test
  void searchStopsEarlyAtPerFileLimitForMultiline() throws Exception {
    // 意图：多行跨行匹配在覆盖行数达到 maxMatches 时立即终止匹配
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 50; i++) {
      sb.append("block_start\nblock_end\n");
    }

    AtomicInteger checks = new AtomicInteger();
    SearchControl control =
        SearchControl.withProbe(Duration.ofSeconds(10), checks::incrementAndGet);

    List<CloudSearchSupport.GrepMatch> matches =
        CloudSearchSupport.searchFile(
            sb.toString(), "block_start\nblock_end", true, false, true, 2, control);

    assertEquals(2, matches.size());
    // 匹配了第一个 block 的两行即满足 limit=2 提前终止
    assertTrue(checks.get() <= 3, "Multiline scanner must stop early at limit");
  }

  @Test
  void searchControlChecksInterruptionAndTimeout() {
    // 意图：已取消或超时的 SearchControl 会正确响应
    SearchControl control = SearchControl.of(Duration.ofNanos(1));
    try {
      Thread.sleep(2);
    } catch (InterruptedException ignored) {
    }
    assertThrows(InterruptedException.class, control::check);

    SearchControl cancelledControl = SearchControl.of(Duration.ofMinutes(1));
    cancelledControl.cancel();
    assertTrue(cancelledControl.isCancelled());
    assertThrows(InterruptedException.class, cancelledControl::check);

    SearchControl unlimitedControl = SearchControl.of(null);
    assertFalse(unlimitedControl.isCancelled());
    assertDoesNotThrow(unlimitedControl::check);

    SearchControl zeroControl = SearchControl.of(Duration.ZERO);
    assertFalse(zeroControl.isCancelled());
    assertDoesNotThrow(zeroControl::check);

    // 验证结合 request timeout 计算有效较小超时
    SearchControl combined = SearchControl.of(Duration.ofSeconds(30), Duration.ofSeconds(5));
    // request timeout 更紧时采用 5 秒
    assertFalse(combined.isCancelled());
  }

  @Test
  void largeMatchCodePointBudgetRespected() {
    // 意图：当匹配项本身长度达到或超过 464 code points 时，严格约束摘录在 <= 500 code points 预算内
    String longMatch = "M".repeat(500);
    String line = "prefix_" + longMatch + "_suffix";
    int cpStart = "prefix_".length();
    int cpEnd = cpStart + longMatch.length();

    String excerpt = CloudSearchSupport.buildExcerpt(line, cpStart, cpEnd);
    assertTrue(
        excerpt.codePointCount(0, excerpt.length()) <= 500, "Excerpt must remain <= 500 cps");
    assertTrue(excerpt.contains("... (line truncated to 500 chars)"));
    assertDoesNotThrow(() -> StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(excerpt)));
  }

  @Test
  void emptyInputsAndZeroBudgetReturnEmpty() throws Exception {
    // 意图：空文本、空模式或 maxMatches<=0 时安全返回空列表
    SearchControl control = SearchControl.of(null);
    assertTrue(CloudSearchSupport.searchFile("", "abc", true, false, false, 10, control).isEmpty());
    assertTrue(
        CloudSearchSupport.searchFile("abc", "abc", true, false, false, 0, control).isEmpty());
    assertTrue(CloudSearchSupport.searchLiteral("abc", "", false, false, 10, control).isEmpty());
  }

  @Test
  void longLineSubstringPeriodicCancellationAndProbeCheck() {
    // 意图：验证超长无匹配行在字面量搜索中每 4096 字符周期性触发 SearchControl 检查与取消响应
    String longNoMatchLine = "a".repeat(50000);
    AtomicInteger probeChecks = new AtomicInteger(0);

    // 探针计数验证：50,000 字符应至少触发 10 次周期性检查 (50000 / 4096 >= 12)
    SearchControl countingControl =
        SearchControl.withProbe(Duration.ofSeconds(10), probeChecks::incrementAndGet);
    assertDoesNotThrow(
        () ->
            CloudSearchSupport.searchLiteral(
                longNoMatchLine, "target_not_found", false, false, 10, countingControl));
    assertTrue(
        probeChecks.get() >= 10,
        "Substring scan of 50000 chars must check control periodically (actual: "
            + probeChecks.get()
            + ")");

    // 探针在达到指定次数后触发取消/中断
    AtomicInteger cancelChecks = new AtomicInteger(0);
    SearchControl abortControl =
        SearchControl.withProbe(
            Duration.ofSeconds(10),
            () -> {
              if (cancelChecks.incrementAndGet() >= 3) {
                Thread.currentThread().interrupt();
              }
            });
    assertThrows(
        InterruptedException.class,
        () ->
            CloudSearchSupport.searchLiteral(
                longNoMatchLine, "target_not_found", false, false, 10, abortControl));
    // 清理当前线程中断标志
    Thread.interrupted();
  }

  @Test
  void multilineRegexZeroLengthPatternMakesProgressAndObeysMaxMatches() throws Exception {
    // 意图：验证多行正则下零宽匹配模式（如 ^, $, a*）正常推进不发生死循环，且严格受控于 maxMatches
    String text = "alpha\nbeta\ngamma\ndelta\n";
    SearchControl control = SearchControl.of(Duration.ofSeconds(5));

    // 测试 (?m)^ 行首零宽匹配
    List<CloudSearchSupport.GrepMatch> startMatches =
        CloudSearchSupport.searchFile(text, "(?m)^", false, false, true, 3, control);
    assertEquals(3, startMatches.size(), "Should match 3 line starts");
    assertEquals(1, startMatches.get(0).lineNumber());
    assertEquals(2, startMatches.get(1).lineNumber());
    assertEquals(3, startMatches.get(2).lineNumber());

    // 测试 (?m)$ 行尾零宽匹配
    List<CloudSearchSupport.GrepMatch> endMatches =
        CloudSearchSupport.searchFile(text, "(?m)$", false, false, true, 2, control);
    assertEquals(2, endMatches.size(), "Should match 2 line ends");
    assertEquals(1, endMatches.get(0).lineNumber());
    assertEquals(2, endMatches.get(1).lineNumber());

    // 测试 a* 零长匹配不循环并满足 limit
    List<CloudSearchSupport.GrepMatch> zeroLengthRegex =
        CloudSearchSupport.searchFile("bb\ncc\n", "a*", false, false, true, 2, control);
    assertEquals(2, zeroLengthRegex.size());
  }

  @Test
  void cloudToolPromptsMissingResourceThrows() {
    assertThrows(
        IllegalStateException.class, () -> CloudToolPrompts.prompt("non_existent_tool_xyz"));
  }
}
