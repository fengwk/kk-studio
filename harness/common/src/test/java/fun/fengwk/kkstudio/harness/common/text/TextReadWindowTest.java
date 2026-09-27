package fun.fengwk.kkstudio.harness.common.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.MalformedInputException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link TextReadWindow} 的行为测试：分页窗口、正文预算、Unicode/换行精确性、截断元数据、失败映射与 BOM 剥离边界。
 *
 * <p>本类守护本地与受管读取共用的状态机本身；两侧适配器（本地 {@code LocalTextReadWindow}、受管 {@code ReadTextWindow}）另有端到端用例，
 * 覆盖解码、BOM、超时与异常映射。
 *
 * <p>长文本 fixture 由确定性生成（{@code "a".repeat(n)}）而不是资源文件：用例唯一的事实就是“精确到某个码点/行的长度”。
 */
class TextReadWindowTest {

  private static final Runnable NO_CHECKPOINT = () -> {};

  private static String read(String content, Integer offset, Integer limit, Integer columnOffset) {
    try {
      return TextReadWindow.read(
          new StringReader(content),
          offset == null ? 1 : offset,
          limit == null ? TextReadWindow.DEFAULT_LIMIT : limit,
          columnOffset,
          "p",
          "unsupported",
          NO_CHECKPOINT);
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  /** 空输入与越过 EOF 都输出明确空范围，不伪造空正文行。 */
  @Test
  void emptyAndBeyondEofProduceEmptyRange() {
    assertEquals(
        String.join("\n", "path: p", "ends_with_newline: no", "range: empty"),
        read("", 1, 200, null));
    assertEquals(
        String.join("\n", "path: p", "ends_with_newline: yes", "range: empty"),
        read("one\ntwo\n", 7, 200, null));
  }

  /** 分页窗口：offset/limit 生效，未到 EOF 时给出 line_limit 与下一行位置，行号按总行数宽度右对齐。 */
  @Test
  void windowMetadataAndRightAlignedLineNumbers() {
    StringBuilder content = new StringBuilder();
    for (int index = 1; index <= 12; index++) {
      content.append("line-").append(index).append('\n');
    }

    assertEquals(
        String.join(
            "\n",
            "path: p",
            "ends_with_newline: yes",
            "range: 9:1-11:7",
            "truncated: yes",
            "truncation_reason: line_limit",
            "next: 12:1",
            "",
            " 9|line-9",
            "10|line-10",
            "11|line-11",
            "",
            "[TRUNCATED: More file content remains. Next position: line 12, column 1.]"),
        read(content.toString(), 9, 3, null));
  }

  /** CRLF、孤立 CR 与 LF 都只作为行边界，不进入正文。 */
  @Test
  void crlfAndLoneCrAreLineBoundaries() {
    assertEquals(
        String.join(
            "\n", "path: p", "ends_with_newline: no", "range: 1:1-3:1", "", "1|a", "2|b", "3|c"),
        read("a\r\nb\rc", 1, 200, null));
  }

  /** column_offset 只作用于起始行片段，不要求 limit=1，也不作用于后续行。 */
  @Test
  void columnOffsetSlicesOnlyFirstLine() {
    assertEquals(
        String.join(
            "\n",
            "path: p",
            "ends_with_newline: yes",
            "range: 1:4-2:6",
            "",
            "1|defghij",
            "2|klmnop"),
        read("abcdefghij\nklmnop\n", 1, 200, 4));
  }

  /** 有效目标行上的越界列报错；无字符空行的合法端点是第 1 列且正文为空串。 */
  @Test
  void columnOffsetBeyondLineLengthThrowsWhileEmptyLineEndpointIsValid() {
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> read("abc", 1, 200, 10));
    assertEquals("column_offset 10 is out of range: line 1 has 3 columns", failure.getMessage());

    assertEquals(
        String.join("\n", "path: p", "ends_with_newline: yes", "range: 1:1-1:1", "", "1|"),
        read("\n", 1, 200, 1));
  }

  /** 正文预算在行内命中时按码点截断，续读位置指向第一个未返回字符。 */
  @Test
  void characterBudgetCutsMidLineAndPointsAtNextColumn() {
    String result = read("a".repeat(70000), 1, 2000, null);

    assertTrue(
        result.startsWith(
            String.join(
                "\n",
                "path: p",
                "ends_with_newline: no",
                "range: 1:1-1:60000",
                "truncated: yes",
                "truncation_reason: character_limit",
                "next: 1:60001",
                "")),
        result);
    String body = result.lines().filter(line -> line.startsWith("1|")).findFirst().orElseThrow();
    assertEquals(60000, body.substring(2).length());
  }

  /** 预算恰好命中 EOF 不算截断；预算在行尾用尽而仍有后续行时归一到下一行第 1 列。 */
  @Test
  void characterBudgetBoundariesAtEofAndLineEnd() {
    String exact = read("a".repeat(60000), 1, 2000, null);
    assertTrue(exact.contains("range: 1:1-1:60000"), exact);
    assertFalse(exact.contains("truncated"), exact);

    String lineEnd = read("a".repeat(60000) + "\ntail\n", 1, 2000, null);
    assertTrue(lineEnd.contains("truncation_reason: character_limit"), lineEnd);
    assertTrue(lineEnd.contains("next: 2:1"), lineEnd);
  }

  /** 增补平面码点在预算边界按完整码点切断，并跨读取块合并被拆开的代理项。 */
  @Test
  void supplementaryCodePointsAreNeverSplit() {
    String result = read("a".repeat(59999) + "🚀" + "b".repeat(5), 1, 2000, null);
    String body = result.lines().filter(line -> line.startsWith("1|")).findFirst().orElseThrow();
    String fragment = body.substring(2);
    assertEquals(60000, fragment.codePointCount(0, fragment.length()));
    assertTrue(fragment.endsWith("🚀"), fragment.substring(fragment.length() - 4));
    assertTrue(result.contains("next: 1:60001"), result);

    String combined = readInChunks("a\uD83D\uDE80b", 1);
    assertTrue(combined.contains("range: 1:1-1:3"), combined);
    assertEquals(
        "a🚀b",
        combined
            .lines()
            .filter(line -> line.startsWith("1|"))
            .findFirst()
            .orElseThrow()
            .substring(2));
  }

  /** 孤立或悬挂的代理项按二进制拒绝，绝不把半个码点写入正文。 */
  @Test
  void malformedSurrogatesAreRejected() {
    assertThrows(IllegalArgumentException.class, () -> read("a\uD800b", 1, 200, null));
    assertThrows(IllegalArgumentException.class, () -> read("a\uDC00b", 1, 200, null));
    assertThrows(IllegalArgumentException.class, () -> read("a\uD800", 1, 200, null));
    assertThrows(IllegalArgumentException.class, () -> readInChunks("a\uD800b", 2));
    assertThrows(
        IllegalArgumentException.class, () -> readInChunks("b".repeat(8191) + "\uD800", 8192));
  }

  /** NUL 码点判定为二进制，与“空正文”区分。 */
  @Test
  void nulCodePointIsBinary() {
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> read("hi\u0000x", 1, 200, null));
    assertTrue(failure.getMessage().contains("binary"), failure.getMessage());
  }

  /** 严格解码器抛出的编码异常在读取契约上统一为“看似二进制”。 */
  @Test
  void decoderFailuresAreReportedAsBinary() {
    Reader failing =
        new Reader() {
          @Override
          public int read(char[] buffer, int offset, int length) throws IOException {
            throw new MalformedInputException(1);
          }

          @Override
          public void close() {}
        };

    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> TextReadWindow.read(failing, 1, 200, null, "p", "unsupported", NO_CHECKPOINT));
    assertTrue(failure.getMessage().contains("invalid text encoding"), failure.getMessage());
  }

  /** 检查点在每块读取前后各执行一次，并且它抛出的异常原样中止扫描（超时/中断映射依赖该行为）。 */
  @Test
  void checkpointRunsAroundEveryBlockAndAbortsTheScan() throws IOException {
    AtomicInteger calls = new AtomicInteger();
    TextReadWindow.read(
        new StringReader("a\nb\n"), 1, 200, null, "p", "unsupported", calls::incrementAndGet);
    assertEquals(3, calls.get(), "一块数据 + 终止读取：pre、post、pre");

    IllegalStateException abort = new IllegalStateException("deadline");
    AtomicInteger seen = new AtomicInteger();
    IllegalStateException threw =
        assertThrows(
            IllegalStateException.class,
            () ->
                TextReadWindow.read(
                    new StringReader("a\nb\n"),
                    1,
                    200,
                    null,
                    "p",
                    "unsupported",
                    () -> {
                      if (seen.incrementAndGet() > 1) {
                        throw abort;
                      }
                    }));
    assertSame(abort, threw);
  }

  /** lsp 是 header 最后一行：可用时输出，{@code unsupported} 或 null 时省略。 */
  @Test
  void lspHeaderIsLastAndOmittedWhenUnavailable() throws IOException {
    String available =
        TextReadWindow.read(
            new StringReader("one\n"), 1, 200, null, "p", "supported (java)", NO_CHECKPOINT);
    assertTrue(
        available.startsWith(
            "path: p\nends_with_newline: yes\nrange: 1:1-1:3\nlsp: supported (java)\n\n1|one"),
        available);

    assertFalse(
        TextReadWindow.read(
                new StringReader("one\n"), 1, 200, null, "p", "unsupported", NO_CHECKPOINT)
            .contains("lsp:"));
    assertFalse(
        TextReadWindow.read(new StringReader("one\n"), 1, 200, null, "p", null, NO_CHECKPOINT)
            .contains("lsp:"));
  }

  /** BOM 剥离严格只作用于首个字符：正文里后续出现的 {@code \uFEFF} 必须保留。 */
  @Test
  void withoutLeadingBomStripsOnlyTheVeryFirstCharacter() throws IOException {
    assertEquals("x", withoutLeadingBomRead("\uFEFFx"));
    assertEquals("\n\uFEFFx", withoutLeadingBomRead("\n\uFEFFx"));
    assertEquals("😀\uFEFFx", withoutLeadingBomRead("😀\uFEFFx"));
    assertEquals("", withoutLeadingBomRead(""));
    assertEquals("", withoutLeadingBomRead("\uFEFF"));
    assertEquals("x", withoutLeadingBomReadInChunks("\uFEFFx", 1));
  }

  /** 协议位置是 int：范围内的位置原样返回，越界位置显式拒绝而不截断成伪造值。 */
  @Test
  void protocolPositionOverflowIsRejected() {
    assertEquals(
        Integer.MAX_VALUE, TextReadWindow.requireProtocolPosition(Integer.MAX_VALUE, "line"));
    IllegalArgumentException failure =
        assertThrows(
            IllegalArgumentException.class,
            () -> TextReadWindow.requireProtocolPosition(Integer.MAX_VALUE + 1L, "line"));
    assertTrue(failure.getMessage().contains("line=2147483648"), failure.getMessage());
  }

  private static String withoutLeadingBomRead(String content) throws IOException {
    return withoutLeadingBomReadInChunks(content, content.length() + 1);
  }

  private static String withoutLeadingBomReadInChunks(String content, int chunk)
      throws IOException {
    try (Reader reader = TextReadWindow.withoutLeadingBom(chunked(content, chunk))) {
      StringBuilder text = new StringBuilder();
      char[] buffer = new char[8];
      int count;
      while ((count = reader.read(buffer)) != -1) {
        text.append(buffer, 0, count);
      }
      return text.toString();
    }
  }

  private static String readInChunks(String content, int chunk) {
    try {
      return TextReadWindow.read(
          chunked(content, chunk), 1, 2000, null, "p", "unsupported", NO_CHECKPOINT);
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
  }

  /** 每块最多返回 {@code chunk} 个字符的字符流，用于构造真实的读取块边界。 */
  private static Reader chunked(String content, int chunk) {
    Reader delegate = new StringReader(content);
    return new Reader() {
      @Override
      public int read(char[] buffer, int offset, int length) throws IOException {
        return delegate.read(buffer, offset, Math.min(length, chunk));
      }

      @Override
      public void close() throws IOException {
        delegate.close();
      }
    };
  }
}
