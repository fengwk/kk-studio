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
    return TestCodingConfig.withBridge(workdir);
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

  /** 验证 ReadCapability 文本读取长行片段与元数据输出、正文无合成截断标记、offset>total 行数为空提示。 */
  @Test
  void readTextLineTruncationAndOffsetBeyondTotal() throws Exception {
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
    String out = text(res);
    String expected =
        String.join(
            "\n",
            "path: longline.txt",
            "ends_with_newline: yes",
            "lsp: supported",
            "",
            "1|short",
            "2|" + "a".repeat(2000),
            "3|end",
            "",
            "[Showing columns 1-2000 of 2500 on line 2. Re-run read with offset=2, limit=1, column_offset=2001 to continue.]");
    assertEquals(expected, out);

    // offset > totalLines 返回空窗口
    EnvironmentCapabilityResult beyond =
        invoke(
            read,
            "{\"path\":\"longline.txt\",\"offset\":100,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(beyond.error());
    String expectedBeyond =
        String.join(
            "\n",
            "path: longline.txt",
            "ends_with_newline: yes",
            "lsp: supported",
            "",
            "[Showing 0 lines of 3.]");
    assertEquals(expectedBeyond, text(beyond));
  }

  /** 验证 ReadCapability 支持首个、后续、末尾分片以及越过行尾的 column_offset 边界，断言严格协议外观。 */
  @Test
  void readTextLongLineFirstNextFinalFragmentsAndBeyondEnd() throws Exception {
    Path textFile = workdir.resolve("multi-fragment.txt");
    String longLine = "a".repeat(4500);
    Files.writeString(textFile, "prefix\n" + longLine + "\nsuffix\n");

    ReadCapability read = new ReadCapability(config(), executor);

    // 1. 首个分片（通过显式 column_offset=1，limit 缺省为 1）
    EnvironmentCapabilityResult firstFrag =
        invoke(
            read,
            "{\"path\":\"multi-fragment.txt\",\"offset\":2,\"column_offset\":1,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(firstFrag.error());
    String firstOut = text(firstFrag);
    String expectedFirst =
        String.join(
            "\n",
            "path: multi-fragment.txt",
            "ends_with_newline: yes",
            "lsp: supported",
            "",
            "2|" + "a".repeat(2000),
            "",
            "[Showing columns 1-2000 of 4500 on line 2. Re-run read with offset=2, limit=1, column_offset=2001 to continue.]");
    assertEquals(expectedFirst, firstOut);

    // 2. 中间分片（columns 2001-4000）
    EnvironmentCapabilityResult nextFrag =
        invoke(
            read,
            "{\"path\":\"multi-fragment.txt\",\"offset\":2,\"limit\":1,\"column_offset\":2001,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(nextFrag.error());
    String nextOut = text(nextFrag);
    String expectedNext =
        String.join(
            "\n",
            "path: multi-fragment.txt",
            "ends_with_newline: yes",
            "lsp: supported",
            "",
            "2|" + "a".repeat(2000),
            "",
            "[Showing columns 2001-4000 of 4500 on line 2. Re-run read with offset=2, limit=1, column_offset=4001 to continue.]");
    assertEquals(expectedNext, nextOut);

    // 3. 末尾分片（columns 4001-4500，无后续 continuation hint）
    EnvironmentCapabilityResult finalFrag =
        invoke(
            read,
            "{\"path\":\"multi-fragment.txt\",\"offset\":2,\"limit\":1,\"column_offset\":4001,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(finalFrag.error());
    String finalOut = text(finalFrag);
    String expectedFinal =
        String.join(
            "\n",
            "path: multi-fragment.txt",
            "ends_with_newline: yes",
            "lsp: supported",
            "",
            "2|" + "a".repeat(500),
            "",
            "[Showing columns 4001-4500 of 4500 on line 2.]");
    assertEquals(expectedFinal, finalOut);

    // 4. column_offset 超出行尾：不输出编号正文行，输出确定性 0 columns 元数据
    EnvironmentCapabilityResult beyondFrag =
        invoke(
            read,
            "{\"path\":\"multi-fragment.txt\",\"offset\":2,\"limit\":1,\"column_offset\":4501,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(beyondFrag.error());
    String beyondOut = text(beyondFrag);
    String expectedBeyond =
        String.join(
            "\n",
            "path: multi-fragment.txt",
            "ends_with_newline: yes",
            "lsp: supported",
            "",
            "[Showing 0 columns of 4500 on line 2.]");
    assertEquals(expectedBeyond, beyondOut);

    // 5. 空行上使用 column_offset=1：超出 0 长度行尾
    Path emptyLineFile = workdir.resolve("empty-line.txt");
    Files.writeString(emptyLineFile, "\n");
    EnvironmentCapabilityResult emptyLineRes =
        invoke(
            read,
            "{\"path\":\"empty-line.txt\",\"offset\":1,\"column_offset\":1,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(emptyLineRes.error());
    String emptyLineOut = text(emptyLineRes);
    String expectedEmptyLine =
        String.join(
            "\n",
            "path: empty-line.txt",
            "ends_with_newline: yes",
            "lsp: supported",
            "",
            "[Showing 0 columns of 0 on line 1.]");
    assertEquals(expectedEmptyLine, emptyLineOut);
  }

  /** 验证 ReadCapability 按 Unicode 码点安全切片，不截断代理对（surrogate pairs），严格断言输出协议。 */
  @Test
  void readUnicodeCodePointBoundariesPreservesSurrogatePairs() throws Exception {
    Path unicodeFile = workdir.resolve("unicode-line.txt");
    // 构建第 2000 个码点为 😀（\uD83D\uDE00），第 2001 个码点为 🚀（\uD83D\uDE80）的长行
    String line = "A".repeat(1999) + "😀" + "🚀" + "B".repeat(100);
    assertEquals(2101, line.codePointCount(0, line.length()));
    Files.writeString(unicodeFile, line + "\n");

    ReadCapability read = new ReadCapability(config(), executor);

    // 第一分片（码点 1-2000）：以完整 😀 结尾，不截断代理对
    EnvironmentCapabilityResult p1 =
        invoke(
            read,
            "{\"path\":\"unicode-line.txt\",\"offset\":1,\"limit\":1,\"column_offset\":1,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(p1.error());
    String out1 = text(p1);
    String expectedOut1 =
        String.join(
            "\n",
            "path: unicode-line.txt",
            "ends_with_newline: yes",
            "lsp: supported",
            "",
            "1|" + "A".repeat(1999) + "😀",
            "",
            "[Showing columns 1-2000 of 2101 on line 1. Re-run read with offset=1, limit=1, column_offset=2001 to continue.]");
    assertEquals(expectedOut1, out1);

    // 第二分片（码点 2001-2101）：以完整 🚀 开头，不遗留孤立低代理项
    EnvironmentCapabilityResult p2 =
        invoke(
            read,
            "{\"path\":\"unicode-line.txt\",\"offset\":1,\"limit\":1,\"column_offset\":2001,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(p2.error());
    String out2 = text(p2);
    String expectedOut2 =
        String.join(
            "\n",
            "path: unicode-line.txt",
            "ends_with_newline: yes",
            "lsp: supported",
            "",
            "1|🚀" + "B".repeat(100),
            "",
            "[Showing columns 2001-2101 of 2101 on line 1.]");
    assertEquals(expectedOut2, out2);
  }

  /** 验证 column_offset 参数校验、多行模式拒绝以及对目录/图片的确定性拒绝。 */
  @Test
  void readColumnOffsetValidationAndRejections() throws Exception {
    Path file = workdir.resolve("valid.txt");
    Files.writeString(file, "content\n");
    Path dir = workdir.resolve("sub-dir");
    Files.createDirectory(dir);
    Path img = workdir.resolve("sample.png");
    Files.write(img, new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0});

    ReadCapability read = new ReadCapability(config(), executor);

    // 1. column_offset 搭配 limit > 1 被拒绝
    EnvironmentCapabilityResult multiLine =
        invoke(
            read,
            "{\"path\":\"valid.txt\",\"offset\":1,\"limit\":2,\"column_offset\":1,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertTrue(multiLine.error());
    assertTrue(text(multiLine).contains("limit must be 1 when column_offset is specified"));

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

    // 5. 显式 null limit 携带 column_offset 时被 InputNormalizer 静默归一化为缺省（limit 默认 1），正常读取成功
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

  /** 验证恰好 2000 与 2001 码点临界边界下切片与元数据输出的确定性。 */
  @Test
  void readExactBoundary2000And2001CodePoints() throws Exception {
    Path file2000 = workdir.resolve("exact-2000.txt");
    String line2000 = "x".repeat(2000);
    Files.writeString(file2000, line2000 + "\n");

    Path file2001 = workdir.resolve("exact-2001.txt");
    String line2001 = "y".repeat(2000) + "Z";
    Files.writeString(file2001, line2001 + "\n");

    ReadCapability read = new ReadCapability(config(), executor);

    // 1. 恰好 2000 码点，默认读取：无截断、无列尾注
    EnvironmentCapabilityResult res2000Default =
        invoke(
            read,
            "{\"path\":\"exact-2000.txt\",\"offset\":1,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res2000Default.error());
    String expected2000Default =
        String.join(
            "\n",
            "path: exact-2000.txt",
            "ends_with_newline: yes",
            "lsp: supported",
            "",
            "1|" + line2000);
    assertEquals(expected2000Default, text(res2000Default));

    // 2. 恰好 2000 码点，携带 column_offset=1：显示 1-2000 of 2000，且无后续 continuation hint
    EnvironmentCapabilityResult res2000Col1 =
        invoke(
            read,
            "{\"path\":\"exact-2000.txt\",\"offset\":1,\"column_offset\":1,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res2000Col1.error());
    String expected2000Col1 =
        String.join(
            "\n",
            "path: exact-2000.txt",
            "ends_with_newline: yes",
            "lsp: supported",
            "",
            "1|" + line2000,
            "",
            "[Showing columns 1-2000 of 2000 on line 1.]");
    assertEquals(expected2000Col1, text(res2000Col1));

    // 3. 恰好 2000 码点，column_offset=2001：越界，输出 0 columns
    EnvironmentCapabilityResult res2000ColBeyond =
        invoke(
            read,
            "{\"path\":\"exact-2000.txt\",\"offset\":1,\"column_offset\":2001,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res2000ColBeyond.error());
    String expected2000ColBeyond =
        String.join(
            "\n",
            "path: exact-2000.txt",
            "ends_with_newline: yes",
            "lsp: supported",
            "",
            "[Showing 0 columns of 2000 on line 1.]");
    assertEquals(expected2000ColBeyond, text(res2000ColBeyond));

    // 4. 2001 码点，默认读取：截断至 2000，带 continuation hint
    EnvironmentCapabilityResult res2001Default =
        invoke(
            read,
            "{\"path\":\"exact-2001.txt\",\"offset\":1,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res2001Default.error());
    String expected2001Default =
        String.join(
            "\n",
            "path: exact-2001.txt",
            "ends_with_newline: yes",
            "lsp: supported",
            "",
            "1|" + "y".repeat(2000),
            "",
            "[Showing columns 1-2000 of 2001 on line 1. Re-run read with offset=1, limit=1, column_offset=2001 to continue.]");
    assertEquals(expected2001Default, text(res2001Default));

    // 5. 2001 码点，column_offset=2001 读取末尾 1 码点：无后续 continuation hint
    EnvironmentCapabilityResult res2001Tail =
        invoke(
            read,
            "{\"path\":\"exact-2001.txt\",\"offset\":1,\"limit\":1,\"column_offset\":2001,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res2001Tail.error());
    String expected2001Tail =
        String.join(
            "\n",
            "path: exact-2001.txt",
            "ends_with_newline: yes",
            "lsp: supported",
            "",
            "1|Z",
            "",
            "[Showing columns 2001-2001 of 2001 on line 1.]");
    assertEquals(expected2001Tail, text(res2001Tail));
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
        String.join(
            "\n",
            "path: crlf-control.txt",
            "ends_with_newline: yes",
            "lsp: supported",
            "",
            "1|" + line1,
            "2|" + line2);
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

  /** 验证 offset 与 column_offset 在达到 Integer.MAX_VALUE 极端整数时无溢出，确定性输出机器可用元数据。 */
  @Test
  void readMaxLegalIntsAndBeyondLineRange() throws Exception {
    Path file = workdir.resolve("sample.txt");
    Files.writeString(file, "line1\nline2\nline3\n");

    ReadCapability read = new ReadCapability(config(), executor);

    // 1. offset = Integer.MAX_VALUE：确定性返回 0 lines of 3
    EnvironmentCapabilityResult maxOffsetRes =
        invoke(
            read,
            "{\"path\":\"sample.txt\",\"offset\":2147483647,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(maxOffsetRes.error());
    String expectedMaxOffset =
        String.join(
            "\n",
            "path: sample.txt",
            "ends_with_newline: yes",
            "lsp: supported",
            "",
            "[Showing 0 lines of 3.]");
    assertEquals(expectedMaxOffset, text(maxOffsetRes));

    // 2. offset = 1, column_offset = Integer.MAX_VALUE：确定性返回 0 columns of 5
    EnvironmentCapabilityResult maxColRes =
        invoke(
            read,
            "{\"path\":\"sample.txt\",\"offset\":1,\"column_offset\":2147483647,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(maxColRes.error());
    String expectedMaxCol =
        String.join(
            "\n",
            "path: sample.txt",
            "ends_with_newline: yes",
            "lsp: supported",
            "",
            "[Showing 0 columns of 5 on line 1.]");
    assertEquals(expectedMaxCol, text(maxColRes));

    // 3. offset 越界 (offset > totalLines) 且携带 column_offset：统一由 offset 越界优先拦截
    EnvironmentCapabilityResult beyondLineWithCol =
        invoke(
            read,
            "{\"path\":\"sample.txt\",\"offset\":5,\"column_offset\":1,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(beyondLineWithCol.error());
    String expectedBeyondLine =
        String.join(
            "\n",
            "path: sample.txt",
            "ends_with_newline: yes",
            "lsp: supported",
            "",
            "[Showing 0 lines of 3.]");
    assertEquals(expectedBeyondLine, text(beyondLineWithCol));
  }

  /** 验证超长行读取出的真实正文片段能直接作为 old_string 顺利通过 EditCapability 精确替换。 */
  @Test
  void readExactFragmentRoundTripThroughEditCapability() throws Exception {
    Path file = workdir.resolve("roundtrip.txt");
    String longLine = "UNIQUE_START_" + "Z".repeat(2500) + "_UNIQUE_END";
    Files.writeString(file, "head\n" + longLine + "\ntail\n");

    ReadCapability read = new ReadCapability(config(), executor);
    EditCapability edit = new EditCapability(config(), executor);

    // 1. 读取长行第一分片
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
    assertEquals(2000, exactFragment.codePointCount(0, exactFragment.length()));

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
    assertTrue(updated.contains("head\nREPLACED_CHUNK" + "Z".repeat(513) + "_UNIQUE_END\ntail\n"));
  }

  /** 验证多行且多超长行下输出严格受控于 48 KiB 字节上限。 */
  @Test
  void readEnforcesResponseByteCeiling() throws Exception {
    Path file = workdir.resolve("huge-cjk.txt");
    // 每行 2000 个 3 字节 CJK 字符（约 6000 字节），15 行文本约 90 KiB，远超 48 KiB 上限
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 15; i++) {
      sb.append("第").append(i + 1).append("行_").append("字".repeat(1995)).append("\n");
    }
    Files.writeString(file, sb.toString());

    ReadCapability read = new ReadCapability(config(), executor);
    EnvironmentCapabilityResult res =
        invoke(
            read,
            "{\"path\":\"huge-cjk.txt\",\"offset\":1,\"limit\":15,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res.error());
    String out = text(res);
    byte[] utf8 = out.getBytes(StandardCharsets.UTF_8);
    assertTrue(
        utf8.length <= 48 * 1024,
        "response bytes (" + utf8.length + ") must not exceed MAX_RESPONSE_BYTES (49152)");
    assertTrue(out.contains("[Showing lines 1-"));
    assertTrue(out.contains("Re-run read with offset="));
  }

  /** 验证增补平面（4 字节 UTF-8）超长行首切片无代理对截断且响应 <= 48 KiB。 */
  @Test
  void readSupplementaryPlaneLongLinePreservesSurrogatePairs() throws Exception {
    Path file = workdir.resolve("rocket.txt");
    String line = "🚀".repeat(2500);
    Files.writeString(file, line + "\n");

    ReadCapability read = new ReadCapability(config(), executor);

    // 首切片：2000 个 🚀 码点，严格在 Unicode 码点边界切断，无半代理项
    EnvironmentCapabilityResult res1 =
        invoke(
            read,
            "{\"path\":\"rocket.txt\",\"offset\":1,\"workdir\":" + json(workdir.toString()) + "}");
    assertFalse(res1.error());
    String text1 = text(res1);
    byte[] bytes1 = text1.getBytes(StandardCharsets.UTF_8);
    assertTrue(bytes1.length <= 48 * 1024, "response bytes must be <= 48 KiB");

    String line1 = text1.lines().filter(l -> l.startsWith("1|")).findFirst().orElseThrow();
    String fragment1 = line1.substring(2);
    assertEquals(2000, fragment1.codePointCount(0, fragment1.length()));
    assertEquals(4000, fragment1.length());
    assertTrue(
        text1.endsWith(
            "\n\n[Showing columns 1-2000 of 2500 on line 1. Re-run read with offset=1, limit=1, column_offset=2001 to continue.]"));

    // 续读切片：剩余 500 个 🚀 码点
    EnvironmentCapabilityResult res2 =
        invoke(
            read,
            "{\"path\":\"rocket.txt\",\"offset\":1,\"limit\":1,\"column_offset\":2001,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(res2.error());
    String text2 = text(res2);
    assertTrue(text2.getBytes(StandardCharsets.UTF_8).length <= 48 * 1024);
    String line2 = text2.lines().filter(l -> l.startsWith("1|")).findFirst().orElseThrow();
    String fragment2 = line2.substring(2);
    assertEquals(500, fragment2.codePointCount(0, fragment2.length()));
    assertEquals(1000, fragment2.length());
    assertTrue(text2.endsWith("\n\n[Showing columns 2001-2500 of 2500 on line 1.]"));
  }

  /** 验证多行读取在 48 KiB 边界处精准打包并给出确定性下一行 offset。 */
  @Test
  void readMultiLinePackingNearByteBoundaryExact() throws Exception {
    Path file = workdir.resolve("pack.txt");
    // 30 行，每行 2000 字符，格式化后每行恰好 2003 字节；在 48 KiB 约束下确定性容纳 24 行
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
    byte[] utf8 = out.getBytes(StandardCharsets.UTF_8);

    assertEquals(48218, utf8.length);
    assertTrue(utf8.length <= 48 * 1024, "response must be <= 48 KiB");
    assertTrue(out.contains(" 1|A"));
    assertTrue(out.contains("24|A"));
    assertFalse(out.contains("25|"));
    assertTrue(
        out.endsWith("\n\n[Showing lines 1-24 of 30. Re-run read with offset=25 to continue.]"));
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
    // 头部读取
    EnvironmentCapabilityResult head =
        invoke(
            read,
            "{\"path\":\"huge.log\",\"offset\":1,\"limit\":3,\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(head.error(), text(head));
    assertTrue(text(head).contains("1|line-0-"), text(head));
    assertTrue(text(head).contains("of " + lineCount), text(head));
    assertTrue(text(head).contains("Re-run read with offset=4"), text(head));

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

  /** 验证 ReadCapability 针对各种语言后缀检测 LSP 语言。 */
  @Test
  void readDetectsLspLanguages() {
    assertEquals("java", ReadCapability.detectLanguage(Path.of("App.java")));
    assertEquals("typescript", ReadCapability.detectLanguage(Path.of("index.ts")));
    assertEquals("javascript", ReadCapability.detectLanguage(Path.of("index.js")));
    assertEquals("python", ReadCapability.detectLanguage(Path.of("script.py")));
    assertEquals("go", ReadCapability.detectLanguage(Path.of("main.go")));
    assertEquals("rust", ReadCapability.detectLanguage(Path.of("lib.rs")));
    assertEquals("c", ReadCapability.detectLanguage(Path.of("main.c")));
    assertEquals("c", ReadCapability.detectLanguage(Path.of("header.h")));
    assertEquals("cpp", ReadCapability.detectLanguage(Path.of("source.cpp")));
    assertEquals("cpp", ReadCapability.detectLanguage(Path.of("source.cc")));
    assertEquals("cpp", ReadCapability.detectLanguage(Path.of("header.hpp")));
  }

  /** 验证 WriteCapability 保留已存在文件的换行风格与编码。 */
  @Test
  void writePreservesExistingLineEndingsAndRejectsDirectory() throws Exception {
    WriteCapability write = new WriteCapability(config(), executor);

    // CRLF
    Path crlfFile = workdir.resolve("crlf.txt");
    Files.writeString(crlfFile, "line1\r\nline2\r\n");
    EnvironmentCapabilityResult resCrlf =
        invoke(
            write,
            "{\"path\":\"crlf.txt\",\"content\":\"a\\nb\\n\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(resCrlf.error());
    assertEquals("a\r\nb\r\n", Files.readString(crlfFile));

    // CR
    Path crFile = workdir.resolve("cr.txt");
    Files.write(crFile, "line1\rline2\r".getBytes(StandardCharsets.UTF_8));
    EnvironmentCapabilityResult resCr =
        invoke(
            write,
            "{\"path\":\"cr.txt\",\"content\":\"a\\nb\\n\",\"workdir\":"
                + json(workdir.toString())
                + "}");
    assertFalse(resCr.error());
    assertArrayEquals("a\rb\r".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(crFile));

    // 拒绝目录作为写目标
    Path dir = workdir.resolve("mydir");
    Files.createDirectory(dir);
    EnvironmentCapabilityResult resDir =
        invoke(
            write,
            "{\"path\":\"mydir\",\"content\":\"x\",\"workdir\":" + json(workdir.toString()) + "}");
    assertTrue(resDir.error());
    assertTrue(text(resDir).contains("path is a directory"));

    // 行尾样式检测辅助函数
    assertEquals("\n", WriteCapability.detectLineEnding(null));
    assertEquals("mixed", WriteCapability.detectLineEnding("a\r\nb\n"));
    assertEquals("\r\n", WriteCapability.detectLineEnding("a\r\nb\r\n"));
    assertEquals("\r", WriteCapability.detectLineEnding("a\rb\r"));
    assertEquals("\n", WriteCapability.detectLineEnding("a\nb\n"));
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
