package fun.fengwk.kkstudio.harness.daemon.coding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextArtifactMetadata;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 验证 {@link OutputSpool} 的内联缓冲、跨阈值转临时文件流式写入、硬上限截断以及自动清理行为。 */
class OutputSpoolTest {

  /** 验证空输出返回 0 字节、0 物理行，且内联返回空文本。 */
  @Test
  void emptyOutputProducesZeroBytesAndZeroLinesInline() throws IOException {
    InMemoryResourceStore store = new InMemoryResourceStore();
    try (OutputSpool spool = new OutputSpool()) {
      assertEquals(0, spool.totalBytes());
      assertEquals(0, spool.totalLines());
      assertFalse(spool.isSpilled());
      assertFalse(spool.isTooLarge());

      EnvironmentCapabilityResult result = spool.finish("call-1", false, store, "text/plain");
      assertFalse(result.error());
      assertEquals(1, result.contents().size());
      assertTrue(result.contents().getFirst() instanceof TextResultContent);
      assertEquals("", ((TextResultContent) result.contents().getFirst()).text());
    }
  }

  /** 验证物理行计数规则：空文本 0，单行无 LF 为 1，CRLF 计 1，末尾有/无 LF 计数正确。 */
  @Test
  void countsPhysicalLinesAccurately() throws IOException {
    // 单行无 LF
    try (OutputSpool spool = new OutputSpool()) {
      spool.write("hello".getBytes(StandardCharsets.UTF_8));
      assertEquals(5, spool.totalBytes());
      assertEquals(1, spool.totalLines());
    }

    // 单行带 LF
    try (OutputSpool spool = new OutputSpool()) {
      spool.write("hello\n".getBytes(StandardCharsets.UTF_8));
      assertEquals(6, spool.totalBytes());
      assertEquals(1, spool.totalLines());
    }

    // CRLF 换行
    try (OutputSpool spool = new OutputSpool()) {
      spool.write("hello\r\n".getBytes(StandardCharsets.UTF_8));
      assertEquals(7, spool.totalBytes());
      assertEquals(1, spool.totalLines());
    }

    // 多行末尾带 LF
    try (OutputSpool spool = new OutputSpool()) {
      spool.write("line1\nline2\n".getBytes(StandardCharsets.UTF_8));
      assertEquals(12, spool.totalBytes());
      assertEquals(2, spool.totalLines());
    }

    // 多行末尾无 LF
    try (OutputSpool spool = new OutputSpool()) {
      spool.write("line1\nline2".getBytes(StandardCharsets.UTF_8));
      assertEquals(11, spool.totalBytes());
      assertEquals(2, spool.totalLines());
    }

    // 纯换行
    try (OutputSpool spool = new OutputSpool()) {
      spool.write("\n\n\n".getBytes(StandardCharsets.UTF_8));
      assertEquals(3, spool.totalBytes());
      assertEquals(3, spool.totalLines());
    }

    // 分块写入换行与字符
    try (OutputSpool spool = new OutputSpool()) {
      spool.write("a".getBytes(StandardCharsets.UTF_8));
      assertEquals(1, spool.totalLines());
      spool.write("\n".getBytes(StandardCharsets.UTF_8));
      assertEquals(1, spool.totalLines());
      spool.write("b".getBytes(StandardCharsets.UTF_8));
      assertEquals(2, spool.totalLines());
      assertEquals(3, spool.totalBytes());
    }
  }

  /** 验证在字节与行数阈值之内保留内联，且支持非 0 退出（error=true）标识。 */
  @Test
  void staysInlineUnderLimitsAndPreservesErrorFlag() throws IOException {
    InMemoryResourceStore store = new InMemoryResourceStore();
    try (OutputSpool spool = new OutputSpool(20, 5)) {
      spool.write("test error output\n".getBytes(StandardCharsets.UTF_8));
      assertEquals(18, spool.totalBytes());
      assertEquals(1, spool.totalLines());
      assertFalse(spool.isSpilled());

      EnvironmentCapabilityResult result = spool.finish("call-err", true, store, "text/plain");
      assertTrue(result.error());
      assertEquals(1, result.contents().size());
      assertTrue(result.contents().getFirst() instanceof TextResultContent);
      assertEquals(
          "test error output\n", ((TextResultContent) result.contents().getFirst()).text());
    }
  }

  /** 验证超过字节阈值时自动转临时文件流式写入，并在 finish 后发布为单一 ResourceResultContent 并清理临时文件。 */
  @Test
  void spillsToTempFileWhenByteThresholdExceededAndCleansUpAfterFinish() throws IOException {
    InMemoryResourceStore store = new InMemoryResourceStore();
    Path tempPath;
    try (OutputSpool spool = new OutputSpool(10, 100)) {
      spool.write("12345678".getBytes(StandardCharsets.UTF_8));
      assertFalse(spool.isSpilled());

      spool.write("90ab".getBytes(StandardCharsets.UTF_8));
      assertTrue(spool.isSpilled());
      tempPath = spool.tempFile();
      assertNotNull(tempPath);
      assertTrue(Files.exists(tempPath));

      spool.write("cdef".getBytes(StandardCharsets.UTF_8));
      assertEquals(16, spool.totalBytes());

      EnvironmentCapabilityResult result = spool.finish("call-spill", false, store, "text/plain");
      assertFalse(result.error());
      assertEquals(1, result.contents().size());
      assertTrue(result.contents().getFirst() instanceof ResourceResultContent);

      ResourceResultContent content = (ResourceResultContent) result.contents().getFirst();
      assertEquals("1234567890abcdef", content.preview());
      assertEquals(16, content.resource().size());
      assertEquals(InMemoryResourceStore.sha256Of("1234567890abcdef"), content.resource().sha256());

      TextArtifactMetadata metadata = content.textMetadata();
      assertNotNull(metadata);
      assertEquals(16, metadata.totalBytes());
      assertEquals(1, metadata.totalLines());

      // 验证 finish 后临时文件已被清理
      assertFalse(Files.exists(tempPath));
    }
  }

  /** 验证超过行数阈值时自动转临时文件流式写入。 */
  @Test
  void spillsToTempFileWhenLineThresholdExceeded() throws IOException {
    InMemoryResourceStore store = new InMemoryResourceStore();
    try (OutputSpool spool = new OutputSpool(1000, 2)) {
      spool.write("line 1\nline 2\n".getBytes(StandardCharsets.UTF_8));
      assertFalse(spool.isSpilled());

      spool.write("line 3\n".getBytes(StandardCharsets.UTF_8));
      assertTrue(spool.isSpilled());
      assertEquals(3, spool.totalLines());

      EnvironmentCapabilityResult result = spool.finish("call-line", false, store, "text/plain");
      assertEquals(1, result.contents().size());
      assertTrue(result.contents().getFirst() instanceof ResourceResultContent);
      ResourceResultContent content = (ResourceResultContent) result.contents().getFirst();
      assertEquals("line 1\nline 2\nline 3\n", content.preview());
      assertEquals(3, content.textMetadata().totalLines());
    }
  }

  /** 验证超过硬上限时停止写入并返回明确的 OUTPUT_TOO_LARGE 错误，且不误报完整性。 */
  @Test
  void hardLimitReturnsOutputTooLargeErrorWithoutMisstatingCompleteness() throws IOException {
    InMemoryResourceStore store = new InMemoryResourceStore();
    try (OutputSpool spool = new OutputSpool(10, 5, 25)) {
      spool.write("12345678901234567890".getBytes(StandardCharsets.UTF_8)); // 20 bytes
      assertFalse(spool.isTooLarge());

      spool.write("1234567890".getBytes(StandardCharsets.UTF_8)); // +10 bytes = 30 bytes > 25
      assertTrue(spool.isTooLarge());

      // 进一步写入应被忽略
      spool.write("more-bytes".getBytes(StandardCharsets.UTF_8));

      EnvironmentCapabilityResult result = spool.finish("call-large", false, store, "text/plain");
      assertTrue(result.error());
      assertEquals(1, result.contents().size());
      assertTrue(result.contents().getFirst() instanceof TextResultContent);
      String text = ((TextResultContent) result.contents().getFirst()).text();
      assertTrue(text.contains("OUTPUT_TOO_LARGE"));
    }
  }

  /** 验证未调用 finish 或发生异常时，close() 保证删除临时文件。 */
  @Test
  void closeCleansUpTempFileWhenNotFinished() throws IOException {
    Path tempPath;
    try (OutputSpool spool = new OutputSpool(5, 5)) {
      spool.write("1234567890".getBytes(StandardCharsets.UTF_8));
      assertTrue(spool.isSpilled());
      tempPath = spool.tempFile();
      assertNotNull(tempPath);
      assertTrue(Files.exists(tempPath));
    }
    // try-with-resources 退出后自动调用 close()，临时文件应已被删除
    assertFalse(Files.exists(tempPath));
  }

  /** 验证 preview 截取最多 20 行且最多 2 KiB，并且不切断 UTF-8 字符。 */
  @Test
  void previewExtractionCapsLinesAndBytesSafely() {
    // 验证超过 20 行时截断在 20 行
    StringBuilder lines = new StringBuilder();
    for (int i = 1; i <= 30; i++) {
      lines.append("line-").append(i).append("\n");
    }
    String preview20 =
        OutputSpool.extractPreview(lines.toString().getBytes(StandardCharsets.UTF_8));
    String[] split = preview20.split("\n", -1);
    // split 会包含最后的空串
    assertEquals(20, split.length - 1);
    assertTrue(preview20.startsWith("line-1\n"));
    assertTrue(preview20.endsWith("line-20\n"));

    // 验证单行超过 2048 字节时截断在 2048 字节内
    String longLine = "a".repeat(3000);
    String previewBytes = OutputSpool.extractPreview(longLine.getBytes(StandardCharsets.UTF_8));
    assertEquals(
        OutputSpool.PREVIEW_MAX_BYTES, previewBytes.getBytes(StandardCharsets.UTF_8).length);

    // 验证多字节 UTF-8 字符不被半截切断
    String emojiLine = "🔥".repeat(1000); // 每个 emoji 4 字节
    String previewEmoji = OutputSpool.extractPreview(emojiLine.getBytes(StandardCharsets.UTF_8));
    byte[] emojiBytes = previewEmoji.getBytes(StandardCharsets.UTF_8);
    assertTrue(emojiBytes.length <= OutputSpool.PREVIEW_MAX_BYTES);
    assertEquals(0, emojiBytes.length % 4); // 必须是完整的 emoji
  }

  /** 验证单字节写入与边界参数合法性。 */
  @Test
  void handlesSingleByteAndBoundsChecks() throws IOException {
    try (OutputSpool spool = new OutputSpool()) {
      spool.write((int) 'x');
      assertEquals(1, spool.totalBytes());
      assertEquals(1, spool.totalLines());

      spool.write(new byte[0]);
      assertEquals(1, spool.totalBytes());

      assertThrows(IndexOutOfBoundsException.class, () -> spool.write(new byte[5], -1, 1));
      assertThrows(IndexOutOfBoundsException.class, () -> spool.write(new byte[5], 0, 6));
      assertThrows(NullPointerException.class, () -> spool.write(null));
    }

    assertThrows(IllegalArgumentException.class, () -> new OutputSpool(0, 10));
    assertThrows(IllegalArgumentException.class, () -> new OutputSpool(10, 0));
    assertThrows(IllegalArgumentException.class, () -> new OutputSpool(10, 10, 0));
  }
}
