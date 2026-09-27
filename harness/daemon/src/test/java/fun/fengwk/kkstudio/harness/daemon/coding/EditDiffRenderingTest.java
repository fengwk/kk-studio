package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link EditCapability} 的 diff 契约测试：展示的必须是结果文件真实、完整的行级变化。
 *
 * <p>用例来源与改写理由记录在 docs/operations/builtin-mutation-tests.md。其中上下文折叠规则与 pi-base 一致（每个变更块两侧各 4 行、
 * 前导块保留最靠近变更块的行、尾随块保留最靠近变更块的行、块间按两侧各一半折叠），只有行号宽度下限不同：kk 固定右对齐到至少 2 位， 因此 pi-base 中 1 位宽度的期望（如
 * {@code -1|old}）在 kk 写作 {@code - 1|old}。
 */
class EditDiffRenderingTest {

  private static final Pattern CONTEXT_ELISION = Pattern.compile("^\\.\\.\\.$", Pattern.MULTILINE);

  @TempDir Path workdir;
  private ExecutorService executor;

  @BeforeEach
  void setUp() {
    executor = Executors.newCachedThreadPool();
  }

  @AfterEach
  void tearDown() {
    executor.shutdownNow();
  }

  private EditCapability edit() {
    return new EditCapability(TestCodingConfig.withLsp(workdir), executor);
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static String json(String value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception error) {
      throw new IllegalStateException(error);
    }
  }

  private static String text(EnvironmentCapabilityResult result) {
    return ((TextResultContent) result.contents().getFirst()).text();
  }

  /** 写入按 LF 拼接的行内容，交由 edit 的读取路径产生真实文件。 */
  private void writeLines(String path, List<String> lines) throws Exception {
    Files.write(
        workdir.resolve(path), (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
  }

  private static List<String> numbered(String prefix, int count) {
    List<String> lines = new ArrayList<>(count);
    for (int index = 1; index <= count; index++) {
      lines.add(prefix + String.format("%02d", index));
    }
    return lines;
  }

  private EnvironmentCapabilityResult editLines(
      String path, String oldString, String newString, boolean replaceAll) throws Exception {
    EditCapability capability = edit();
    String arguments =
        "{\"path\":"
            + json(path)
            + ",\"old_string\":"
            + json(oldString)
            + ",\"new_string\":"
            + json(newString)
            + ",\"replace_all\":"
            + replaceAll
            + ",\"workdir\":"
            + json(workdir.toString())
            + "}";
    CountDownLatch latch = new CountDownLatch(1);
    List<EnvironmentCapabilityResult> results = new ArrayList<>(1);
    capability.execute(
        new EnvironmentCapabilityExecutionRequest(
            capability.descriptor(),
            new EnvironmentCapabilityCall("call", arguments),
            Duration.ofSeconds(60)),
        new EnvironmentCapabilityExecutionListener() {
          @Override
          public void onComplete(EnvironmentCapabilityResult result) {
            results.add(result);
            latch.countDown();
          }

          @Override
          public void onError(Throwable error) {
            results.add(
                EnvironmentCapabilityResult.error("call", String.valueOf(error.getMessage())));
            latch.countDown();
          }
        });
    assertTrue(latch.await(60, TimeUnit.SECONDS), "edit did not settle in time");
    return results.get(0);
  }

  /** 从成功结果中取出 diff 正文；结果必须成功且包含 diff。 */
  private static String diffOf(EnvironmentCapabilityResult result) {
    assertFalse(result.error(), text(result));
    String body = text(result);
    int start = body.indexOf("\n\ndiff:\n");
    assertTrue(start >= 0, "结果应包含 diff 段：" + body);
    return body.substring(start + "\n\ndiff:\n".length());
  }

  private static int countMatches(String text, Pattern pattern) {
    Matcher matcher = pattern.matcher(text);
    int count = 0;
    while (matcher.find()) {
      count++;
    }
    return count;
  }

  // ------------------------------------------------------- trailing context

  /**
   * 来源：pi-base edit-diff「trailing context / keeps the first contextLines lines closest to the
   * preceding hunk」。
   */
  @Test
  void trailingContextKeepsOnlyLinesClosestToHunk() throws Exception {
    List<String> lines = new ArrayList<>();
    lines.add("head-1");
    lines.add("TO-EDIT");
    lines.addAll(numbered("tail-", 12));
    writeLines("trail.txt", lines);

    String diff = diffOf(editLines("trail.txt", "TO-EDIT", "EDITED", false));

    assertTrue(diff.contains("tail-01"), diff);
    assertTrue(diff.contains("tail-04"), diff);
    assertFalse(diff.contains("tail-05"), diff);
    assertFalse(diff.contains("tail-12"), diff);
    assertTrue(diff.contains("..."), diff);
  }

  /** 来源：pi-base edit-diff「emits no '...' for trailing context of exactly contextLines lines」。 */
  @Test
  void trailingContextOfExactlyContextLinesHasNoElision() throws Exception {
    List<String> lines = new ArrayList<>();
    lines.add("head-1");
    lines.add("TO-EDIT");
    lines.addAll(numbered("tail-", 4));
    writeLines("trail4.txt", lines);

    String diff = diffOf(editLines("trail4.txt", "TO-EDIT", "EDITED", false));

    assertFalse(diff.contains("..."), diff);
    assertTrue(diff.contains("tail-04"), diff);
  }

  /** 来源：pi-base edit-diff「emits a single '...' for trailing context of contextLines + 1 lines」。 */
  @Test
  void trailingContextOfContextLinesPlusOneElidesOnce() throws Exception {
    List<String> lines = new ArrayList<>();
    lines.add("head-1");
    lines.add("TO-EDIT");
    lines.addAll(numbered("tail-", 5));
    writeLines("trail5.txt", lines);

    String diff = diffOf(editLines("trail5.txt", "TO-EDIT", "EDITED", false));

    assertTrue(diff.contains("..."), diff);
    assertTrue(diff.contains("tail-04"), diff);
    assertFalse(diff.contains("tail-05"), diff);
  }

  /** 来源：pi-base edit-diff「does not preserve the trailing tail (matches git, not head+...+tail)」。 */
  @Test
  void trailingContextDoesNotPreserveFileTail() throws Exception {
    List<String> lines = new ArrayList<>();
    lines.add("head-1");
    lines.add("TO-EDIT");
    lines.addAll(numbered("tail-", 50));
    writeLines("trail50.txt", lines);

    String diff = diffOf(editLines("trail50.txt", "TO-EDIT", "EDITED", false));

    assertTrue(diff.contains("tail-01"), diff);
    assertTrue(diff.contains("tail-04"), diff);
    assertFalse(diff.contains("tail-47"), diff);
    assertFalse(diff.contains("tail-50"), diff);
  }

  // -------------------------------------------------------- leading context

  /**
   * 来源：pi-base edit-diff「leading context / keeps the last contextLines lines closest to the
   * following hunk」。
   */
  @Test
  void leadingContextKeepsOnlyLinesClosestToHunk() throws Exception {
    List<String> lines = new ArrayList<>(numbered("head-", 12));
    lines.add("TO-EDIT");
    writeLines("lead.txt", lines);

    String diff = diffOf(editLines("lead.txt", "TO-EDIT", "EDITED", false));

    assertTrue(diff.contains("head-09"), diff);
    assertTrue(diff.contains("head-12"), diff);
    assertFalse(diff.contains("head-08"), diff);
    assertFalse(diff.contains("head-01"), diff);
    assertTrue(diff.contains("..."), diff);
  }

  /** 来源：pi-base edit-diff「emits no '...' for leading context of exactly contextLines lines」。 */
  @Test
  void leadingContextOfExactlyContextLinesHasNoElision() throws Exception {
    List<String> lines = new ArrayList<>(numbered("head-", 4));
    lines.add("TO-EDIT");
    writeLines("lead4.txt", lines);

    String diff = diffOf(editLines("lead4.txt", "TO-EDIT", "EDITED", false));

    assertFalse(diff.contains("..."), diff);
    assertTrue(diff.contains("head-01"), diff);
    assertTrue(diff.contains("head-04"), diff);
  }

  /** 来源：pi-base edit-diff「emits a single '...' for leading context of contextLines + 1 lines」。 */
  @Test
  void leadingContextOfContextLinesPlusOneElidesOnce() throws Exception {
    List<String> lines = new ArrayList<>(numbered("head-", 5));
    lines.add("TO-EDIT");
    writeLines("lead5.txt", lines);

    String diff = diffOf(editLines("lead5.txt", "TO-EDIT", "EDITED", false));

    assertTrue(diff.contains("..."), diff);
    assertTrue(diff.contains("head-05"), diff);
    assertTrue(diff.contains("head-02"), diff);
    assertFalse(diff.contains("head-01"), diff);
  }

  // --------------------------------------------------------- inter-hunk gap

  /** 来源：pi-base edit-diff「inter-hunk context / keeps both ends when the gap is short enough」。 */
  @Test
  void shortInterHunkGapRendersWithoutElision() throws Exception {
    writeLines("short.txt", List.of("L1", "L2", "L3", "L4", "CHANGE", "L6", "L7", "L8"));

    String diff = diffOf(editLines("short.txt", "CHANGE", "EDITED", false));

    assertFalse(diff.contains("..."), diff);
    assertTrue(diff.contains("EDITED"), diff);
  }

  /**
   * 来源：pi-base edit-diff「inserts exactly one '...' when two hunks are separated by more than
   * 2*contextLines」。
   */
  @Test
  void distantHunksElideExactlyOnce() throws Exception {
    List<String> lines = numbered("ctx-", 30);
    lines.set(2, "MARKER");
    lines.set(27, "MARKER");
    writeLines("g.txt", lines);

    String diff = diffOf(editLines("g.txt", "MARKER", "MARKER-new", true));

    assertEquals(1, countMatches(diff, CONTEXT_ELISION), diff);
    assertEquals(2, countMatches(diff, Pattern.compile("MARKER-new")), diff);
  }

  /**
   * 来源：pi-base edit-diff「merges two hunks without a '...' when their gap fits within
   * 2*contextLines」。
   */
  @Test
  void mergedHunksWithinDoubleContextRenderWithoutElision() throws Exception {
    List<String> lines = numbered("ctx-", 13);
    lines.set(2, "MARKER");
    lines.set(10, "MARKER");
    writeLines("h.txt", lines);

    String diff = diffOf(editLines("h.txt", "MARKER", "MARKER-new", true));

    assertFalse(diff.contains("..."), diff);
    assertEquals(2, countMatches(diff, Pattern.compile("MARKER-new")), diff);
  }

  /**
   * 来源：pi-base edit-diff「emits no '...' for an inter-hunk block of exactly 2*contextLines lines」。
   */
  @Test
  void interHunkBlockOfExactlyDoubleContextHasNoElision() throws Exception {
    List<String> lines = numbered("ctx-", 14);
    lines.set(1, "MARKER");
    lines.set(10, "MARKER");
    writeLines("h8.txt", lines);

    String diff = diffOf(editLines("h8.txt", "MARKER", "MARKER-new", true));

    assertFalse(diff.contains("..."), diff);
    assertEquals(2, countMatches(diff, Pattern.compile("MARKER-new")), diff);
  }

  /**
   * 来源：pi-base edit-diff「emits a single '...' for an inter-hunk block of 2*contextLines + 1 lines」。
   */
  @Test
  void interHunkBlockJustOverDoubleContextElidesExactlyOnce() throws Exception {
    List<String> lines = numbered("ctx-", 15);
    lines.set(1, "MARKER");
    lines.set(11, "MARKER");
    writeLines("h9.txt", lines);

    String diff = diffOf(editLines("h9.txt", "MARKER", "MARKER-new", true));

    assertEquals(1, countMatches(diff, CONTEXT_ELISION), diff);
    assertTrue(diff.contains("ctx-03"), diff);
    assertTrue(diff.contains("ctx-06"), diff);
    assertTrue(diff.contains("ctx-08"), diff);
    assertTrue(diff.contains("ctx-11"), diff);
    assertFalse(diff.contains("ctx-07"), diff);
  }

  /** 来源：pi-base edit-diff「emits exactly two '...' for three hunks with mixed gap sizes」。 */
  @Test
  void threeHunksWithMixedGapsElideTwice() throws Exception {
    List<String> lines = numbered("ctx-", 40);
    lines.set(2, "MARKER");
    lines.set(15, "MARKER");
    lines.set(19, "MARKER");
    writeLines("three.txt", lines);

    String diff = diffOf(editLines("three.txt", "MARKER", "MARKER-new", true));

    assertEquals(3, countMatches(diff, Pattern.compile("MARKER-new")), diff);
    assertEquals(2, countMatches(diff, CONTEXT_ELISION), diff);
  }

  /**
   * 来源：pi-base edit-diff「renders three adjacent hunks (no context between them) without any fold」。
   */
  @Test
  void adjacentHunksRenderWithoutElision() throws Exception {
    writeLines("adjacent.txt", List.of("MARKER", "MARKER", "MARKER", "tail"));

    String diff = diffOf(editLines("adjacent.txt", "MARKER", "MARKER-new", true));

    assertEquals(3, countMatches(diff, Pattern.compile("MARKER-new")), diff);
    assertFalse(diff.contains("..."), diff);
  }

  // ------------------------------------------------- error and degenerate

  /** 来源：pi-base edit-diff「handles a file with no changes by emitting an empty diff」。 */
  @Test
  void identicalReplacementIsRejectedWithoutDiff() throws Exception {
    writeLines("nochange.txt", List.of("L1", "L2", "L3", "L4", "L5"));

    EnvironmentCapabilityResult result = editLines("nochange.txt", "L3", "L3", false);

    assertTrue(result.error());
    assertTrue(text(result).contains("No changes to apply"), text(result));
    assertFalse(text(result).contains("diff:"), text(result));
  }

  /** 来源：pi-base edit-diff「handles a single-line change with no surrounding context」。 */
  @Test
  void singleLineChangeRendersExactHunk() throws Exception {
    Files.write(workdir.resolve("single.txt"), "old\n".getBytes(StandardCharsets.UTF_8));

    String diff = diffOf(editLines("single.txt", "old", "new", false));

    assertTrue(diff.contains("- 1|old"), diff);
    assertTrue(diff.contains("+ 1|new"), diff);
    assertFalse(diff.contains("..."), diff);
  }

  /** 来源：pi-base edit-diff「handles a whole-file replacement without invoking appendContextBlock」。 */
  @Test
  void wholeFileReplacementRendersEveryLine() throws Exception {
    List<String> lines = List.of("alpha", "beta", "gamma");
    writeLines("all.txt", lines);

    String diff =
        diffOf(
            editLines("all.txt", String.join("\n", lines) + "\n", "ALPHA\nBETA\nGAMMA\n", false));

    assertTrue(diff.contains("- 1|alpha"), diff);
    assertTrue(diff.contains("- 2|beta"), diff);
    assertTrue(diff.contains("- 3|gamma"), diff);
    assertTrue(diff.contains("+ 1|ALPHA"), diff);
    assertTrue(diff.contains("+ 2|BETA"), diff);
    assertTrue(diff.contains("+ 3|GAMMA"), diff);
    assertFalse(diff.contains("..."), diff);
  }

  /** 来源：pi-base edit-diff「renders a hunk at the very first line with long trailing context」。 */
  @Test
  void hunkAtFirstLineWithLongTrailingContext() throws Exception {
    List<String> lines = new ArrayList<>();
    lines.add("FIRST-LINE-EDITED");
    lines.addAll(numbered("tail-", 12));
    writeLines("first.txt", lines);

    String diff = diffOf(editLines("first.txt", "FIRST-LINE-EDITED", "FIRST-LINE-NEW", false));

    assertTrue(diff.contains("- 1|FIRST-LINE-EDITED"), diff);
    assertTrue(diff.contains("+ 1|FIRST-LINE-NEW"), diff);
    assertTrue(diff.contains("tail-01"), diff);
    assertTrue(diff.contains("tail-04"), diff);
    assertFalse(diff.contains("tail-05"), diff);
    assertTrue(diff.contains("..."), diff);
  }

  /** 来源：pi-base edit-diff「renders a hunk at the very last line with long leading context」。 */
  @Test
  void hunkAtLastLineWithLongLeadingContext() throws Exception {
    List<String> lines = new ArrayList<>(numbered("head-", 12));
    lines.add("LAST-LINE-EDITED");
    writeLines("last.txt", lines);

    String diff = diffOf(editLines("last.txt", "LAST-LINE-EDITED", "LAST-LINE-NEW", false));

    assertTrue(diff.contains("-13|LAST-LINE-EDITED"), diff);
    assertTrue(diff.contains("+13|LAST-LINE-NEW"), diff);
    assertTrue(diff.contains("head-09"), diff);
    assertTrue(diff.contains("head-12"), diff);
    assertFalse(diff.contains("head-08"), diff);
    assertTrue(diff.contains("..."), diff);
  }

  /**
   * 来源：pi-base edit-diff「real-world regression / renders a long-edit 3-line block + 10-line
   * trailing context」。
   */
  @Test
  void realWorldLongEditRendersTightFold() throws Exception {
    List<String> lines =
        List.of(
            "时光旅人",
            "",
            "我叫苏晚，今年二十八岁，是一个普通的图书管理员。每天的工作就是整理书籍、帮读者查找资料。",
            "",
            "那天晚上，我正准备下班关门，忽然发现门口有一封信。",
            "",
            "带着满心的疑惑，我小心翼翼地打开信，里面只有短短几行字。",
            "",
            "读完这封信，我第一反应是觉得荒谬。",
            "",
            "那天晚上回到家，我躺在床上辗转反侧，脑海里不断浮现信上的那些字。",
            "",
            "第二天是周六，我有一整天的休息时间。",
            "",
            "子夜时分，我站在了城南废弃的钟楼前。");
    writeLines("story.txt", lines);

    String diff =
        diffOf(
            editLines(
                "story.txt",
                "时光旅人\n\n我叫苏晚，今年二十八岁，是一个普通的图书管理员。",
                "时光旅人：觉醒之路\n\n我叫苏晚，今年二十八岁，是一名普通的图书管理员。",
                false));

    assertTrue(diff.contains("- 1|时光旅人"), diff);
    assertTrue(diff.contains("+ 1|时光旅人：觉醒之路"), diff);
    assertTrue(diff.contains("- 3|我叫苏晚"), diff);
    assertTrue(diff.contains("+ 3|我叫苏晚"), diff);
    assertTrue(diff.contains("  2|"), diff);
    assertTrue(diff.contains("..."), diff);
    assertTrue(diff.contains("那天晚上"), diff);
    assertFalse(diff.contains("读完这封信"), diff);
    assertFalse(diff.contains("第二天是周六，我"), diff);
    assertFalse(diff.contains("子夜时分，我站"), diff);
  }

  // ------------------------------------------------- 结果行真实性回归用例

  /**
   * 缺陷回归：diff 曾经直接输出 new_string 的行，丢失同行前缀，展示的不是结果文件真实内容。
   *
   * <p>这里替换发生在行的中间且 new_string 是多行，结果文件的第一行必须是 {@code foo baz}。
   */
  @Test
  void reportsRealResultingLinesInsteadOfRawReplacementText() throws Exception {
    Files.write(workdir.resolve("inline.txt"), "foo bar\n".getBytes(StandardCharsets.UTF_8));

    String diff = diffOf(editLines("inline.txt", "bar", "baz\nqux", false));

    assertEquals("foo baz\nqux\n", Files.readString(workdir.resolve("inline.txt")));
    assertTrue(diff.contains("+ 1|foo baz"), diff);
    assertTrue(diff.contains("+ 2|qux"), diff);
    assertFalse(diff.contains("+ 1|baz"), diff);
  }

  /**
   * 缺陷回归：跨行替换（删除包含换行的 old_string）曾经把相邻行也画成删除行，虚报未发生的行变化。
   *
   * <p>实际结果只是删掉了 b，c 仍是上下文行，并且行号按结果文件重新编号。
   */
  @Test
  void deletingAcrossLineBoundaryDoesNotClaimNeighbourRemoved() throws Exception {
    Files.write(workdir.resolve("delete.txt"), "a\nb\nc\n".getBytes(StandardCharsets.UTF_8));

    String diff = diffOf(editLines("delete.txt", "b\n", "", false));

    assertEquals("a\nc\n", Files.readString(workdir.resolve("delete.txt")));
    assertTrue(diff.contains("- 2|b"), diff);
    assertTrue(diff.contains("  3|c"), diff);
    assertFalse(diff.contains("- 3|c"), diff);
  }

  /** 超过内联上限的 diff 被截断并给出确定性标记；同时这条用例覆盖大变更区域的退化对齐路径（超出 LCS 单元格上限）。 */
  @Test
  void hugeDiffIsTruncatedWithMarker() throws Exception {
    StringBuilder original = new StringBuilder();
    StringBuilder replacement = new StringBuilder();
    for (int index = 0; index < 1600; index++) {
      original.append("old-chunk-").append(index).append('\n');
      replacement.append("new-chunk-").append(index).append('\n');
    }
    Files.write(workdir.resolve("huge.txt"), original.toString().getBytes(StandardCharsets.UTF_8));

    EnvironmentCapabilityResult result =
        editLines("huge.txt", original.toString(), replacement.toString(), false);

    assertFalse(result.error(), text(result));
    String diff = diffOf(result);
    assertTrue(
        diff.contains("... (diff truncated to fit inline limit)"),
        diff.substring(diff.length() - 200));
    assertTrue(diff.length() <= 30 * 1024 + 60, "diff 长度 " + diff.length());
    assertTrue(diff.contains("|old-chunk-0"), diff.substring(0, 200));
    assertEquals(replacement.toString(), Files.readString(workdir.resolve("huge.txt")));
  }

  /**
   * 超限截断不得拆开代理对：截断点必须落在代码点边界上，输出正文不得含孤立代理。
   *
   * <p>构造：old 行取 4 个字符，使前缀 {@code - 1|olde\n+ 1|} 为奇数长度 13，于是第 {@code 30*1024} 个字符（1-based）正好落在
   * 60000 个 emoji 的某个代理对前半；朴素 {@code substring(0, maxChars)} 会留下孤立高代理，修正后回退 1 个 char。
   */
  @Test
  void truncatedDiffDoesNotSplitEmojiSurrogatePairs() throws Exception {
    String emoji = "\uD83D\uDE00";
    String added = emoji.repeat(60000);
    Files.write(workdir.resolve("emoji.txt"), "olde\n".getBytes(StandardCharsets.UTF_8));

    EnvironmentCapabilityResult result = editLines("emoji.txt", "olde", added, false);

    assertFalse(result.error(), text(result));
    String diff = diffOf(result);
    String marker = "\n... (diff truncated to fit inline limit)";
    assertTrue(diff.contains(marker), "应触发内联截断");
    String body = diff.substring(0, diff.indexOf(marker));
    int maxChars = 30 * 1024;
    // 边界恰好切在代理对中间：截断后正文比上限少 1 个 char，并以完整 emoji 结尾。
    assertEquals(maxChars - 1, body.length(), "截断点必须回退到代码点边界");
    assertTrue(body.endsWith(emoji), "截断正文必须以完整 emoji 结尾");
    for (int index = 0; index < body.length(); index++) {
      char current = body.charAt(index);
      boolean pairedLow =
          Character.isLowSurrogate(current)
              && index > 0
              && Character.isHighSurrogate(body.charAt(index - 1));
      boolean pairedHigh =
          Character.isHighSurrogate(current)
              && index + 1 < body.length()
              && Character.isLowSurrogate(body.charAt(index + 1));
      assertFalse(
          (Character.isLowSurrogate(current) && !pairedLow)
              || (Character.isHighSurrogate(current) && !pairedHigh),
          "截断正文出现孤立代理 @ " + index);
    }
  }
}
