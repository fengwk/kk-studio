package fun.fengwk.kkstudio.platform.harness.read;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 受管文本读取适配器的契约测试：受管文本与本地 {@code fs.read} 必须同契约，而窗口状态机由共享核心 {@link
 * fun.fengwk.kkstudio.harness.common.text.TextReadWindow} 提供（其自身行为由 common 侧测试守护）。
 *
 * <p>本类覆盖适配器职责：严格 UTF-8 解码、首个字符 BOM 剥离、参数校验、扫描超时/中断与 IO 失败的映射，以及受管入口的端到端窗口结果，包括空范围、 60000
 * 码点正文预算、截断元数据与“续读位置可无损重构全部源内容”。
 */
class ReadTextWindowTest {

  private static final byte[] UTF8_BOM = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

  /** 截断输出的续读位置：{@code next: <line>:<column>}。 */
  private static final Pattern NEXT_PATTERN = Pattern.compile("(?m)^next: (\\d+):(\\d+)$");

  /** 编号正文行：可选左填充数字 + {@code |} + 真实文件内容。 */
  private static final Pattern BODY_PATTERN = Pattern.compile(" *([0-9]+)\\|(.*)");

  private static String format(
      String content, Integer offset, Integer limit, Integer columnOffset) {
    return ReadTextWindow.format(
        content.getBytes(StandardCharsets.UTF_8), offset, limit, columnOffset, "test.txt");
  }

  /** 空资源输出明确空范围；lsp 不可用时整行省略。 */
  @Test
  void emptyInputReportsEmptyRange() {
    assertEquals(
        String.join("\n", "path: test.txt", "ends_with_newline: no", "range: empty"),
        ReadTextWindow.format(new byte[0], 1, 200, null, "test.txt"));
  }

  /** 分页窗口：offset/limit 生效，未到 EOF 时给出 line_limit 截断与下一行位置。 */
  @Test
  void offsetAndLimitProduceWindowAndLineLimitTruncation() {
    String result = format("one\ntwo\nthree\nfour\n", 2, 2, null);

    assertEquals(
        String.join(
            "\n",
            "path: test.txt",
            "ends_with_newline: yes",
            "range: 2:1-3:5",
            "truncated: yes",
            "truncation_reason: line_limit",
            "next: 4:1",
            "",
            "2|two",
            "3|three",
            "",
            "[TRUNCATED: More file content remains. Next position: line 4, column 1.]"),
        result);
  }

  /** CRLF、孤立 CR 与 LF 都作为行边界归一化，且不丢字符。 */
  @Test
  void crlfAndLoneCrAreLineBoundaries() {
    assertEquals(
        String.join(
            "\n",
            "path: test.txt",
            "ends_with_newline: no",
            "range: 1:1-3:1",
            "",
            "1|a",
            "2|b",
            "3|c"),
        format("a\r\nb\rc", 1, 200, null));
  }

  /** 起点超过 EOF 时输出明确空范围而不是伪造空正文行。 */
  @Test
  void offsetBeyondTotalLinesReportsEmptyRange() {
    String result = format("line1\nline2\n", 5, 200, null);
    assertEquals(
        String.join("\n", "path: test.txt", "ends_with_newline: yes", "range: empty"), result);
  }

  /** 单行不再按长度截断：3000 码点整行返回。 */
  @Test
  void longLineIsNotTruncated() {
    String longLine = "a".repeat(3000);
    String result = format(longLine, 1, 200, null);

    assertTrue(result.contains("range: 1:1-1:3000"), result);
    assertTrue(result.contains("1|" + longLine), result);
    assertFalse(result.contains("truncated"), result);
  }

  /** column_offset 只作用于起始行片段，且不再要求 limit=1。 */
  @Test
  void columnOffsetSlicesOnlyFirstLineAndKeepsFollowingLines() {
    String result = format("abcdefghij\nklmnop\n", 1, 200, 4);

    assertEquals(
        String.join(
            "\n",
            "path: test.txt",
            "ends_with_newline: yes",
            "range: 1:4-2:6",
            "",
            "1|defghij",
            "2|klmnop"),
        result);
  }

  /** column_offset 超过有效目标行长度时报错；无字符空行的合法端点是第 1 列。 */
  @Test
  void columnOffsetBeyondLineLengthThrowsWhileEmptyLineEndpointIsValid() {
    PlatformReadException failure =
        assertThrows(PlatformReadException.class, () -> format("abc", 1, 200, 10));
    assertTrue(
        failure.getMessage().contains("column_offset 10 is out of range: line 1 has 3 columns"),
        failure.getMessage());

    assertEquals(
        String.join("\n", "path: test.txt", "ends_with_newline: yes", "range: 1:1-1:1", "", "1|"),
        format("\n", 1, 200, 1));
  }

  /** 正文预算 60000 码点：行内命中预算时截断并指向第一个未返回字符。 */
  @Test
  void characterBudgetCutsMidLineAtSixtyThousandCodePoints() {
    String result = format("a".repeat(70000), 1, 2000, null);

    assertTrue(
        result.startsWith(
            String.join(
                "\n",
                "path: test.txt",
                "ends_with_newline: no",
                "range: 1:1-1:60000",
                "truncated: yes",
                "truncation_reason: character_limit",
                "next: 1:60001",
                "")),
        result);
    String bodyLine =
        result.lines().filter(line -> line.startsWith("1|")).findFirst().orElseThrow();
    assertEquals(60000, bodyLine.substring(2).length());
  }

  /** 预算恰好命中 EOF 不算截断。 */
  @Test
  void characterBudgetExactlyAtEofIsNotTruncation() {
    String result = format("a".repeat(60000), 1, 2000, null);

    assertTrue(
        result.startsWith("path: test.txt\nends_with_newline: no\nrange: 1:1-1:60000\n"), result);
    assertFalse(result.contains("truncated"), result);
  }

  /** 增补平面码点（4 字节 UTF-8）在预算边界按完整码点切断，不留半代理项。 */
  @Test
  void supplementaryPlaneCodePointIsNotSplitAtBudgetBoundary() {
    String result = format("a".repeat(59999) + "🚀" + "b".repeat(5), 1, 2000, null);

    String bodyLine =
        result.lines().filter(line -> line.startsWith("1|")).findFirst().orElseThrow();
    String fragment = bodyLine.substring(2);
    assertEquals(60000, fragment.codePointCount(0, fragment.length()));
    assertTrue(fragment.endsWith("🚀"), fragment.substring(fragment.length() - 4));
    assertTrue(result.contains("next: 1:60001"), result);
  }

  /** 增补平面码点恰好落在解码缓冲区边界时仍是完整码元对，正文与列计数都按码点计算。 */
  @Test
  void supplementaryCodePointAtDecodeBufferBoundaryStaysIntact() {
    String content = "a".repeat(8191) + "🚀" + "tail";

    String result = format(content, 1, 2000, null);

    assertTrue(result.contains("range: 1:1-1:8196"), result);
    String body = result.lines().filter(line -> line.startsWith("1|")).findFirst().orElseThrow();
    assertEquals(content, body.substring(2));
  }

  /** ends_with_newline 与总行数来自整资源扫描：窗口截断在资源中部时依然准确。 */
  @Test
  void endsWithNewlineAndTotalsDescribeWholeResource() {
    String result = format("alpha\nbeta\ngamma", 1, 1, null);

    assertTrue(result.contains("ends_with_newline: no"), result);
    assertTrue(result.contains("1|alpha"), result);
    assertTrue(result.contains("truncation_reason: line_limit"), result);
    assertTrue(result.contains("next: 2:1"), result);
  }

  /**
   * BOM 只剥离一次，且严格只作用于流的第 1 个字符。
   *
   * <p>测试意图：回退保护“首个非普通字符（换行、代理项对、解码块边界）之后出现的 {@code \uFEFF} 被误当作 BOM 吞掉”的缺陷；正文里的 {@code \uFEFF}
   * 是真实内容，必须逐字符返回。
   */
  @Test
  void byteOrderMarkIsStrippedOnlyAtTheVeryStart() {
    assertTrue(
        ReadTextWindow.format(withLeadingBom("bom-text\n"), 1, 200, null, "test.txt", "unsupported")
            .contains("range: 1:1-1:8"));

    String afterNewline = format("\n\uFEFFx", 1, 200, null);
    assertTrue(afterNewline.contains("1|"), afterNewline);
    assertTrue(afterNewline.contains("2|\uFEFFx"), afterNewline);

    // 代理项对只算一个码点：正文三段 = 😀 + \uFEFF + x。
    String afterSurrogatePair = format("😀\uFEFFx", 1, 200, null);
    assertTrue(afterSurrogatePair.contains("1|😀\uFEFFx"), afterSurrogatePair);
    assertTrue(afterSurrogatePair.contains("range: 1:1-1:3"), afterSurrogatePair);

    String afterDecodeBlock = format("a".repeat(8191) + "\uFEFF" + "x", 1, 2000, null);
    assertTrue(afterDecodeBlock.contains("\uFEFFx"), afterDecodeBlock);
    assertTrue(afterDecodeBlock.contains("range: 1:1-1:8193"), afterDecodeBlock);
  }

  private static byte[] withLeadingBom(String content) {
    byte[] body = content.getBytes(StandardCharsets.UTF_8);
    byte[] all = new byte[UTF8_BOM.length + body.length];
    System.arraycopy(UTF8_BOM, 0, all, 0, UTF8_BOM.length);
    System.arraycopy(body, 0, all, UTF8_BOM.length, body.length);
    return all;
  }

  /** NUL 与非法 UTF-8 都判定为二进制并抛出可区分错误。 */
  @Test
  void binaryAndMalformedUtf8AreRejected() {
    assertTrue(
        assertThrows(
                PlatformReadException.class,
                () -> ReadTextWindow.format(new byte[] {'a', 0, 'b'}, 1, 200, null, "test.bin"))
            .getMessage()
            .contains("binary"));
    assertTrue(
        assertThrows(
                PlatformReadException.class,
                () ->
                    ReadTextWindow.format(
                        new byte[] {(byte) 0xC0, (byte) 0xAF}, 1, 200, null, "test.bin"))
            .getMessage()
            .contains("binary"));
  }

  /** 参数非法时抛出确定性错误，不进入内容读取。 */
  @Test
  void invalidArgumentsAreRejected() {
    assertThrows(PlatformReadException.class, () -> format("text", 0, 200, null));
    assertThrows(PlatformReadException.class, () -> format("text", 1, 0, null));
    assertThrows(PlatformReadException.class, () -> format("text", 1, 2001, null));
    assertThrows(PlatformReadException.class, () -> format("text", 1, 200, 0));
  }

  /** lsp 状态是 header 最后一行：可用时输出，{@code unsupported} 时省略。 */
  @Test
  void lspStatusIsLastHeaderLineAndOmittedWhenUnsupported() {
    String supported =
        ReadTextWindow.format(
            "class App {}\n".getBytes(StandardCharsets.UTF_8),
            1,
            200,
            null,
            "app.java",
            "supported (java)");
    assertTrue(
        supported.startsWith(
            "path: app.java\nends_with_newline: yes\nrange: 1:1-1:12\nlsp: supported (java)\n\n1|class App {}"),
        supported);

    String unsupported =
        ReadTextWindow.format(
            "class App {}\n".getBytes(StandardCharsets.UTF_8),
            1,
            200,
            null,
            "app.java",
            "unsupported");
    assertFalse(unsupported.contains("lsp:"), unsupported);
  }

  /** 底层 IO 失败映射为受管读取失败，不把半截正文当作结果。 */
  @Test
  void streamFailuresAreMappedToPlatformReadException() {
    InputStream failing =
        new InputStream() {
          @Override
          public int read(byte[] buffer, int offset, int length) throws IOException {
            throw new IOException("stream closed");
          }

          @Override
          public int read() throws IOException {
            throw new IOException("stream closed");
          }
        };

    PlatformReadException failure =
        assertThrows(
            PlatformReadException.class,
            () -> ReadTextWindow.format(failing, 1, 200, null, "test.txt", "unsupported"));
    assertTrue(failure.getMessage().contains("cannot be read"), failure.getMessage());
  }

  /** 扫描期间线程被中断时以可区分的错误结束，不返回半截正文。 */
  @Test
  void interruptedScanFailsInsteadOfReturningPartialWindow() {
    InputStream interrupting =
        new InputStream() {
          @Override
          public int read() {
            Thread.currentThread().interrupt();
            return 'a';
          }
        };

    try {
      PlatformReadException failure =
          assertThrows(
              PlatformReadException.class,
              () -> ReadTextWindow.format(interrupting, 1, 200, null, "test.txt", "unsupported"));
      assertTrue(failure.getMessage().contains("interrupted or timed out"), failure.getMessage());
    } finally {
      Thread.interrupted();
    }
  }

  /**
   * 大流只保留窗口，但总行数来自扫描到 EOF；不再有“资源超过 N MiB 就拒绝”的限制。
   *
   * <p>测试意图：证明流式扫描是线性读取（大流不会被整段驻留内存），且行数统计准确。
   */
  @Test
  void largeStreamKeepsWindowWithAccurateLineCount() {
    int bytes = 9 * 1024 * 1024;

    String head =
        ReadTextWindow.format(generatedStream(bytes), 1, 1, null, "huge.txt", "unsupported");

    assertTrue(head.contains("range: 1:1-1:99"), head);
    assertTrue(head.contains("1|" + "a".repeat(99)), head);
    assertTrue(head.contains("truncated: yes"), head);
    assertTrue(head.contains("next: 2:1"), head);
    assertFalse(head.contains("Showing lines"), "旧分页 footer 已移除");

    // 读取统计出的最后一行，证明总行数来自扫描到 EOF；再越过它必须是空范围。
    long lastLine = ((long) bytes - 1) / 100 + 1;
    String tail =
        ReadTextWindow.format(
            generatedStream(bytes), (int) lastLine, 1, null, "huge.txt", "unsupported");
    assertTrue(tail.contains("range: " + lastLine + ":1-" + lastLine + ":84"), tail);
    String beyond =
        ReadTextWindow.format(
            generatedStream(bytes), (int) lastLine + 1, 1, null, "huge.txt", "unsupported");
    assertTrue(beyond.endsWith("range: empty"), beyond);
  }

  /** 续读位置必须能无损重构全部源内容：按 {@code next} 反复读取，正文片段按行拼接后与源文本逐行一致。 */
  @Test
  void successiveReadsReconstructEverySourceCharacter() {
    String firstLine = "a".repeat(150000);
    String secondLine = "b".repeat(70000);
    String thirdLine = "tail";
    String source = firstLine + "\n" + secondLine + "\n" + thirdLine + "\n";

    Map<Integer, StringBuilder> fragments = new TreeMap<>();
    int offset = 1;
    Integer columnOffset = 1;
    int attempts = 0;
    while (true) {
      String output =
          ReadTextWindow.format(
              source.getBytes(StandardCharsets.UTF_8), offset, 2000, columnOffset, "test.txt");
      collectFragments(output, fragments);
      if (!output.contains("truncated: yes")) {
        break;
      }
      Matcher next = NEXT_PATTERN.matcher(output);
      assertTrue(next.find(), "截断输出必须给出 next 位置: " + output);
      offset = Integer.parseInt(next.group(1));
      columnOffset = Integer.parseInt(next.group(2));
      attempts++;
      assertTrue(attempts < 20, "续读次数异常");
    }

    assertEquals(3, fragments.size());
    assertEquals(firstLine, fragments.get(1).toString());
    assertEquals(secondLine, fragments.get(2).toString());
    assertEquals(thirdLine, fragments.get(3).toString());
  }

  /** 生成 {@code bytes} 字节的确定性文本流：每 100 字节一个有 99 个 {@code a} 的行。 */
  private static InputStream generatedStream(int bytes) {
    return new InputStream() {
      private int index;

      @Override
      public int read() {
        return index++ < bytes ? (index % 100 == 0 ? '\n' : 'a') : -1;
      }
    };
  }

  /** 把一次响应中的所有编号正文行片段按行号累加，用于验证续读不丢字符、不重复。 */
  private static void collectFragments(String output, Map<Integer, StringBuilder> fragments) {
    for (String line : output.split("\n", -1)) {
      Matcher body = BODY_PATTERN.matcher(line);
      if (body.matches()) {
        fragments
            .computeIfAbsent(Integer.parseInt(body.group(1)), key -> new StringBuilder())
            .append(body.group(2));
      }
    }
  }

  /** 输入流由调用方负责关闭：{@code format} 不能替调用方关闭资源。 */
  @Test
  void inputStreamIsNotClosedByFormat() {
    InputStream stream =
        new ByteArrayInputStream("content\n".getBytes(StandardCharsets.UTF_8)) {
          @Override
          public void close() {
            throw new AssertionError("format 不得关闭调用方输入流");
          }
        };

    assertTrue(
        ReadTextWindow.format(stream, 1, 200, null, "test.txt", "unsupported")
            .contains("1|content"));
  }
}
