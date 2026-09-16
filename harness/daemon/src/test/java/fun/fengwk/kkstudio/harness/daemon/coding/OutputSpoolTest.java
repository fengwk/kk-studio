package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

/**
 * 针对 {@link OutputSpool} 的行为断言测试：小输出内联、大输出落本地 durable 全文并返回有界预览、捕获预算只截断文件而不终止进程。
 *
 * <p>被冻结的产品语义决定本测试的重点：终态永远只返回一个 {@link TextResultContent}（而不是 Resource），大文本必须给出绝对路径、总字节/行数与
 * read/grep 指引，任何本地存储失败都只降级为预览而不抛出、不杀子进程。
 */
class OutputSpoolTest {

  @TempDir Path root;

  private TextOutputStore store() {
    return TextOutputStore.open(root.resolve("text"), root.resolve("staging"));
  }

  /** 空输出内联返回：0 字节、0 物理行、不落盘，且 details 仍是合法 JSON 对象。 */
  @Test
  void emptyOutputProducesZeroBytesAndZeroLinesInline() throws IOException {
    try (OutputSpool spool = new OutputSpool(store(), "call-1")) {
      assertEquals(0, spool.totalBytes());
      assertEquals(0, spool.totalLines());
      assertFalse(spool.isSpilled());
      assertFalse(spool.isCaptureTruncated());
      assertFalse(spool.isCaptureFailed());
      assertNull(spool.stagingFile());

      EnvironmentCapabilityResult result = spool.finish(false);
      assertFalse(result.error());
      assertEquals(1, result.contents().size());
      assertEquals("", text(result));
      assertEquals("{}", result.detailsJson());
    }
    assertTrue(listDirectory(root.resolve("text")).isEmpty(), "小输出不得产生 durable 文件");
    assertTrue(listDirectory(root.resolve("staging")).isEmpty(), "小输出不得产生中转文件");
  }

  /** 物理行计数规则：空文本 0、单行无 LF 为 1、CRLF 计 1、末尾有无 LF 与分块写入都要正确。 */
  @Test
  void countsPhysicalLinesAccurately() throws IOException {
    assertEquals(1, linesOf("hello"));
    assertEquals(1, linesOf("hello\n"));
    assertEquals(1, linesOf("hello\r\n"));
    assertEquals(2, linesOf("line1\nline2\n"));
    assertEquals(2, linesOf("line1\nline2"));
    assertEquals(3, linesOf("\n\n\n"));

    try (OutputSpool spool = new OutputSpool(store(), "chunked")) {
      spool.write("a".getBytes(StandardCharsets.UTF_8));
      assertEquals(1, spool.totalLines());
      spool.write("\n".getBytes(StandardCharsets.UTF_8));
      assertEquals(1, spool.totalLines());
      spool.write("b".getBytes(StandardCharsets.UTF_8));
      assertEquals(2, spool.totalLines());
      assertEquals(3, spool.totalBytes());
    }
  }

  /** 阈值之内保持完整内联，并保留非 0 退出的 error 标识（失败运行的 stdout 仍是可读事实）。 */
  @Test
  void staysInlineUnderLimitsAndPreservesErrorFlag() throws IOException {
    try (OutputSpool spool = new OutputSpool(store(), "call-err", 20, 5, 1024)) {
      spool.write("test error output\n".getBytes(StandardCharsets.UTF_8));
      assertEquals(18, spool.totalBytes());
      assertEquals(1, spool.totalLines());
      assertFalse(spool.isSpilled());

      EnvironmentCapabilityResult result = spool.finish(true);
      assertTrue(result.error());
      assertEquals(1, result.contents().size());
      assertEquals("test error output\n", text(result));
    }
  }

  /** 跨越字节阈值时发布 durable 全文：终态是单个 TextResultContent（不是 Resource），预览加路径事实与 read/grep 指引。 */
  @Test
  void spillsOnByteThresholdAndPublishesTextResultContentWithPath() throws IOException {
    TextOutputStore store = store();
    Path stagingPath;
    try (OutputSpool spool = new OutputSpool(store, "call-spill", 10, 100, 1024)) {
      spool.write("12345678".getBytes(StandardCharsets.UTF_8));
      assertFalse(spool.isSpilled());

      spool.write("90ab".getBytes(StandardCharsets.UTF_8));
      assertTrue(spool.isSpilled());
      stagingPath = spool.stagingFile();
      assertNotNull(stagingPath);
      assertTrue(Files.exists(stagingPath));

      spool.write("cdef".getBytes(StandardCharsets.UTF_8));
      assertEquals(16, spool.totalBytes());

      EnvironmentCapabilityResult result = spool.finish(false);
      assertFalse(result.error());
      assertEquals(1, result.contents().size());
      // 大文本绝不是 Resource：不经过 ResourceStore、不做内容寻址。
      assertFalse(result.contents().getFirst() instanceof ResourceResultContent);
      assertTrue(result.contents().getFirst() instanceof TextResultContent);

      String preview = text(result);
      assertTrue(preview.contains("12345678"), "预览必须包含可读的头部内容：" + preview);
      assertTrue(preview.contains("1234567890abcdef".substring(12)), "预览必须包含尾部内容：" + preview);
      assertTrue(preview.contains("16 bytes"), "预览必须报告总字节数：" + preview);
      assertTrue(preview.contains("1 lines"), "预览必须报告总行数：" + preview);

      JsonNode details = AbstractCodingCapability.OBJECT_MAPPER.readTree(result.detailsJson());
      JsonNode textOutput = details.path("textOutput");
      assertEquals(16, textOutput.path("totalBytes").asLong());
      assertEquals(1, textOutput.path("totalLines").asLong());
      assertEquals(16, textOutput.path("capturedBytes").asLong());
      assertFalse(textOutput.path("captureTruncated").asBoolean());
      assertFalse(textOutput.path("captureFailed").asBoolean());

      Path published = Path.of(textOutput.path("path").asText());
      assertTrue(published.isAbsolute(), "必须给出绝对路径，模型才能直接 read/grep");
      assertTrue(published.toString().endsWith(".log"));
      assertTrue(preview.contains(published.toString()), "预览必须内联该绝对路径：" + preview);
      assertTrue(textOutput.path("readHint").asText().contains("read"));
      assertEquals("1234567890abcdef", Files.readString(published), "durable 全文必须是完整内容");
      assertTrue(Files.isRegularFile(published, LinkOption.NOFOLLOW_LINKS));

      // finish 已把中转文件发布为 durable 全文。
      assertFalse(Files.exists(stagingPath));
      assertNull(spool.stagingFile());
    }
    assertEquals(1, listDirectory(root.resolve("text")).size());
  }

  /** 跨越行数阈值同样落盘（不能只按字节判断，否则少量超长行会绕过内联上界）。 */
  @Test
  void spillsOnLineThresholdAndKeepsLocalFullText() throws IOException {
    try (OutputSpool spool = new OutputSpool(store(), "call-line", 1000, 2, 4096)) {
      spool.write("line 1\nline 2\n".getBytes(StandardCharsets.UTF_8));
      assertFalse(spool.isSpilled());

      spool.write("line 3\n".getBytes(StandardCharsets.UTF_8));
      assertTrue(spool.isSpilled());
      assertEquals(3, spool.totalLines());

      EnvironmentCapabilityResult result = spool.finish(false);
      JsonNode textOutput =
          AbstractCodingCapability.OBJECT_MAPPER.readTree(result.detailsJson()).path("textOutput");
      assertEquals(3, textOutput.path("totalLines").asLong());
      assertEquals(
          "line 1\nline 2\nline 3\n",
          Files.readString(Path.of(textOutput.path("path").asText())),
          "durable 全文必须保留全部行");
    }
  }

  /** 达到捕获预算只停止文件捕获并明确报告截断；进程与调用方继续正常，帧内不出现任何“杀进程”语义。 */
  @Test
  void captureBudgetStopsFileCaptureWithoutFailingTheCall() throws IOException {
    TextOutputStore store = store();
    try (OutputSpool spool = new OutputSpool(store, "call-budget", 10, 100, 25)) {
      spool.write("12345678901234567890".getBytes(StandardCharsets.UTF_8)); // 20 bytes
      assertFalse(spool.isCaptureTruncated());

      spool.write("1234567890".getBytes(StandardCharsets.UTF_8)); // +10 => 30 > 25
      assertTrue(spool.isCaptureTruncated());
      assertEquals(25, spool.capturedBytes(), "只能捕获到预算上限");

      // 预算耗尽后继续写入：仍必须准确计数（进程输出并未停止）。
      spool.write("more-bytes".getBytes(StandardCharsets.UTF_8));
      assertEquals(40, spool.totalBytes());
      assertEquals(25, spool.capturedBytes(), "截断后不再增长");

      EnvironmentCapabilityResult result = spool.finish(false);
      assertFalse(result.error(), "输出体积永远不是调用失败或终止进程的理由");
      String preview = text(result);
      assertTrue(preview.contains("40 bytes"), "总数必须反映真实输出量：" + preview);
      assertTrue(preview.contains("Capture stopped"), "必须明确报告捕获被截断：" + preview);
      assertTrue(preview.contains("-byte daemon budget"), preview);
      assertTrue(preview.contains("ran to completion"), "必须说明命令本身已正常结束、退出码仍然有效：" + preview);

      JsonNode textOutput =
          AbstractCodingCapability.OBJECT_MAPPER.readTree(result.detailsJson()).path("textOutput");
      assertTrue(textOutput.path("captureTruncated").asBoolean());
      assertEquals(40, textOutput.path("totalBytes").asLong());
      assertEquals(25, textOutput.path("capturedBytes").asLong());
      assertEquals(
          "1234567890123456789012345",
          Files.readString(Path.of(textOutput.path("path").asText())),
          "已捕获的前缀仍必须作为可读文件发布");
    }
  }

  /** 本地存储不可用时只降级为有界预览：不抛异常、没有路径、明确说明全文无法保存。 */
  @Test
  void localStorageFailureDegradesToBoundedPreviewWithoutThrowing() throws IOException {
    assumeTrue(isPosixSupported(), "需要 POSIX 权限位来构造确定性的本地写入失败");
    TextOutputStore store = store();
    // 让 staging 目录不可写：创建中转文件必然以 IOException 失败，且不需要任何 mock。
    Files.setPosixFilePermissions(
        store.stagingDirectory(),
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));

    try (OutputSpool spool = new OutputSpool(store, "call-broken", 10, 100, 4096)) {
      spool.write("0123456789abcdef".getBytes(StandardCharsets.UTF_8));

      assertTrue(spool.isCaptureFailed());
      assertTrue(spool.isSpilled());
      assertFalse(spool.isCaptureTruncated());
      assertNull(spool.stagingFile());

      EnvironmentCapabilityResult result = spool.finish(false);
      assertFalse(result.error(), "本地写入失败不得使调用失败");
      String preview = text(result);
      assertTrue(preview.contains("could not be saved to local storage"), preview);
      assertTrue(preview.contains("16 bytes"), preview);
      assertTrue(preview.contains("bounded preview"), preview);

      JsonNode textOutput =
          AbstractCodingCapability.OBJECT_MAPPER.readTree(result.detailsJson()).path("textOutput");
      assertTrue(textOutput.path("captureFailed").asBoolean());
      assertTrue(textOutput.path("path").isMissingNode(), "失败时不得给出不存在的路径");
    }
    try (var entries = Files.list(store.textDirectory())) {
      assertEquals(0, entries.count(), "失败不得留下任何 durable 文件");
    }
  }

  /** 未 finish 就 close 时必须清理未发布中转文件；已发布全文不受 close 影响。 */
  @Test
  void closeRemovesUnpublishedStagingFileOnly() throws IOException {
    TextOutputStore store = store();
    Path abortedStaging;
    try (OutputSpool spool = new OutputSpool(store, "call-abort", 5, 5, 4096)) {
      spool.write("1234567890".getBytes(StandardCharsets.UTF_8));
      assertTrue(spool.isSpilled());
      abortedStaging = spool.stagingFile();
      assertNotNull(abortedStaging);
      assertTrue(Files.exists(abortedStaging));
    }
    assertFalse(Files.exists(abortedStaging), "close 必须清理未发布的中转文件");

    TextOutputStore publishedStore = store();
    Path published;
    try (OutputSpool spool = new OutputSpool(publishedStore, "call-done", 5, 5, 4096)) {
      spool.write("1234567890".getBytes(StandardCharsets.UTF_8));
      spool.finish(false);
    }
    try (var entries = Files.list(publishedStore.textDirectory())) {
      published = entries.findFirst().orElseThrow();
    }
    assertTrue(Files.exists(published), "已发布的 durable 全文不得被 close 删除");
  }

  /** 有界预览本身有上界：head 与 tail 各自受限，中间被省略且明确标注省略字节数。 */
  @Test
  void previewIsBoundedAndMarksTheOmittedMiddle() throws IOException {
    TextOutputStore store = store();
    String head = "HEAD-MARKER-" + "h".repeat(20_000) + "\n";
    String tail = "t".repeat(20_000) + "-TAIL-MARKER\n";
    try (OutputSpool spool = new OutputSpool(store, "call-bounded", 16, 1, 1024 * 1024)) {
      spool.write(head.getBytes(StandardCharsets.UTF_8));
      spool.write(tail.getBytes(StandardCharsets.UTF_8));

      EnvironmentCapabilityResult result = spool.finish(false);
      String preview = text(result);

      assertTrue(preview.contains("HEAD-MARKER-"), "必须保留头部标记：" + preview.length());
      assertTrue(preview.contains("-TAIL-MARKER"), "必须保留尾部标记：" + preview.length());
      assertTrue(preview.contains("bytes omitted here"), "必须标注中间被省略：" + preview.length());
      assertTrue(preview.contains("the middle of the output is not shown"), preview);
      assertTrue(
          preview.getBytes(StandardCharsets.UTF_8).length
              <= OutputSpool.PREVIEW_HEAD_MAX_BYTES + OutputSpool.PREVIEW_TAIL_MAX_BYTES + 1024,
          "预览必须有界，实际 " + preview.getBytes(StandardCharsets.UTF_8).length + " 字节");
    }
  }

  /** 单字节写入与边界参数校验；非法上界必须在构造期拒绝。 */
  @Test
  void handlesSingleByteAndBoundsChecks() throws IOException {
    try (OutputSpool spool = new OutputSpool(store(), "call-bounds")) {
      spool.write((int) 'x');
      assertEquals(1, spool.totalBytes());
      assertEquals(1, spool.totalLines());

      spool.write(new byte[0]);
      assertEquals(1, spool.totalBytes());

      assertThrows(IndexOutOfBoundsException.class, () -> spool.write(new byte[5], -1, 1));
      assertThrows(IndexOutOfBoundsException.class, () -> spool.write(new byte[5], 0, 6));
      assertThrows(NullPointerException.class, () -> spool.write(null));
    }

    TextOutputStore store = store();
    assertThrows(IllegalArgumentException.class, () -> new OutputSpool(store, "x", 0, 10, 10));
    assertThrows(IllegalArgumentException.class, () -> new OutputSpool(store, "x", 10, 0, 10));
    assertThrows(IllegalArgumentException.class, () -> new OutputSpool(store, "x", 10, 10, 0));
    assertThrows(NullPointerException.class, () -> new OutputSpool(null, "x"));
    assertThrows(NullPointerException.class, () -> new OutputSpool(store, null));
  }

  /** 多字节 UTF-8 内容必须被完整保留，不能因为分块写入或字节上界切断字符。 */
  @Test
  void preservesMultiByteUtf8AcrossChunkedWrites() throws IOException {
    TextOutputStore store = store();
    String payload = "🔥中文内容\n".repeat(64);
    try (OutputSpool spool = new OutputSpool(store, "call-utf8", 16, 4, 1024 * 1024)) {
      byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
      for (int index = 0; index < bytes.length; index += 3) {
        spool.write(bytes, index, Math.min(3, bytes.length - index));
      }

      EnvironmentCapabilityResult result = spool.finish(false);
      JsonNode textOutput =
          AbstractCodingCapability.OBJECT_MAPPER.readTree(result.detailsJson()).path("textOutput");
      assertEquals(payload, Files.readString(Path.of(textOutput.path("path").asText())));
      // 解码后的预览不得出现替换字符（说明没有切断多字节序列）。
      assertFalse(text(result).contains("\uFFFD"));
    }
  }

  /**
   * 行数语义必须与 `read`/`grep` 的 {@link TextStreams} 逐行扫描完全一致。
   *
   * <p>这是可观测契约：`process.exec` 终态报告的总行数，随后会被 `read` 对同一文件再次报告一次。CR、LF、CRLF 三种终止符 只要两边解释不同（例如只数 LF、或把
   * CRLF 记成两行），模型就会看到两个互相矛盾的总数。CRLF 必须只记一次换行。
   */
  @Test
  void lineCountingMatchesTextStreamsForCrLfAndCrlf() throws IOException, InterruptedException {
    String[] payloads = {
      "a\rb", "a\r\nb", "a\r", "hello\r\n", "hello\n", "line1\nline2", "\n\n\n", "a\r\n\r\nb", ""
    };

    for (String payload : payloads) {
      Path file = root.resolve("lines-" + Math.abs(payload.hashCode()) + ".txt");
      Files.write(file, payload.getBytes(StandardCharsets.UTF_8));

      long spoolLines;
      try (OutputSpool spool = new OutputSpool(store(), "call-crlf")) {
        spool.write(payload.getBytes(StandardCharsets.UTF_8));
        spoolLines = spool.totalLines();
      }

      assertEquals(
          textStreamsLineCount(file), spoolLines, "行数必须与 read/grep 的逐行扫描一致，输入=" + escape(payload));
    }

    // CRLF 只记一次换行，而不是 CR 与 LF 各记一次；已终止行与末尾未终止行分别计数。
    assertEquals(2, linesOf("a\r\nb"));
    assertEquals(2, linesOf("a\r\nb\n"));
    assertEquals(2, linesOf("a\rb"));
    assertEquals(1, linesOf("a\r"));
    assertEquals(1, linesOf("a\n"));
  }

  /**
   * 预览必须按完整 UTF-8 字符边界裁剪：字节上界落在多字节字符内部时不得产生替换字符。
   *
   * <p>把 4 字节 emoji 精确压在 head 缓冲的 4 KiB 边界上，同时断言 durable 全文逐字节完整——预览是摘要，但摘要不能是
   * 乱码。省略字节数同样必须精确，否则模型据它推算文件结构会得到错误结论。
   */
  @Test
  void previewCutsAtCharacterBoundaryWithoutReplacementCharacters() throws IOException {
    TextOutputStore store = store();
    // 4095 个单字节字符后紧跟一个 4 字节 emoji：head 缓冲的 4096 字节正好落在 emoji 内部。
    String payload =
        "a".repeat(OutputSpool.PREVIEW_HEAD_MAX_BYTES - 1)
            + "\uD83D\uDD25"
            + "b".repeat(20_000)
            + "\n";

    try (OutputSpool spool = new OutputSpool(store, "call-utf8-boundary", 16, 1, 1024 * 1024)) {
      spool.write(payload.getBytes(StandardCharsets.UTF_8));
      assertTrue(spool.isSpilled(), "超过内联阈值必须落盘");

      EnvironmentCapabilityResult result = spool.finish(false);
      String preview = text(result);

      assertFalse(preview.contains("\uFFFD"), "预览不得因切断多字节字符而产生替换字符，实际长度 " + preview.length());
      assertTrue(preview.startsWith("a"), preview.substring(0, Math.min(16, preview.length())));

      JsonNode textOutput =
          AbstractCodingCapability.OBJECT_MAPPER.readTree(result.detailsJson()).path("textOutput");
      assertEquals(
          payload,
          Files.readString(Path.of(textOutput.path("path").asText())),
          "durable 全文必须逐字节完整，不受预览裁剪影响");
      assertEquals(
          payload.getBytes(StandardCharsets.UTF_8).length, textOutput.path("totalBytes").asLong());

      // 省略字节数必须是原始字节差：head 实际展示的字节数加回省略数应等于总量减去尾部窗口。
      String omittedText =
          preview.substring(preview.indexOf("[... ") + 5, preview.indexOf(" bytes omitted"));
      long omitted = Long.parseLong(omittedText);
      assertTrue(omitted > 0, "本用例必须真的省略了中间内容：" + preview.length());
      assertTrue(omitted < textOutput.path("totalBytes").asLong(), "省略数不能超过总量：" + omitted);
    }
  }

  /**
   * 输出量小于 head 与 tail 缓冲容量之和时，两个窗口覆盖同一段内容，预览不得把同一批字节展示两次。
   *
   * <p>这是重叠窗口的退化情形：若不做去重，模型会看到被重复拼接的内容，并可能误判输出的真实结构。
   */
  @Test
  void previewDoesNotRepeatContentWhenHeadAndTailWindowsOverlap() throws IOException {
    String marker = "MARK-";
    String payload = marker + "x".repeat(50) + "-END";

    try (OutputSpool spool = new OutputSpool(store(), "call-overlap", 16, 1, 1024 * 1024)) {
      spool.write(payload.getBytes(StandardCharsets.UTF_8));
      assertTrue(spool.isSpilled(), "超过内联阈值必须落盘");
      assertTrue(
          spool.totalBytes()
              < OutputSpool.PREVIEW_HEAD_MAX_BYTES + OutputSpool.PREVIEW_TAIL_MAX_BYTES,
          "本用例要求输出小于两个窗口容量之和才能构造重叠");

      String preview = text(spool.finish(false));

      assertEquals(1, countOccurrences(preview, marker), "重叠窗口不得重复展示同一段内容：" + preview);
      assertFalse(preview.contains("bytes omitted here"), "没有真正省略内容时不得给出省略提示：" + preview);
      assertTrue(preview.contains(payload), "重叠退化情形必须直接展示完整内容：" + preview);
    }
  }

  /**
   * 本地存储失败后必须彻底清理：不残留中转文件、不残留 durable 文件，且 finish/close 都不抛异常。
   *
   * <p>本地写入失败是降级路径而不是崩溃路径；若该路径漏关文件描述符或漏删中转文件，长期运行的 Daemon 会把句柄和磁盘慢慢耗尽。
   */
  @Test
  void storageFailureLeavesNoResidualFilesAndNeverThrows() throws IOException {
    assumeTrue(isPosixSupported(), "需要 POSIX 权限位来构造确定性的本地写入失败");
    TextOutputStore store = store();
    // 让 staging 目录不可写：中转文件创建必然失败。
    Files.setPosixFilePermissions(
        store.stagingDirectory(),
        Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));

    OutputSpool spool = new OutputSpool(store, "call-cleanup", 16, 1, 4096);
    spool.write("y".repeat(4096).getBytes(StandardCharsets.UTF_8));
    assertTrue(spool.isCaptureFailed(), "写入失败必须被标记为降级");
    assertNull(spool.stagingFile(), "失败路径不得留下中转文件句柄");

    // 失败路径反复收尾必须幂等且不抛异常。
    EnvironmentCapabilityResult result = spool.finish(false);
    assertFalse(result.error(), "本地存储失败不得让调用失败");
    spool.finish(false);
    spool.close();
    spool.close();

    assertTrue(listDirectory(store.textDirectory()).isEmpty(), "失败不得留下 durable 文件");
    assertTrue(listDirectory(store.stagingDirectory()).isEmpty(), "失败不得留下中转文件");
  }

  /** 通过 {@link TextStreams} 逐行扫描统计文件行数，作为行数语义的独立事实源。 */
  private static long textStreamsLineCount(Path file) throws IOException, InterruptedException {
    TextStreams.Encoding encoding = TextStreams.detectEncoding(TextStreams.probe(file));
    int[] lastLine = {0};
    TextStreams.Outcome outcome =
        TextStreams.forEachLine(
            file,
            encoding,
            (lineNumber, line, truncated) -> {
              lastLine[0] = lineNumber;
              return true;
            });
    assertEquals(lastLine[0], outcome.totalLines(), "逐行扫描的行号必须与总数一致");
    return outcome.totalLines();
  }

  private static int countOccurrences(String text, String needle) {
    int count = 0;
    int index = text.indexOf(needle);
    while (index >= 0) {
      count++;
      index = text.indexOf(needle, index + needle.length());
    }
    return count;
  }

  private static String escape(String value) {
    return value.replace("\r", "\\r").replace("\n", "\\n");
  }

  private int linesOf(String text) throws IOException {
    try (OutputSpool spool = new OutputSpool(store(), "lines")) {
      spool.write(text.getBytes(StandardCharsets.UTF_8));
      return (int) spool.totalLines();
    }
  }

  private static String text(EnvironmentCapabilityResult result) {
    assertEquals(1, result.contents().size(), "终态必须只有一个内容项");
    return ((TextResultContent) result.contents().getFirst()).text();
  }

  private static boolean isPosixSupported() {
    return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  }

  private static List<Path> listDirectory(Path directory) throws IOException {
    if (!Files.isDirectory(directory)) {
      return List.of();
    }
    try (var entries = Files.list(directory)) {
      return entries.toList();
    }
  }
}
