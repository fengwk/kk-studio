package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCall;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 验证 {@link ReadCapability}、{@link WriteCapability}、{@link EditCapability} 与 {@link TextFileCodec}
 * 的核心契约、边界条件与覆盖率。
 */
class ReadWriteEditCapabilitiesTest {

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

  private CodingToolsConfig config() {
    return TestCodingConfig.withLsp(workdir);
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
    assertTrue(latch.await(5, TimeUnit.SECONDS));
    return resultRef.get();
  }

  private static String text(EnvironmentCapabilityResult result) {
    return ((TextResultContent) result.contents().getFirst()).text();
  }

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static String json(String str) {
    try {
      return MAPPER.writeValueAsString(str);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * 新读取契约的 header 期望由测试自己逐字段拼装，而不是调用 production formatter 当 oracle。
   *
   * <p>显式钉住字段顺序：{@code path}/{@code ends_with_newline}/{@code range}（截断时再接 {@code truncated}/{@code
   * truncation_reason}/{@code next}），{@code lsp} 恒为最后一行 header；未截断时不输出任何尾部警告。
   */
  private static String readHeader(String path, String range) {
    return String.join(
        "\n",
        "path: " + path,
        "ends_with_newline: yes",
        "range: " + range,
        "lsp: supported (" + TestCodingConfig.LSP_SERVER_ID + ")");
  }

  /** 截断窗口 header：截断元数据插在 lsp 之前，lsp 仍保持最后一行 header。 */
  private static String truncatedReadHeader(String path, String range, String reason, String next) {
    return String.join(
        "\n",
        "path: " + path,
        "ends_with_newline: yes",
        "range: " + range,
        "truncated: yes",
        "truncation_reason: " + reason,
        "next: " + next,
        "lsp: supported (" + TestCodingConfig.LSP_SERVER_ID + ")");
  }

  /** 空窗口 header：起点超过 EOF（含空文件）时输出 {@code range: empty} 且不输出任何编号正文。 */
  private static String emptyReadHeader(String path) {
    return String.join(
        "\n",
        "path: " + path,
        "ends_with_newline: yes",
        "range: empty",
        "lsp: supported (" + TestCodingConfig.LSP_SERVER_ID + ")");
  }

  /** 截断尾部只含续读位置的警告行。 */
  private static String truncationTail(long nextLine, long nextColumn) {
    return "[TRUNCATED: More file content remains. Next position: line "
        + nextLine
        + ", column "
        + nextColumn
        + ".]";
  }

  /** 验证绝对本地路径可省略 workdir，而相对路径仍必须显式声明解析目录。 */
  @Test
  void readAcceptsAbsolutePathWithoutWorkdirAndRejectsRelativePathWithoutIt() throws Exception {
    Path textFile = workdir.resolve("absolute.txt");
    Files.writeString(textFile, "content\n");
    ReadCapability read = new ReadCapability(config(), executor);

    EnvironmentCapabilityResult absolute =
        invoke(read, "{\"path\":" + json(textFile.toString()) + "}");
    assertFalse(absolute.error());
    assertTrue(text(absolute).contains("1|content"));

    EnvironmentCapabilityResult relative = invoke(read, "{\"path\":\"absolute.txt\"}");
    assertTrue(relative.error());
    assertTrue(text(relative).contains("workdir is required"));
  }

  /** 验证 ReadCapability 读取目录的分页、边界越界提示与展示格式。 */
  @Test
  void readDirectoryPaginationAndBoundaries() throws Exception {
    Path dir = workdir.resolve("sub");
    Files.createDirectories(dir);
    Files.createFile(dir.resolve("fileA.txt"));
    Files.createFile(dir.resolve("fileB.txt"));
    Files.createDirectory(dir.resolve("nestedDir"));

    ReadCapability read = new ReadCapability(config(), executor);

    // 正常分页
    EnvironmentCapabilityResult p1 =
        invoke(
            read,
            "{\"path\":\"sub\",\"offset\":1,\"limit\":2,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(p1.error());
    String t1 = text(p1);
    assertTrue(t1.contains("path: sub"));
    assertTrue(t1.contains("kind: directory"));
    assertTrue(t1.contains("fileA.txt"));
    assertTrue(t1.contains("fileB.txt"));
    assertTrue(t1.contains("Showing entries 1-2 of 3"));

    // offset 超限返回空
    EnvironmentCapabilityResult pEmpty =
        invoke(
            read,
            "{\"path\":\"sub\",\"offset\":10,\"limit\":2,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(pEmpty.error());
    assertTrue(text(pEmpty).contains("[Showing 0 entries of 3.]"));
  }

  /** 验证长行不再按行截断而是整行返回，且 offset 超过 EOF 时输出空范围而不编号。 */
  @Test
  void readLongLineWithoutPerLineTruncationAndOffsetBeyondTotal() throws Exception {
    Path textFile = workdir.resolve("longline.txt");
    String longLine = "a".repeat(2500);
    Files.writeString(textFile, "short\n" + longLine + "\nend\n");

    ReadCapability read = new ReadCapability(config(), executor);

    EnvironmentCapabilityResult res =
        invoke(
            read,
            "{\"path\":\"longline.txt\",\"offset\":1,\"limit\":10,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res.error());
    String expected =
        readHeader("longline.txt", "1:1-3:3")
            + "\n\n"
            + String.join("\n", "1|short", "2|" + longLine, "3|end");
    assertEquals(expected, text(res));

    // offset > totalLines 返回空窗口（range: empty，且不输出编号正文）
    EnvironmentCapabilityResult beyond =
        invoke(
            read,
            "{\"path\":\"longline.txt\",\"offset\":100,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(beyond.error());
    assertEquals(emptyReadHeader("longline.txt"), text(beyond));
  }

  /** 验证 60000 码点正文预算下的首个、后续、末尾分片，越过行尾的 column_offset 报错，以及无字符空行的合法端点。 */
  @Test
  void readLongLineFirstNextFinalFragmentsAndRejectBeyondEndColumn() throws Exception {
    Path textFile = workdir.resolve("multi-fragment.txt");
    String longLine = "a".repeat(130000);
    Files.writeString(textFile, "prefix\n" + longLine + "\n");

    ReadCapability read = new ReadCapability(config(), executor);

    // 1. 首个分片：命中 60000 码点预算，character_limit 指向下一个未返回列
    EnvironmentCapabilityResult firstFrag =
        invoke(
            read,
            "{\"path\":\"multi-fragment.txt\",\"offset\":2,\"column_offset\":1,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(firstFrag.error());
    String expectedFirst =
        truncatedReadHeader("multi-fragment.txt", "2:1-2:60000", "character_limit", "2:60001")
            + "\n\n"
            + "2|"
            + "a".repeat(60000)
            + "\n\n"
            + truncationTail(2, 60001);
    assertEquals(expectedFirst, text(firstFrag));

    // 2. 中间分片（列 60001-120000）
    EnvironmentCapabilityResult nextFrag =
        invoke(
            read,
            "{\"path\":\"multi-fragment.txt\",\"offset\":2,\"limit\":1,\"column_offset\":60001,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(nextFrag.error());
    String expectedNext =
        truncatedReadHeader("multi-fragment.txt", "2:60001-2:120000", "character_limit", "2:120001")
            + "\n\n"
            + "2|"
            + "a".repeat(60000)
            + "\n\n"
            + truncationTail(2, 120001);
    assertEquals(expectedNext, text(nextFrag));

    // 3. 末尾分片（列 120001-130000，抵达 EOF，无截断、无尾部警告）
    EnvironmentCapabilityResult finalFrag =
        invoke(
            read,
            "{\"path\":\"multi-fragment.txt\",\"offset\":2,\"limit\":1,\"column_offset\":120001,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(finalFrag.error());
    String expectedFinal =
        readHeader("multi-fragment.txt", "2:120001-2:130000") + "\n\n" + "2|" + "a".repeat(10000);
    assertEquals(expectedFinal, text(finalFrag));

    // 4. 有效行上的越界 column_offset 直接报错（不再输出 0 columns 元数据）
    EnvironmentCapabilityResult beyondFrag =
        invoke(
            read,
            "{\"path\":\"multi-fragment.txt\",\"offset\":2,\"limit\":1,\"column_offset\":130001,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertTrue(beyondFrag.error());
    assertTrue(
        text(beyondFrag)
            .contains("column_offset 130001 is out of range: line 2 has 130000 columns"),
        text(beyondFrag));

    // 5. 空行上使用 column_offset=1：无字符空行的合法端点是第 1 列，输出编号空行
    Path emptyLineFile = workdir.resolve("empty-line.txt");
    Files.writeString(emptyLineFile, "\n");
    EnvironmentCapabilityResult emptyLineRes =
        invoke(
            read,
            "{\"path\":\"empty-line.txt\",\"offset\":1,\"column_offset\":1,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(emptyLineRes.error());
    String expectedEmptyLine = readHeader("empty-line.txt", "1:1-1:1") + "\n\n" + "1|";
    assertEquals(expectedEmptyLine, text(emptyLineRes));
  }

  /** 验证 60000 码点预算在跨码点边界处不截断代理对，续读从完整码点开始。 */
  @Test
  void readUnicodeCodePointBoundariesPreserveSurrogatePairsWithinBudget() throws Exception {
    Path unicodeFile = workdir.resolve("unicode-line.txt");
    // 第 60000 个码点为 😀，第 60001 个码点为 🚀：预算边界落在两个增补平面码点之间
    String line = "A".repeat(59999) + "😀" + "🚀" + "B".repeat(100);
    assertEquals(60101, line.codePointCount(0, line.length()));
    Files.writeString(unicodeFile, line + "\n");

    ReadCapability read = new ReadCapability(config(), executor);

    // 第一分片（码点 1-60000）：以完整 😀 结尾，不截断代理对
    EnvironmentCapabilityResult p1 =
        invoke(
            read,
            "{\"path\":\"unicode-line.txt\",\"offset\":1,\"limit\":1,\"column_offset\":1,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(p1.error());
    String out1 = text(p1);
    String expectedOut1 =
        truncatedReadHeader("unicode-line.txt", "1:1-1:60000", "character_limit", "1:60001")
            + "\n\n"
            + "1|"
            + "A".repeat(59999)
            + "😀"
            + "\n\n"
            + truncationTail(1, 60001);
    assertEquals(expectedOut1, out1);

    String fragment1 =
        out1.lines().filter(l -> l.startsWith("1|")).findFirst().orElseThrow().substring(2);
    assertEquals(60000, fragment1.codePointCount(0, fragment1.length()));
    assertTrue(fragment1.endsWith("😀"), "预算边界必须停在完整码点上");

    // 第二分片（码点 60001-60101）：以完整 🚀 开头，不遗留孤立低代理项
    EnvironmentCapabilityResult p2 =
        invoke(
            read,
            "{\"path\":\"unicode-line.txt\",\"offset\":1,\"limit\":1,\"column_offset\":60001,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(p2.error());
    String expectedOut2 =
        readHeader("unicode-line.txt", "1:60001-1:60101") + "\n\n" + "1|" + "🚀" + "B".repeat(100);
    assertEquals(expectedOut2, text(p2));

    String fragment2 =
        text(p2).lines().filter(l -> l.startsWith("1|")).findFirst().orElseThrow().substring(2);
    assertEquals(101, fragment2.codePointCount(0, fragment2.length()));
    assertTrue(fragment2.startsWith("🚀"), "续读必须从完整码点开始");
  }

  /** 验证 column_offset 参数校验、与 limit>1 并存，以及对目录/图片的确定性拒绝。 */
  @Test
  void readColumnOffsetValidationAndRejections() throws Exception {
    Path file = workdir.resolve("valid.txt");
    Files.writeString(file, "content\nmore\n");
    Path dir = workdir.resolve("sub-dir");
    Files.createDirectory(dir);
    Path img = workdir.resolve("sample.png");
    Files.write(img, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0});

    ReadCapability read = new ReadCapability(config(), executor);

    // 1. column_offset 不再要求 limit=1：与 limit>1 同时指定时正常读取多行
    EnvironmentCapabilityResult multiLine =
        invoke(
            read,
            "{\"path\":\"valid.txt\",\"offset\":1,\"limit\":2,\"column_offset\":1,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(multiLine.error());
    assertTrue(text(multiLine).contains("1|content"));
    assertTrue(text(multiLine).contains("2|more"));

    // 2. 非正数 column_offset 被拒绝
    EnvironmentCapabilityResult zeroOffset =
        invoke(
            read,
            "{\"path\":\"valid.txt\",\"offset\":1,\"column_offset\":0,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertTrue(zeroOffset.error());
    assertTrue(text(zeroOffset).contains("column_offset must be a positive integer"));

    // 3. 目录请求指定 column_offset 被拒绝
    EnvironmentCapabilityResult dirRes =
        invoke(
            read,
            "{\"path\":\"sub-dir\",\"column_offset\":1,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertTrue(dirRes.error());
    assertTrue(text(dirRes).contains("column_offset is only supported for text files"));

    // 4. 图片请求指定 column_offset 被拒绝
    EnvironmentCapabilityResult imgRes =
        invoke(
            read,
            "{\"path\":\"sample.png\",\"column_offset\":1,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertTrue(imgRes.error());
    assertTrue(text(imgRes).contains("column_offset is only supported for text files"));

    // 5. 显式 null limit 携带 column_offset 时被 InputNormalizer 静默归一化为缺省（limit 默认 2000），正常读取成功
    EnvironmentCapabilityResult nullLimit =
        invoke(
            read,
            "{\"path\":\"valid.txt\",\"offset\":1,\"limit\":null,\"column_offset\":1,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(nullLimit.error());
    assertTrue(text(nullLimit).contains("1|content"));

    // 6. 显式 null column_offset 被 InputNormalizer 静默归一化为缺省，按普通模式读取成功
    EnvironmentCapabilityResult nullColOffset =
        invoke(
            read,
            "{\"path\":\"valid.txt\",\"offset\":1,\"column_offset\":null,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(nullColOffset.error());
    assertTrue(text(nullColOffset).contains("1|content"));

    // 7. 非整数 column_offset 被 schema 校验拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            invoke(
                read,
                "{\"path\":\"valid.txt\",\"offset\":1,\"column_offset\":\"abc\",\"workdir\":"
                    + json(workdir.toString())
                    + "}"));

    // 8. 超出 Integer.MAX_VALUE 的 column_offset 被拒绝
    EnvironmentCapabilityResult overflowColOffset =
        invoke(
            read,
            "{\"path\":\"valid.txt\",\"offset\":1,\"column_offset\":2147483648,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertTrue(overflowColOffset.error());
    assertTrue(text(overflowColOffset).contains("column_offset must be a positive integer"));

    // 9. 负数 column_offset 被拒绝
    EnvironmentCapabilityResult negativeColOffset =
        invoke(
            read,
            "{\"path\":\"valid.txt\",\"offset\":1,\"column_offset\":-1,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertTrue(negativeColOffset.error());
    assertTrue(text(negativeColOffset).contains("column_offset must be a positive integer"));
  }

  /** 验证恰好 60000 与 60001 码点临界边界下正文预算、越界列报错与续读元数据的确定性。 */
  @Test
  void readExactBoundary60000And60001CodePoints() throws Exception {
    Path file60000 = workdir.resolve("exact-60000.txt");
    String line60000 = "x".repeat(60000);
    Files.writeString(file60000, line60000 + "\n");

    Path file60001 = workdir.resolve("exact-60001.txt");
    String line60001 = "y".repeat(60000) + "Z";
    Files.writeString(file60001, line60001 + "\n");

    ReadCapability read = new ReadCapability(config(), executor);

    // 1. 恰好 60000 码点，默认读取：命中预算但已到 EOF，无截断、无尾部警告
    EnvironmentCapabilityResult res60000Default =
        invoke(
            read,
            "{\"path\":\"exact-60000.txt\",\"offset\":1,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res60000Default.error());
    String expected60000 = readHeader("exact-60000.txt", "1:1-1:60000") + "\n\n" + "1|" + line60000;
    assertEquals(expected60000, text(res60000Default));

    // 2. 恰好 60000 码点，携带 column_offset=1：整行返回，依旧无截断
    EnvironmentCapabilityResult res60000Col1 =
        invoke(
            read,
            "{\"path\":\"exact-60000.txt\",\"offset\":1,\"column_offset\":1,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res60000Col1.error());
    assertEquals(expected60000, text(res60000Col1));

    // 3. 恰好 60000 码点，column_offset=60001：有效行上的越界列直接报错
    EnvironmentCapabilityResult res60000ColBeyond =
        invoke(
            read,
            "{\"path\":\"exact-60000.txt\",\"offset\":1,\"column_offset\":60001,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertTrue(res60000ColBeyond.error());
    assertTrue(
        text(res60000ColBeyond)
            .contains("column_offset 60001 is out of range: line 1 has 60000 columns"),
        text(res60000ColBeyond));

    // 4. 60001 码点，默认读取：character_limit 截断至 60000，续读指向第 60001 列
    EnvironmentCapabilityResult res60001Default =
        invoke(
            read,
            "{\"path\":\"exact-60001.txt\",\"offset\":1,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res60001Default.error());
    String expected60001Default =
        truncatedReadHeader("exact-60001.txt", "1:1-1:60000", "character_limit", "1:60001")
            + "\n\n"
            + "1|"
            + "y".repeat(60000)
            + "\n\n"
            + truncationTail(1, 60001);
    assertEquals(expected60001Default, text(res60001Default));

    // 5. 60001 码点，column_offset=60001 读取末尾 1 码点：无后续截断元数据
    EnvironmentCapabilityResult res60001Tail =
        invoke(
            read,
            "{\"path\":\"exact-60001.txt\",\"offset\":1,\"column_offset\":60001,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res60001Tail.error());
    String expected60001Tail = readHeader("exact-60001.txt", "1:60001-1:60001") + "\n\n" + "1|Z";
    assertEquals(expected60001Tail, text(res60001Tail));
  }

  /** 验证 ReadCapability 严格保留控制字符与 CRLF 行尾真实内容，EditCapability 可精确 round-trip。 */
  @Test
  void readPreservesExactControlCharactersAndCrlf() throws Exception {
    Path file = workdir.resolve("crlf-control.txt");
    // 包含 \t 制表符与 ANSI 转义字符 \u001b[31m红字\u001b[0m 的 CRLF 文件
    String line1 = "col1\tcol2\t\u001b[31mred\u001b[0m";
    String line2 = "second\tline";
    Files.writeString(file, line1 + "\r\n" + line2 + "\r\n");

    ReadCapability read = new ReadCapability(config(), executor);
    EditCapability edit = new EditCapability(config(), executor);

    // 1. 读取文本：验证 exact content 保留制表符和 ANSI 转义字符，正文不转义为 \\0 或 \\r
    EnvironmentCapabilityResult readRes =
        invoke(
            read,
            "{\"path\":\"crlf-control.txt\",\"offset\":1,\"limit\":2,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(readRes.error());
    String out = text(readRes);
    String expectedRead =
        readHeader("crlf-control.txt", "1:1-2:11")
            + "\n\n"
            + String.join("\n", "1|" + line1, "2|" + line2);
    assertEquals(expectedRead, out);

    // 2. 将读取出的真实正文作为 old_string 送入 EditCapability 进行精确替换
    EnvironmentCapabilityResult editRes =
        invoke(
            edit,
            "{\"path\":\"crlf-control.txt\",\"old_string\":"
                + json(line1)
                + ",\"new_string\":"
                + json("col1\tcol2\t\u001b[32mgreen\u001b[0m")
                + ",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(editRes.error(), text(editRes));

    // 3. 验证替换后文件完整保留原 CRLF 行尾特征
    String updated = Files.readString(file);
    assertEquals("col1\tcol2\t\u001b[32mgreen\u001b[0m\r\nsecond\tline\r\n", updated);
  }

  /** 验证 offset 与 column_offset 达到 Integer.MAX_VALUE 时无溢出：越界起点给出空范围、有效行越界列明确报错。 */
  @Test
  void readMaxLegalIntsAndBeyondLineRange() throws Exception {
    Path file = workdir.resolve("sample.txt");
    Files.writeString(file, "line1\nline2\nline3\n");

    ReadCapability read = new ReadCapability(config(), executor);

    // 1. offset = Integer.MAX_VALUE：确定性返回 range: empty
    EnvironmentCapabilityResult maxOffsetRes =
        invoke(
            read,
            "{\"path\":\"sample.txt\",\"offset\":2147483647,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(maxOffsetRes.error());
    assertEquals(emptyReadHeader("sample.txt"), text(maxOffsetRes));

    // 2. offset = 1, column_offset = Integer.MAX_VALUE：有效行上的越界列明确报错
    EnvironmentCapabilityResult maxColRes =
        invoke(
            read,
            "{\"path\":\"sample.txt\",\"offset\":1,\"column_offset\":2147483647,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertTrue(maxColRes.error());
    assertTrue(
        text(maxColRes).contains("column_offset 2147483647 is out of range: line 1 has 5 columns"),
        text(maxColRes));

    // 3. offset 越界 (offset > totalLines) 且携带 column_offset：统一由 offset 越界优先拦截
    EnvironmentCapabilityResult beyondLineWithCol =
        invoke(
            read,
            "{\"path\":\"sample.txt\",\"offset\":5,\"column_offset\":1,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(beyondLineWithCol.error());
    assertEquals(emptyReadHeader("sample.txt"), text(beyondLineWithCol));
  }

  /** 验证超长行读取出的真实正文片段能直接作为 old_string 顺利通过 EditCapability 精确替换。 */
  @Test
  void readExactFragmentRoundTripThroughEditCapability() throws Exception {
    Path file = workdir.resolve("roundtrip.txt");
    String longLine = "UNIQUE_START_" + "Z".repeat(2500) + "_UNIQUE_END";
    Files.writeString(file, "head\n" + longLine + "\ntail\n");

    ReadCapability read = new ReadCapability(config(), executor);
    EditCapability edit = new EditCapability(config(), executor);

    // 1. 读取长行整行（60000 码点预算下一行完整返回，不再按 2000 码点截断）
    EnvironmentCapabilityResult readRes =
        invoke(
            read,
            "{\"path\":\"roundtrip.txt\",\"offset\":2,\"limit\":1,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(readRes.error());
    String readOut = text(readRes);
    String exactFragment =
        readOut.lines().filter(l -> l.startsWith("2|")).findFirst().orElseThrow().substring(2);
    assertEquals(2524, exactFragment.codePointCount(0, exactFragment.length()));

    // 2. 将读取出的无损正文作为 old_string 传入 EditCapability
    EnvironmentCapabilityResult editRes =
        invoke(
            edit,
            "{\"path\":\"roundtrip.txt\",\"old_string\":"
                + json(exactFragment)
                + ",\"new_string\":\"REPLACED_CHUNK\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(editRes.error(), text(editRes));
    String updated = Files.readString(file);
    assertEquals("head\nREPLACED_CHUNK\ntail\n", updated);
  }

  /** 验证正文超过旧 48 KiB 文本上限仍完整返回：CJK 正文 60000 字节而仅 20000 码点，不得二次截断。 */
  @Test
  void readTextBodyExceedsLegacy48KiBWithoutSecondaryTruncation() throws Exception {
    Path file = workdir.resolve("huge-cjk.txt");
    // 20000 个 3 字节 CJK 码点 => 正文 60000 字节，远超旧 48 KiB 文本上限，但仍低于 60000 码点预算
    String cjk = "字".repeat(20000);
    Files.writeString(file, cjk + "\n");

    ReadCapability read = new ReadCapability(config(), executor);
    EnvironmentCapabilityResult res =
        invoke(
            read,
            "{\"path\":\"huge-cjk.txt\",\"offset\":1,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res.error());
    String out = text(res);
    byte[] utf8 = out.getBytes(StandardCharsets.UTF_8);
    assertTrue(utf8.length > 48 * 1024, "正文 60000 字节必须超过旧 48 KiB 上限，说明该上限不再约束文本正文");
    assertFalse(out.contains("truncated"), out);
    assertTrue(out.contains("range: 1:1-1:20000"), out);
    String bodyLine = out.lines().filter(l -> l.startsWith("1|")).findFirst().orElseThrow();
    String body = bodyLine.substring(2);
    assertEquals(20000, body.codePointCount(0, body.length()));
    assertEquals(cjk, body);
  }

  /** 验证增补平面（4 字节 UTF-8）长行在 60000 码点预算处按完整码点切断，续读不遗留半代理项。 */
  @Test
  void readSupplementaryPlaneLongLinePreservesSurrogatePairs() throws Exception {
    Path file = workdir.resolve("rocket.txt");
    String line = "🚀".repeat(70000);
    Files.writeString(file, line + "\n");

    ReadCapability read = new ReadCapability(config(), executor);

    // 首切片：60000 个 🚀 码点，严格在 Unicode 码点边界切断，无半代理项
    EnvironmentCapabilityResult res1 =
        invoke(
            read,
            "{\"path\":\"rocket.txt\",\"offset\":1,\"workdir\":" + json(workdir.toString()) + "}");
    assertFalse(res1.error());
    String text1 = text(res1);
    assertTrue(text1.contains("range: 1:1-1:60000"), text1);
    assertTrue(text1.contains("next: 1:60001"), text1);
    String line1 = text1.lines().filter(l -> l.startsWith("1|")).findFirst().orElseThrow();
    String fragment1 = line1.substring(2);
    assertEquals(60000, fragment1.codePointCount(0, fragment1.length()));
    assertEquals(120000, fragment1.length());
    assertTrue(text1.endsWith(truncationTail(1, 60001)), text1);

    // 续读切片：剩余 10000 个 🚀 码点，抵达 EOF，无截断元数据
    EnvironmentCapabilityResult res2 =
        invoke(
            read,
            "{\"path\":\"rocket.txt\",\"offset\":1,\"limit\":1,\"column_offset\":60001,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res2.error());
    String text2 = text(res2);
    assertTrue(text2.contains("range: 1:60001-1:70000"), text2);
    assertFalse(text2.contains("truncated"), text2);
    String line2 = text2.lines().filter(l -> l.startsWith("1|")).findFirst().orElseThrow();
    String fragment2 = line2.substring(2);
    assertEquals(10000, fragment2.codePointCount(0, fragment2.length()));
    assertEquals(20000, fragment2.length());
  }

  /** 验证多行读取恰好耗尽 60000 码点预算并在 EOF 收尾：全部 30 行返回、无截断元数据、无字节上限限制。 */
  @Test
  void readMultiLinePackingConsumesExactCodePointBudgetWithoutTruncation() throws Exception {
    Path file = workdir.resolve("pack.txt");
    // 30 行 × 2000 码点 = 60000 码点，恰好等于正文预算，且最后一行结尾即 EOF
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= 30; i++) {
      sb.append("A".repeat(2000)).append("\n");
    }
    Files.writeString(file, sb.toString());

    ReadCapability read = new ReadCapability(config(), executor);
    EnvironmentCapabilityResult res =
        invoke(
            read,
            "{\"path\":\"pack.txt\",\"offset\":1,\"limit\":30,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res.error());
    String out = text(res);

    assertTrue(out.contains("range: 1:1-30:2000"), out);
    assertFalse(out.contains("truncated"), out);
    assertFalse(out.contains("next:"), out);
    assertTrue(out.contains(" 1|A"), out);
    assertTrue(out.endsWith("30|" + "A".repeat(2000)), "全部 30 行必须完整返回");
    assertEquals(30, out.lines().filter(line -> line.matches("\\s*\\d+\\|A+")).count());
    // 编号正文总计恰好 30 × 2000 = 60000 码点，说明预算在 EOF 处刚好用尽而非二次截断
    assertEquals(60000L, out.chars().filter(ch -> ch == 'A').count());
  }

  /** 验证包含 NUL 控制字符的文件被 TextFileCodec/ReadCapability 严格拒绝为二进制。 */
  @Test
  void readRejectsNulAsBinaryFile() throws Exception {
    Path file = workdir.resolve("binary-nul.txt");
    Files.write(file, new byte[] {'h', 'e', 'l', 'l', 'o', 0, 'w', 'o', 'r', 'l', 'd'});

    ReadCapability read = new ReadCapability(config(), executor);
    EnvironmentCapabilityResult res =
        invoke(read, "{\"path\":\"binary-nul.txt\",\"workdir\":" + json(workdir.toString()) + "}");
    assertTrue(res.error());
    assertTrue(text(res).contains("file appears to be binary"));

    Path utf16File = workdir.resolve("utf16-nul.txt");
    Files.write(utf16File, TextFileCodec.encode("hello\u0000world", StandardCharsets.UTF_16LE, 2));
    EnvironmentCapabilityResult utf16Res =
        invoke(read, "{\"path\":\"utf16-nul.txt\",\"workdir\":" + json(workdir.toString()) + "}");
    assertTrue(utf16Res.error());
    assertTrue(text(utf16Res).contains("file appears to be binary"));
  }

  /** 验证 ReadCapability.textResponse 终态防线严格拒绝超 48 KiB 文本。 */
  @Test
  void readEnforcesTextResponseInvariant() {
    EnvironmentCapabilityResult normal = ReadCapability.textResponse("call-ok", "small text");
    assertFalse(normal.error());
    assertEquals("small text", ((TextResultContent) normal.contents().getFirst()).text());

    String exactly48k = "x".repeat(48 * 1024);
    EnvironmentCapabilityResult maxOk = ReadCapability.textResponse("call-max", exactly48k);
    assertFalse(maxOk.error());

    String overflow = "x".repeat(48 * 1024 + 1);
    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> ReadCapability.textResponse("call-overflow", overflow));
    assertTrue(
        ex.getMessage().contains("read response exceeds 49152 bytes invariant: 49153 bytes"));
  }

  /**
   * 验证 fs.read 能分页读取远超 64 MiB 的文本：去掉了“文件超过 64 MiB 就拒绝”的旧上界。
   *
   * <p>该 fixture 约 76.8 MiB，旧实现会直接以 {@code file exceeds 64 MiB maximum read limit} 失败；现在头部与尾部窗口都能
   * 正确读取，说明总行数与行内容来自流式扫描而不是整文件读入。
   */
  @Test
  void readStreamsTextFilesLargerThan64MiBWithBoundedMemory() throws Exception {
    Path file = workdir.resolve("huge.log");
    // 每行约 84 字节，共 1,200,000 行 => 约 100 MB，稳定超过旧的 64 MiB 上界。
    long lineCount = 1_200_000L;
    try (var writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
      for (long index = 0; index < lineCount; index++) {
        writer.write("line-" + index + "-" + "x".repeat(70) + "\n");
      }
    }
    assertTrue(Files.size(file) > 64L * 1024 * 1024, "fixture 必须超过旧的 64 MiB 上界");

    ReadCapability read = new ReadCapability(config(), executor);
    // 头部读取：range/next header 精确给出已返回窗口与续读位置，不再有 "of N" 总行数展示
    EnvironmentCapabilityResult head =
        invoke(
            read,
            "{\"path\":\"huge.log\",\"offset\":1,\"limit\":3,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(head.error(), text(head));
    assertTrue(text(head).contains("1|line-0-"), text(head));
    assertTrue(text(head).contains("range: 1:1-3:77"), text(head));
    assertTrue(text(head).contains("truncation_reason: line_limit"), text(head));
    assertTrue(text(head).contains("next: 4:1"), text(head));
    assertFalse(text(head).contains("of " + lineCount), text(head));
    assertFalse(text(head).contains("Re-run read"), text(head));

    // 尾部读取：分页定位到文件末尾仍然正确，说明总行数是流式统计出来的。
    EnvironmentCapabilityResult tail =
        invoke(
            read,
            "{\"path\":\"huge.log\",\"offset\":"
                + lineCount
                + ",\"limit\":2,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(tail.error(), text(tail));
    assertTrue(text(tail).contains("range: " + lineCount + ":1-"), text(tail));
    assertTrue(text(tail).contains(lineCount + "|line-" + (lineCount - 1) + "-"), text(tail));

    // 图片附件行为不受影响。
    Path png = workdir.resolve("pic.png");
    Files.write(png, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4});
    EnvironmentCapabilityResult image =
        invoke(read, "{\"path\":\"pic.png\",\"workdir\":" + json(workdir.toString()) + "}");
    assertFalse(image.error());
    assertTrue(image.contents().getFirst() instanceof BinaryResultContent);
  }

  /** 验证 ReadCapability 识别支持的图片 MIME 并以内联二进制内容返回。 */
  @Test
  void readDetectsSupportedImageMimes() throws Exception {
    ReadCapability read = new ReadCapability(config(), executor);
    // JPEG
    Path jpg = workdir.resolve("test.jpg");
    Files.write(jpg, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1, 2, 3});
    EnvironmentCapabilityResult rJpg =
        invoke(read, "{\"path\":\"test.jpg\",\"workdir\":" + json(workdir.toString()) + "}");
    assertFalse(rJpg.error());
    assertTrue(rJpg.contents().getFirst() instanceof BinaryResultContent);
    assertEquals("image/jpeg", ((BinaryResultContent) rJpg.contents().getFirst()).mediaType());

    // GIF
    Path gif = workdir.resolve("test.gif");
    Files.write(gif, new byte[] {'G', 'I', 'F', '8', '9', 'a', 1, 2});
    EnvironmentCapabilityResult rGif =
        invoke(read, "{\"path\":\"test.gif\",\"workdir\":" + json(workdir.toString()) + "}");
    assertFalse(rGif.error());
    assertEquals("image/gif", ((BinaryResultContent) rGif.contents().getFirst()).mediaType());

    // WEBP
    Path webp = workdir.resolve("test.webp");
    Files.write(webp, new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'});
    EnvironmentCapabilityResult rWebp =
        invoke(read, "{\"path\":\"test.webp\",\"workdir\":" + json(workdir.toString()) + "}");
    assertFalse(rWebp.error());
    assertEquals("image/webp", ((BinaryResultContent) rWebp.contents().getFirst()).mediaType());
  }

  /** 完整覆盖按调用 content 原样写入，不沿用既有行尾，并拒绝目录作为写目标。 */
  @Test
  void writeWritesContentAsIsAndRejectsDirectory() throws Exception {
    WriteCapability write = new WriteCapability(config(), executor);

    // 既有 CRLF 文件被完整覆盖：content 就是写入内容本身，不做行尾变换。
    Path crlfFile = workdir.resolve("crlf.txt");
    Files.writeString(crlfFile, "line1\r\nline2\r\n");
    EnvironmentCapabilityResult resCrlf =
        invoke(
            write,
            "{\"path\":\"crlf.txt\",\"content\":\"a\\nb\\n\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(resCrlf.error());
    assertEquals("a\nb\n", Files.readString(crlfFile));

    // 既有 CR 文件同样按 content 原样写入。
    Path crFile = workdir.resolve("cr.txt");
    Files.write(crFile, "line1\rline2\r".getBytes(StandardCharsets.UTF_8));
    EnvironmentCapabilityResult resCr =
        invoke(
            write,
            "{\"path\":\"cr.txt\",\"content\":\"a\\nb\\n\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(resCr.error());
    assertArrayEquals("a\nb\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(crFile));

    // 拒绝目录作为写目标
    Path dir = workdir.resolve("mydir");
    Files.createDirectory(dir);
    EnvironmentCapabilityResult resDir =
        invoke(
            write,
            "{\"path\":\"mydir\",\"content\":\"x\",\"workdir\":" + json(workdir.toString()) + "}");
    assertTrue(resDir.error());
    assertTrue(text(resDir).contains("path is a directory"));
  }

  /** 验证 EditCapability 生成上下文 diff 并拒绝非法输入（错误不回显 old_string）。 */
  @Test
  void editContextualDiffAndErrorsDoNotEchoOldString() throws Exception {
    EditCapability edit = new EditCapability(config(), executor);
    Path target = workdir.resolve("sample.txt");
    Files.writeString(target, "line 1\nline 2\nline 3\nline 4\nline 5\n");

    // 替换单行成功，输出上下文 diff
    EnvironmentCapabilityResult res =
        invoke(
            edit,
            "{\"path\":\"sample.txt\",\"old_string\":\"line 3\",\"new_string\":\"LINE 3\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res.error());
    String diff = text(res);
    assertTrue(diff.contains("Edited sample.txt successfully."));
    assertTrue(diff.contains("- 3|line 3"));
    assertTrue(diff.contains("+ 3|LINE 3"));
    assertTrue(diff.contains(" 2|line 2"));
    assertTrue(diff.contains(" 4|line 4"));

    // 未找到匹配：错误信息不得回显 old_string 的具体内容
    EnvironmentCapabilityResult notFound =
        invoke(
            edit,
            "{\"path\":\"sample.txt\",\"old_string\":\"SECRET_PASSWORD_123\",\"new_string\":\"x\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertTrue(notFound.error());
    assertTrue(text(notFound).contains("Could not find old_string"));
    assertFalse(text(notFound).contains("SECRET_PASSWORD_123"));
  }

  /** 验证 TextFileCodec 处理 UTF-16 编码、BOM、NUL 以及无法编码文本的拒绝。 */
  @Test
  void textFileCodecHandlesUtf16AndStrictValidation() {
    // UTF-16LE with BOM
    byte[] utf16le = new byte[] {(byte) 0xff, (byte) 0xfe, 'h', 0, 'i', 0};
    TextFileCodec.Decoded decodedLe = TextFileCodec.decode(utf16le);
    assertEquals("hi", decodedLe.text());
    assertEquals(StandardCharsets.UTF_16LE, decodedLe.charset());
    assertEquals(2, decodedLe.bomLength());

    byte[] encodedLe = TextFileCodec.encode("hi", StandardCharsets.UTF_16LE, 2);
    assertArrayEquals(utf16le, encodedLe);

    // UTF-16BE with BOM
    byte[] utf16be = new byte[] {(byte) 0xfe, (byte) 0xff, 0, 'h', 0, 'i'};
    TextFileCodec.Decoded decodedBe = TextFileCodec.decode(utf16be);
    assertEquals("hi", decodedBe.text());
    assertEquals(StandardCharsets.UTF_16BE, decodedBe.charset());
    assertEquals(2, decodedBe.bomLength());

    // NUL 字符拒绝
    byte[] hasNul = new byte[] {'a', 0, 'b'};
    assertThrows(IllegalArgumentException.class, () -> TextFileCodec.decode(hasNul));

    // 非法 UTF-8 解码拒绝
    byte[] invalidUtf8 = new byte[] {(byte) 0xc0, (byte) 0xaf};
    assertThrows(IllegalArgumentException.class, () -> TextFileCodec.decode(invalidUtf8));

    // 无法编码字符拒绝（如 US-ASCII 编码包含非 ASCII 字符）
    assertThrows(
        IllegalArgumentException.class,
        () -> TextFileCodec.encode("你好", StandardCharsets.US_ASCII, 0));

    // 空校验
    assertThrows(IllegalArgumentException.class, () -> TextFileCodec.decode(null));
    assertThrows(
        IllegalArgumentException.class,
        () -> TextFileCodec.encode(null, StandardCharsets.UTF_8, 0));
  }
}
