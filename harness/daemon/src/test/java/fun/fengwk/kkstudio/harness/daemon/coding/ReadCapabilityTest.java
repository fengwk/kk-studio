package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.text.TextReadWindow;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@link ReadCapability} 新读取契约的专属测试：分页窗口、正文预算、Unicode/换行精确性、空范围与截断元数据。
 *
 * <p>本类替代旧的“每行 2000 码点截断 + 48 KiB 文本上限 + column_offset 必须配 limit=1”契约断言。旧共享 {@code
 * ReadWriteEditCapabilitiesTest} 中与旧契约绑定的 read 用例不再成立，已报告给主 Agent 统一移除，不在此处弱化。
 *
 * <p>长文本 fixture 由确定性生成（{@code "a".repeat(n)} 等）而不是落盘资源文件：用例的唯一事实就是“精确到某个码点/行的长度”，生成比
 * 二进制资源更可读，也不会被编辑器或 git 归一化破坏。
 */
class ReadCapabilityTest {

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

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** 截断输出的续读位置：{@code next: <line>:<column>}。 */
  private static final Pattern NEXT_PATTERN = Pattern.compile("(?m)^next: (\\d+):(\\d+)$");

  /** 编号正文行：可选左填充数字 + {@code |} + 真实文件内容。 */
  private static final Pattern BODY_PATTERN = Pattern.compile(" *([0-9]+)\\|(.*)");

  private static String json(String value) {
    try {
      return MAPPER.writeValueAsString(value);
    } catch (Exception error) {
      throw new RuntimeException(error);
    }
  }

  private String display(String name) {
    Path target = resolvePath(name);
    return target.normalize().toString().replace('\\', '/');
  }

  private Path resolvePath(String name) {
    Path path = Path.of(name);
    return path.isAbsolute() ? path : workdir.resolve(name);
  }

  /** 缺省不配置 LSP：header 不输出 lsp 行，断言只关心契约字段。 */
  private EnvironmentCapabilityResult read(String name, String extra) throws Exception {
    return read(TestCodingConfig.withoutLsp(workdir), name, extra);
  }

  private EnvironmentCapabilityResult read(CodingToolsConfig config, String name, String extra)
      throws Exception {
    return invoke(new ReadCapability(config, executor), arguments(name, extra));
  }

  private String arguments(String name, String extra) {
    Path target = resolvePath(name);
    return "{\"path\":" + json(target.toString()) + extra + "}";
  }

  private EnvironmentCapabilityResult invoke(EnvironmentCapability capability, String argumentsJson)
      throws Exception {
    CountDownLatch latch = new CountDownLatch(1);
    AtomicReference<EnvironmentCapabilityResult> resultRef = new AtomicReference<>();
    capability.execute(
        new EnvironmentCapabilityExecutionRequest(
            capability.descriptor(),
            new EnvironmentCapabilityCall("call-1", argumentsJson),
            Duration.ofSeconds(10)),
        new EnvironmentCapabilityExecutionListener() {
          @Override
          public void onComplete(EnvironmentCapabilityResult result) {
            resultRef.set(result);
            latch.countDown();
          }

          @Override
          public void onError(Throwable error) {
            resultRef.set(EnvironmentCapabilityResult.error("call-1", error.getMessage()));
            latch.countDown();
          }
        });
    assertTrue(latch.await(30, TimeUnit.SECONDS), "read 必须在预算内结束");
    return resultRef.get();
  }

  private static String text(EnvironmentCapabilityResult result) {
    return ((TextResultContent) result.contents().getFirst()).text();
  }

  private Path write(String name, String content) throws Exception {
    Path file = workdir.resolve(name);
    Files.writeString(file, content, StandardCharsets.UTF_8);
    return file;
  }

  private static void assertOutput(String expected, String actual) {
    assertEquals(expected, actual.replace("\r", "\\r"));
  }

  /** 分页窗口：offset/limit 生效，未到 EOF 且还有未返回内容时给出截断元数据与尾部位置。 */
  @Test
  void numberedLinesRespectOffsetAndLimitAndReportLineLimitTruncation() throws Exception {
    write("basic.txt", "one\ntwo\nthree\nfour\n");

    EnvironmentCapabilityResult result = read("basic.txt", ",\"offset\":2,\"limit\":2");

    assertFalse(result.error());
    assertOutput(
        String.join(
            "\n",
            "path: " + display("basic.txt"),
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
        text(result));
  }

  /** limit 缺省为 2000：2001 行的文件读到第 2000 行后按 line_limit 截断。 */
  @Test
  void defaultLimitIsTwoThousandLines() throws Exception {
    StringBuilder content = new StringBuilder();
    for (int index = 1; index <= 2001; index++) {
      content.append("line-").append(index).append('\n');
    }
    write("many-lines.txt", content.toString());

    EnvironmentCapabilityResult result = read("many-lines.txt", "");

    assertFalse(result.error());
    String output = text(result);
    assertTrue(output.contains("\nrange: 1:1-2000:9\n"), output);
    assertTrue(output.contains("truncation_reason: line_limit"), output);
    assertTrue(output.contains("next: 2001:1"), output);
    assertTrue(output.contains("2000|line-2000"), output);
    assertFalse(output.contains("2001|line-2001"), output);
  }

  /** 空文件与越过 EOF 的起点都输出明确空范围；ends_with_newline 仍描述整个文件。 */
  @Test
  void emptyFileAndOffsetBeyondEofReportEmptyRange() throws Exception {
    write("empty.txt", "");

    EnvironmentCapabilityResult empty = read("empty.txt", "");
    assertFalse(empty.error());
    assertOutput(
        String.join("\n", "path: " + display("empty.txt"), "ends_with_newline: no", "range: empty"),
        text(empty));

    write("two.txt", "a\nb\n");
    EnvironmentCapabilityResult beyond = read("two.txt", ",\"offset\":10");
    assertFalse(beyond.error());
    assertOutput(
        String.join("\n", "path: " + display("two.txt"), "ends_with_newline: yes", "range: empty"),
        text(beyond));
  }

  /** 单行不再按长度截断：2500 码点整行原样返回且没有截断元数据。 */
  @Test
  void longLineIsReturnedWithoutPerLineTruncation() throws Exception {
    String longLine = "x".repeat(2500);
    write("long.txt", longLine + "\n");

    EnvironmentCapabilityResult result = read("long.txt", "");

    assertFalse(result.error());
    assertOutput(
        String.join(
            "\n",
            "path: " + display("long.txt"),
            "ends_with_newline: yes",
            "range: 1:1-1:2500",
            "",
            "1|" + longLine),
        text(result));
  }

  /** column_offset 只作用于起始行片段，起始片段占一行额度，后续行仍从第 1 列开始。 */
  @Test
  void columnOffsetSlicesOnlyTheFirstLine() throws Exception {
    write("columns.txt", "abcdefghij\nklmnop\n");

    EnvironmentCapabilityResult result = read("columns.txt", ",\"column_offset\":4,\"limit\":2");

    assertFalse(result.error());
    assertOutput(
        String.join(
            "\n",
            "path: " + display("columns.txt"),
            "ends_with_newline: yes",
            "range: 1:4-2:6",
            "",
            "1|defghij",
            "2|klmnop"),
        text(result));
  }

  /** column_offset 不再要求 limit=1：缺省 limit 下仍可读取后续行。 */
  @Test
  void columnOffsetDoesNotConstrainLimit() throws Exception {
    write("paired.txt", "abcdef\nghij\n");

    EnvironmentCapabilityResult result = read("paired.txt", ",\"column_offset\":3");

    assertFalse(result.error());
    String output = text(result);
    assertTrue(output.contains("1|cdef"), output);
    assertTrue(output.contains("2|ghij"), output);
  }

  /** 有效目标行上的越界列报错；无字符空行的合法端点是第 1 列。 */
  @Test
  void columnOffsetBeyondLineLengthFailsWhileEmptyLineEndpointIsValid() throws Exception {
    write("short.txt", "abc\n");

    EnvironmentCapabilityResult beyond = read("short.txt", ",\"column_offset\":10");
    assertTrue(beyond.error());
    assertTrue(
        text(beyond).contains("column_offset 10 is out of range: line 1 has 3 columns"),
        text(beyond));

    write("blank.txt", "\n");
    EnvironmentCapabilityResult blank = read("blank.txt", ",\"column_offset\":1");
    assertFalse(blank.error());
    assertOutput(
        String.join(
            "\n",
            "path: " + display("blank.txt"),
            "ends_with_newline: yes",
            "range: 1:1-1:1",
            "",
            "1|"),
        text(blank));
  }

  /** 起点越界优先于列校验：offset 超过 EOF 时不因巨大 column_offset 报错，而是明确空范围。 */
  @Test
  void offsetBeyondEofWinsOverColumnValidation() throws Exception {
    write("three.txt", "line1\nline2\nline3\n");

    EnvironmentCapabilityResult result =
        read("three.txt", ",\"offset\":5,\"column_offset\":2147483647");

    assertFalse(result.error());
    assertOutput(
        String.join(
            "\n", "path: " + display("three.txt"), "ends_with_newline: yes", "range: empty"),
        text(result));
  }

  /** 正文预算 60000 码点：行内命中预算时以 character_limit 截断，续读坐标指向第一个未返回字符。 */
  @Test
  void characterBudgetCutsMidLineAndPointsAtNextColumn() throws Exception {
    write("budget.txt", "a".repeat(70000) + "\nsecond\n");

    EnvironmentCapabilityResult result = read("budget.txt", "");

    assertFalse(result.error());
    String output = text(result);
    assertTrue(
        output.startsWith(
            String.join(
                "\n",
                "path: " + display("budget.txt"),
                "ends_with_newline: yes",
                "range: 1:1-1:60000",
                "truncated: yes",
                "truncation_reason: character_limit",
                "next: 1:60001",
                "")),
        output);
    String bodyLine =
        output.lines().filter(line -> line.startsWith("1|")).findFirst().orElseThrow();
    assertEquals(60000, bodyLine.substring(2).length());
    assertTrue(
        output.endsWith(
            "[TRUNCATED: More file content remains. Next position: line 1, column 60001.]"),
        output);
  }

  /** 预算恰好命中 EOF 不算截断；命中行尾但仍有后续行时以 character_limit 指向下一行第一列。 */
  @Test
  void characterBudgetBoundariesAtLineAndFileEnd() throws Exception {
    write("exact-eof.txt", "a".repeat(60000));

    EnvironmentCapabilityResult exactEof = read("exact-eof.txt", "");
    assertFalse(exactEof.error());
    String eofOutput = text(exactEof);
    assertTrue(
        eofOutput.startsWith(
            "path: " + display("exact-eof.txt") + "\nends_with_newline: no\nrange: 1:1-1:60000\n"),
        eofOutput);
    assertFalse(eofOutput.contains("truncated"), eofOutput);

    write("at-line-end.txt", "a".repeat(60000) + "\nb\n");
    EnvironmentCapabilityResult atLineEnd = read("at-line-end.txt", "");
    assertFalse(atLineEnd.error());
    String lineEndOutput = text(atLineEnd);
    assertTrue(lineEndOutput.contains("truncation_reason: character_limit"), lineEndOutput);
    assertTrue(lineEndOutput.contains("next: 2:1"), lineEndOutput);

    StringBuilder thirtyLines = new StringBuilder();
    for (int index = 0; index < 30; index++) {
      thirtyLines.append("A".repeat(2000)).append('\n');
    }
    write("exact-lines.txt", thirtyLines.toString());
    EnvironmentCapabilityResult exactLines = read("exact-lines.txt", "");
    assertFalse(exactLines.error());
    String linesOutput = text(exactLines);
    assertTrue(linesOutput.contains("range: 1:1-30:2000"), linesOutput);
    assertFalse(linesOutput.contains("truncated"), linesOutput);
  }

  /** 增补平面码点（4 字节 UTF-8）在预算边界必须按完整码点切断，不留半代理项。 */
  @Test
  void surrogatePairIsNeverSplitAtBudgetBoundary() throws Exception {
    String line = "a".repeat(59999) + "😀" + "b".repeat(10);
    write("surrogate.txt", line + "\n");

    EnvironmentCapabilityResult result = read("surrogate.txt", "");

    assertFalse(result.error());
    String output = text(result);
    String bodyLine =
        output.lines().filter(value -> value.startsWith("1|")).findFirst().orElseThrow();
    String fragment = bodyLine.substring(2);
    assertEquals(60000, fragment.codePointCount(0, fragment.length()));
    assertTrue(fragment.endsWith("😀"), "预算边界必须停在完整码点上");
    assertTrue(output.contains("next: 1:60001"), output);
  }

  /** CRLF、孤立 CR 与 LF 都是行边界：正文精确、不丢字符、不重复，行号连续。 */
  @Test
  void mixedLineEndingsAreExactAndPreserveContent() throws Exception {
    write("mixed.txt", "a\r\nb\rc\nd");

    EnvironmentCapabilityResult result = read("mixed.txt", "");

    assertFalse(result.error());
    assertOutput(
        String.join(
            "\n",
            "path: " + display("mixed.txt"),
            "ends_with_newline: no",
            "range: 1:1-4:1",
            "",
            "1|a",
            "2|b",
            "3|c",
            "4|d"),
        text(result));
  }

  /** ends_with_newline 描述整个文件而不是返回片段：窗口截断在文件中部时仍准确。 */
  @Test
  void endsWithNewlineDescribesWholeFileEvenWhenWindowIsTruncated() throws Exception {
    write("no-final-newline.txt", "alpha\nbeta\ngamma");

    EnvironmentCapabilityResult result = read("no-final-newline.txt", ",\"limit\":1");

    assertFalse(result.error());
    String output = text(result);
    assertTrue(output.contains("ends_with_newline: no"), output);
    assertTrue(output.contains("1|alpha"), output);
    assertTrue(output.contains("truncation_reason: line_limit"), output);
    assertTrue(output.contains("next: 2:1"), output);
  }

  /** 尾部只给截断警告与位置，不夹带“下一次调用”教程。 */
  @Test
  void truncationTailCarriesPositionOnly() throws Exception {
    write("tail.txt", "one\ntwo\n");

    EnvironmentCapabilityResult result = read("tail.txt", ",\"limit\":1");

    assertFalse(result.error());
    String output = text(result);
    assertTrue(
        output.endsWith("[TRUNCATED: More file content remains. Next position: line 2, column 1.]"),
        output);
    assertFalse(output.contains("Re-run read"), output);
  }

  /** 正文只包含真实文件内容：制表符与 ANSI 转义原样保留，不生成任何合成截断标记。 */
  @Test
  void bodyKeepsExactFileContentWithoutSyntheticMarkers() throws Exception {
    String line = "col1\tcol2\t\u001b[31mred\u001b[0m";
    write("control.txt", line + "\r\nsecond\tline\r\n");

    EnvironmentCapabilityResult result = read("control.txt", "");

    assertFalse(result.error());
    assertOutput(
        String.join(
            "\n",
            "path: " + display("control.txt"),
            "ends_with_newline: yes",
            "range: 1:1-2:11",
            "",
            "1|" + line,
            "2|second\tline"),
        text(result));
  }

  /** 读出的精确正文片段可以直接作为 edit 的 old_string 完成无损替换。 */
  @Test
  void exactFragmentRoundTripsThroughEditCapability() throws Exception {
    String longLine = "UNIQUE_START_" + "Z".repeat(2500) + "_UNIQUE_END";
    write("roundtrip.txt", "head\n" + longLine + "\ntail\n");

    EnvironmentCapabilityResult readResult = read("roundtrip.txt", ",\"offset\":2");
    assertFalse(readResult.error());
    String fragment =
        text(readResult)
            .lines()
            .filter(line -> line.startsWith("2|"))
            .findFirst()
            .orElseThrow()
            .substring(2);
    assertEquals(2524, fragment.codePointCount(0, fragment.length()));

    EditCapability edit = new EditCapability(TestCodingConfig.withoutLsp(workdir), executor);
    EnvironmentCapabilityResult replaced =
        invoke(
            edit,
            "{\"path\":"
                + json(workdir.resolve("roundtrip.txt").toString())
                + ",\"old_string\":"
                + json(fragment)
                + ",\"new_string\":\"REPLACED_CHUNK\"}");
    assertFalse(replaced.error(), text(replaced));
    assertEquals(
        "head\nREPLACED_CHUNK\ntail\n", Files.readString(workdir.resolve("roundtrip.txt")));
  }

  /** 非法 UTF-8、NUL 与其它控制字符都按二进制拒绝，不返回乱码正文。 */
  @Test
  void binaryContentIsRejected() throws Exception {
    Files.write(workdir.resolve("nul.txt"), new byte[] {'h', 'i', 0, 'x'});
    EnvironmentCapabilityResult nulResult = read("nul.txt", "");
    assertTrue(nulResult.error());
    assertTrue(text(nulResult).contains("binary"), text(nulResult));

    // 普通（非图片）二进制仍拒绝：column_offset 对文本窗口无影响，也不能把二进制洗成文本。
    EnvironmentCapabilityResult nulWithColumn = read("nul.txt", ",\"column_offset\":2");
    assertTrue(nulWithColumn.error());
    assertTrue(text(nulWithColumn).contains("binary"), text(nulWithColumn));

    Files.write(workdir.resolve("control.bin"), new byte[] {'h', 'i', 1, 2, 3});
    EnvironmentCapabilityResult controlResult = read("control.bin", "");
    assertTrue(controlResult.error());
    assertTrue(text(controlResult).contains("binary"), text(controlResult));

    Files.write(
        workdir.resolve("utf16-nul.txt"),
        TextFileCodec.encode("hello\u0000world", StandardCharsets.UTF_16LE, 2));
    EnvironmentCapabilityResult utf16Result = read("utf16-nul.txt", "");
    assertTrue(utf16Result.error());
    assertTrue(text(utf16Result).contains("binary"), text(utf16Result));

    // 无 BOM 的 8 位旧编码（如 windows-1252 的 "café"）没有嗅探通道：严格 UTF-8 解码失败即按二进制拒绝。
    Files.write(workdir.resolve("legacy.txt"), "café\n".getBytes(StandardCharsets.ISO_8859_1));
    EnvironmentCapabilityResult legacyResult = read("legacy.txt", "");
    assertTrue(legacyResult.error());
    assertTrue(text(legacyResult).contains("binary"), text(legacyResult));
  }

  /** UTF-8 BOM 与 UTF-16LE BOM 文本都按各自编码解码，BOM 不进入正文与列计数。 */
  @Test
  void bomEncodedTextIsDecodedWithoutBomInBody() throws Exception {
    byte[] content = "one\ntwo\n".getBytes(StandardCharsets.UTF_8);
    byte[] withBom = new byte[content.length + 3];
    withBom[0] = (byte) 0xEF;
    withBom[1] = (byte) 0xBB;
    withBom[2] = (byte) 0xBF;
    System.arraycopy(content, 0, withBom, 3, content.length);
    Files.write(workdir.resolve("utf8-bom.txt"), withBom);
    assertTrue(text(read("utf8-bom.txt", "")).contains("1|one"));

    Files.write(
        workdir.resolve("utf16.txt"),
        TextFileCodec.encode("alpha\r\nbeta\r\n", StandardCharsets.UTF_16LE, 2));
    EnvironmentCapabilityResult utf16Result = read("utf16.txt", "");
    assertFalse(utf16Result.error());
    String output = text(utf16Result);
    assertTrue(output.contains("range: 1:1-2:4"), output);
    assertTrue(output.contains("1|alpha"), output);
    assertTrue(output.contains("2|beta"), output);
  }

  /** LSP 只有配置且可执行时才在 header 最后一行展示，不输出 unsupported 占位。 */
  @Test
  void lspHeaderReflectsAvailableServerAndIsLast() throws Exception {
    write("app.java", "class App {}\n");

    EnvironmentCapabilityResult noBridge = read("app.java", "");
    assertFalse(noBridge.error());
    assertTrue(text(noBridge).contains("\nrange: 1:1-1:12\n\n1|class App {}"), text(noBridge));
    assertFalse(text(noBridge).contains("lsp:"), text(noBridge));

    EnvironmentCapabilityResult bridged = read(TestCodingConfig.withLsp(workdir), "app.java", "");
    assertFalse(bridged.error());
    assertTrue(
        text(bridged)
            .contains(
                "range: 1:1-1:12\nlsp: supported (" + TestCodingConfig.LSP_SERVER_ID + ")\n\n1|"),
        text(bridged));
  }

  /** 目录保持独立语义：分页缺省与上限都是 2000，且忽略文本专用的 column_offset。 */
  @Test
  void directoryPaginationDefaultsToTwoThousandAndIgnoresColumnOffset() throws Exception {
    assertEquals(2000, TextReadWindow.DEFAULT_LIMIT);
    assertEquals(2000, TextReadWindow.MAX_LIMIT);
    assertEquals(60000, TextReadWindow.MAX_BODY_CODE_POINTS);

    Path dir = workdir.resolve("listing");
    Files.createDirectories(dir);
    for (int index = 1; index <= 3; index++) {
      Files.writeString(dir.resolve("file" + index + ".txt"), "x");
    }

    EnvironmentCapabilityResult page = read("listing", ",\"limit\":2");
    assertFalse(page.error());
    String output = text(page);
    assertTrue(output.contains("kind: directory"), output);
    assertTrue(output.contains("Showing entries 1-2 of 3"), output);

    EnvironmentCapabilityResult overLimit = read("listing", ",\"limit\":2001");
    assertTrue(overLimit.error());
    assertTrue(
        text(overLimit).contains("limit must be a positive integer <= 2000"), text(overLimit));

    // 合法 column_offset 对目录无意义：忽略而不是报错，清单结果与不带该参数一致。
    EnvironmentCapabilityResult withColumn = read("listing", ",\"column_offset\":1,\"limit\":2");
    assertFalse(withColumn.error(), text(withColumn));
    assertTrue(text(withColumn).contains("kind: directory"), text(withColumn));
    assertTrue(text(withColumn).contains("Showing entries 1-2 of 3"), text(withColumn));
  }

  /**
   * 目录清单保留独立的 48 KiB 展示上界，并按整条加尾注的方式有界截断。
   *
   * <p>测试意图：目录上限提升到 2000 条后，体积防线仍必须成立，且截断必须给出可续读的 offset（不能只截断不告知）。
   */
  @Test
  void directoryListingKeepsByteCeilingAndContinuesFromReportedOffset() throws Exception {
    Path dir = workdir.resolve("wide");
    Files.createDirectories(dir);
    int total = 600;
    for (int index = 1; index <= total; index++) {
      Files.writeString(dir.resolve(String.format("%03d", index) + "-" + "n".repeat(80)), "x");
    }

    EnvironmentCapabilityResult first = read("wide", "");
    assertFalse(first.error(), text(first));
    String output = text(first);
    assertTrue(output.getBytes(StandardCharsets.UTF_8).length <= 48 * 1024, "目录清单不得超过 48 KiB");
    Matcher tail =
        Pattern.compile("Showing entries 1-(\\d+) of 600\\. Re-run read with offset=(\\d+)")
            .matcher(output);
    assertTrue(tail.find(), output);
    int firstEnd = Integer.parseInt(tail.group(1));
    int nextOffset = Integer.parseInt(tail.group(2));
    assertEquals(firstEnd + 1, nextOffset, output);

    EnvironmentCapabilityResult second = read("wide", ",\"offset\":" + nextOffset);
    assertFalse(second.error(), text(second));
    String secondOutput = text(second);
    // 最后一页已经到 EOF：不再给续读提示，且确实覆盖到第 600 条。
    assertTrue(secondOutput.contains("600-" + "n".repeat(80)), secondOutput);
    assertFalse(secondOutput.contains("Re-run read with offset="), secondOutput);
    assertFalse(secondOutput.contains("Showing entries"), secondOutput);
  }

  /** 图片仍是二进制语义，并忽略文本专用的 column_offset。 */
  @Test
  void imageResultsStayBinaryAndIgnoreColumnOffset() throws Exception {
    Files.write(
        workdir.resolve("pic.png"),
        new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3});

    EnvironmentCapabilityResult image = read("pic.png", "");
    assertFalse(image.error());
    assertTrue(image.contents().getFirst() instanceof BinaryResultContent);
    assertEquals("image/png", ((BinaryResultContent) image.contents().getFirst()).mediaType());

    // 合法 column_offset 对受支持图片无意义：忽略而不是报错，仍按二进制资源返回。
    EnvironmentCapabilityResult withColumn = read("pic.png", ",\"column_offset\":1");
    assertFalse(withColumn.error());
    assertTrue(withColumn.contents().getFirst() instanceof BinaryResultContent);
    assertEquals("image/png", ((BinaryResultContent) withColumn.contents().getFirst()).mediaType());
  }

  /** 窗口参数先于文件系统访问校验：参数错误不能被 ENOENT 掩盖。 */
  @Test
  void windowArgumentsAreValidatedBeforeFileSystemAccess() throws Exception {
    EnvironmentCapabilityResult invalidOffset = read("missing.txt", ",\"offset\":0");
    assertTrue(invalidOffset.error());
    assertTrue(text(invalidOffset).contains("offset must be a positive integer"));
    assertFalse(text(invalidOffset).contains("does not exist"));

    EnvironmentCapabilityResult invalidLimit = read("missing.txt", ",\"limit\":5000");
    assertTrue(invalidLimit.error());
    assertTrue(text(invalidLimit).contains("limit must be a positive integer <= 2000"));
    assertFalse(text(invalidLimit).contains("does not exist"));

    EnvironmentCapabilityResult invalidColumn = read("missing.txt", ",\"column_offset\":0");
    assertTrue(invalidColumn.error());
    assertTrue(text(invalidColumn).contains("column_offset must be a positive integer"));
    assertFalse(text(invalidColumn).contains("does not exist"));
  }

  /** 相对路径被拒绝；绝对路径正常执行。 */
  @Test
  void relativePathIsRejected() throws Exception {
    Path file = write("relative.txt", "content\n");

    EnvironmentCapabilityResult absolute =
        invoke(
            new ReadCapability(TestCodingConfig.withoutLsp(workdir), executor),
            "{\"path\":" + json(file.toString()) + "}");
    assertFalse(absolute.error());
    assertTrue(text(absolute).contains("range: 1:1-1:7"), text(absolute));

    EnvironmentCapabilityResult relative =
        invoke(
            new ReadCapability(TestCodingConfig.withoutLsp(workdir), executor),
            "{\"path\":\"relative.txt\"}");
    assertTrue(relative.error());
    assertTrue(
        text(relative).contains("path must be an absolute path: relative.txt"), text(relative));
  }

  /** 意图：read 结果无论是否超过内联阈值都不再外置落盘（details 无 textOutput 且 tmp/text 目录为空）。 */
  @Test
  void readDoesNotSpoolResultsExceedingInlineThreshold() throws Exception {
    write("budget-large.txt", "a".repeat(70000) + "\n");
    CodingToolsConfig config = TestCodingConfig.withLimits(workdir, 2000, 1024);
    EnvironmentCapabilityResult result = read(config, "budget-large.txt", "");
    assertFalse(result.error());
    assertTrue(text(result).length() > 1024);
    assertFalse(result.detailsJson().contains("textOutput"), result.detailsJson());
    Path textDir = config.textOutputStore().textDirectory();
    if (Files.isDirectory(textDir)) {
      try (var entries = Files.list(textDir)) {
        assertEquals(0, entries.count());
      }
    }
  }

  /**
   * 非普通文件节点（字符设备、FIFO、socket、块设备）必须在任何 I/O 之前拒绝。
   *
   * <p>测试意图：读 FIFO 会阻塞、读字符设备会伪装成空文件，两者都是“坏掉的环境”而不是正常读取结果。
   */
  @Test
  void nonRegularFileNodesAreRejectedBeforeIo() throws Exception {
    Assumptions.assumeFalse(System.getProperty("os.name").toLowerCase().contains("win"));
    Path device = Path.of("/dev/null");
    Assumptions.assumeTrue(Files.exists(device));

    EnvironmentCapabilityResult result = read(device.toString(), "");

    assertTrue(result.error(), text(result));
    assertTrue(text(result).contains("not a regular file"), text(result));
  }

  /** 非 ASCII 空格保留在路径与展示名中，不被规范化吞掉。 */
  @Test
  void nonAsciiSpacesInFileNamesArePreserved() throws Exception {
    write("hello　world.txt", "alpha\n");

    EnvironmentCapabilityResult result = read("hello　world.txt", "");

    assertFalse(result.error());
    assertTrue(text(result).contains("1|alpha"), text(result));
  }

  /** 大文本流式分页：头部与尾部都能读，总行数与文件级元数据来自全文件扫描而不是整文件读入。 */
  @Test
  void largeTextIsStreamedWithAccurateTotals() throws Exception {
    Path file = workdir.resolve("large.log");
    long lineCount = 40000L;
    try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      for (long index = 0; index < lineCount; index++) {
        writer.write("line-" + index + "-" + "x".repeat(20) + "\n");
      }
    }

    EnvironmentCapabilityResult head = read("large.log", ",\"limit\":2");
    assertFalse(head.error(), text(head));
    assertTrue(text(head).contains("1|line-0-"), text(head));
    assertTrue(text(head).contains("range: 1:1-2:27"), text(head));
    assertTrue(text(head).contains("next: 3:1"), text(head));

    EnvironmentCapabilityResult tail =
        read("large.log", ",\"offset\":" + lineCount + ",\"limit\":1");
    assertFalse(tail.error(), text(tail));
    assertTrue(text(tail).contains(lineCount + "|line-" + (lineCount - 1) + "-"), text(tail));
  }

  /**
   * 续读位置必须能无损重构全部源内容：按 {@code next} 反复续读，把所有编号正文片段按行拼接后应与原文件逐行一致。
   *
   * <p>该性质同时约束“不丢字符、不重复、不跨行错位”：任何一次续读只要多返回或少返回一个字符，拼接结果就会与源文件不同。
   */
  @Test
  void successiveReadsReconstructEverySourceCharacter() throws Exception {
    String firstLine = "a".repeat(150000);
    String secondLine = "b".repeat(70000);
    String thirdLine = "tail";
    write("reconstruct.txt", firstLine + "\n" + secondLine + "\n" + thirdLine + "\n");
    String[] sourceLines = {firstLine, secondLine, thirdLine};

    Map<Integer, StringBuilder> fragments = new TreeMap<>();
    int offset = 1;
    Integer columnOffset = 1;
    int attempts = 0;
    while (true) {
      String extra =
          ",\"offset\":"
              + offset
              + (columnOffset == null ? "" : ",\"column_offset\":" + columnOffset);
      EnvironmentCapabilityResult result = read("reconstruct.txt", extra);
      assertFalse(result.error(), text(result));
      String output = text(result);
      collectFragments(output, fragments);
      if (!output.contains("truncated: yes")) {
        break;
      }
      Matcher next = NEXT_PATTERN.matcher(output);
      assertTrue(next.find(), "截断输出必须给出 next 位置: " + output);
      offset = Integer.parseInt(next.group(1));
      columnOffset = Integer.parseInt(next.group(2));
      attempts++;
      assertTrue(attempts < 20, "续读次数异常，可能存在无法推进的 next 位置");
    }

    assertEquals(sourceLines.length, fragments.size(), "每一行都必须被覆盖");
    for (int index = 0; index < sourceLines.length; index++) {
      assertEquals(
          sourceLines[index],
          fragments.get(index + 1).toString(),
          "第 " + (index + 1) + " 行必须逐字符重构");
    }
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
}
