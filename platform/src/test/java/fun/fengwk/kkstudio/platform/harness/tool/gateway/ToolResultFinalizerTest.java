package fun.fengwk.kkstudio.platform.harness.tool.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextArtifactMetadata;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.runtime.resource.ResourceStore;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@link ToolResultFinalizer} 单元测试：
 *
 * <ul>
 *   <li>50 KiB / 2000 物理行内联判定；
 *   <li>大输出聚合投影（唯一 ResourceResultContent、首文本位置替换、非文本顺序保留）；
 *   <li>严格 UTF-8 与物理行计数、UTF-8/行数安全的 raw preview（无 marker）；
 *   <li>16 MiB 硬上限（OUTPUT_TOO_LARGE）；
 *   <li>无效 Unicode（INVALID_RESULT）；
 *   <li>存储失败收敛（RESOURCE_STORE_FAILED）。
 * </ul>
 */
class ToolResultFinalizerTest {

  private static final String TOOL_NAME = "demo_tool";

  @Test
  void countPhysicalLinesMatchesSpec() {
    assertEquals(0, ToolResultFinalizer.countPhysicalLines(null));
    assertEquals(0, ToolResultFinalizer.countPhysicalLines(""));
    assertEquals(1, ToolResultFinalizer.countPhysicalLines("hello"));
    assertEquals(1, ToolResultFinalizer.countPhysicalLines("hello\n"));
    assertEquals(1, ToolResultFinalizer.countPhysicalLines("hello\r\n"));
    assertEquals(2, ToolResultFinalizer.countPhysicalLines("hello\nworld"));
    assertEquals(2, ToolResultFinalizer.countPhysicalLines("hello\r\nworld\r\n"));
    assertEquals(1, ToolResultFinalizer.countPhysicalLines("\n"));
    assertEquals(2, ToolResultFinalizer.countPhysicalLines("\n\n"));
    assertEquals(3, ToolResultFinalizer.countPhysicalLines("a\nb\nc"));
  }

  @Test
  void extractRawPreviewIsUtf8AndLineSafeWithoutMarker() {
    assertEquals("", ToolResultFinalizer.extractRawPreview(null));
    assertEquals("", ToolResultFinalizer.extractRawPreview(""));

    // 小文本原样返回
    String small = "hello world\nline 2";
    assertEquals(small, ToolResultFinalizer.extractRawPreview(small));

    // 超过 20 行：截断在第 20 行，不开启第 21 行
    StringBuilder thirtyLines = new StringBuilder();
    for (int i = 1; i <= 30; i++) {
      thirtyLines.append("line ").append(i).append("\n");
    }
    String previewLines = ToolResultFinalizer.extractRawPreview(thirtyLines.toString());
    assertEquals(20, ToolResultFinalizer.countPhysicalLines(previewLines));
    assertTrue(previewLines.endsWith("line 20\n"));
    assertFalse(previewLines.contains("line 21"));

    // 超过 2048 字节：截断在 2048 字节内且不截断 code point
    String longLine = "a".repeat(3000);
    String previewBytes = ToolResultFinalizer.extractRawPreview(longLine);
    assertEquals(ToolResultFinalizer.TRUNCATED_PREVIEW_MAX_UTF8_BYTES, previewBytes.length());
    assertFalse(previewBytes.contains("..."));

    // 包含 4-byte emoji 且边界在 emoji 中间时，不会产生破碎码点
    int budget = ToolResultFinalizer.TRUNCATED_PREVIEW_MAX_UTF8_BYTES;
    String emojiText = "a".repeat(budget - 1) + "\uD83D\uDE00" + "tail".repeat(100);
    String previewEmoji = ToolResultFinalizer.extractRawPreview(emojiText);
    assertEquals("a".repeat(budget - 1), previewEmoji);
    byte[] emojiBytes = previewEmoji.getBytes(StandardCharsets.UTF_8);
    assertTrue(emojiBytes.length <= budget);
  }

  @Test
  void inlinesWhenBothBytesAndLinesAreWithinThreshold() {
    RecordingResourceStore store = new RecordingResourceStore();
    ToolResultFinalizer finalizer = new ToolResultFinalizer(store);

    ToolResult result =
        new ToolResult(
            "call-1",
            List.of(
                new TextResultContent("first line\n"),
                new JsonResultContent("{\"status\":\"ok\"}")),
            false,
            "{}");

    ToolResultFinalizer.Outcome outcome = finalizer.finalizeResult(TOOL_NAME, result);
    ToolResultFinalizer.Outcome.Success success =
        assertInstanceOf(ToolResultFinalizer.Outcome.Success.class, outcome);

    assertEquals(0, store.puts.size());
    assertEquals(2, success.result().contents().size());
    assertEquals("first line\n", ((TextResultContent) success.result().contents().get(0)).text());
    assertEquals(
        "{\"status\":\"ok\"}", ((JsonResultContent) success.result().contents().get(1)).json());
  }

  @Test
  void externalizesWhenUtf8BytesExceedThreshold() {
    RecordingResourceStore store = new RecordingResourceStore();
    ToolResultFinalizer finalizer = new ToolResultFinalizer(store);

    String largeText = "x".repeat(ToolResultFinalizer.INLINE_MAX_UTF8_BYTES + 1);
    ToolResult result =
        new ToolResult("call-1", List.of(new TextResultContent(largeText)), false, "{}");

    ToolResultFinalizer.Outcome outcome = finalizer.finalizeResult(TOOL_NAME, result);
    ToolResultFinalizer.Outcome.Success success =
        assertInstanceOf(ToolResultFinalizer.Outcome.Success.class, outcome);

    assertEquals(1, store.puts.size());
    assertEquals("text/plain", store.puts.get(0).mediaType());
    assertEquals("demo_tool-result.txt", store.puts.get(0).name());

    assertEquals(1, success.result().contents().size());
    ResourceResultContent resource =
        assertInstanceOf(ResourceResultContent.class, success.result().contents().get(0));
    assertEquals("demo_tool-result.txt", resource.resource().name());
    assertNotNull(resource.textMetadata());
    assertEquals(largeText.length(), resource.textMetadata().totalBytes());
    assertEquals(1, resource.textMetadata().totalLines());
    assertEquals(ToolResultFinalizer.extractRawPreview(largeText), resource.preview());
  }

  @Test
  void externalizesWhenPhysicalLinesExceedThreshold() {
    RecordingResourceStore store = new RecordingResourceStore();
    ToolResultFinalizer finalizer = new ToolResultFinalizer(store);

    // 2001 行，每行 "a\n"，总字节 4002（远小于 50 KiB），但行数超出 2000 行
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 2001; i++) {
      sb.append("a\n");
    }
    String multiLine = sb.toString();

    ToolResult result =
        new ToolResult("call-1", List.of(new TextResultContent(multiLine)), false, "{}");

    ToolResultFinalizer.Outcome outcome = finalizer.finalizeResult(TOOL_NAME, result);
    ToolResultFinalizer.Outcome.Success success =
        assertInstanceOf(ToolResultFinalizer.Outcome.Success.class, outcome);

    assertEquals(1, store.puts.size());
    ResourceResultContent resource =
        assertInstanceOf(ResourceResultContent.class, success.result().contents().get(0));
    assertNotNull(resource.textMetadata());
    assertEquals(2001, resource.textMetadata().totalLines());
  }

  @Test
  void singleJsonExternalizedGetsJsonMediaTypeAndExtension() {
    RecordingResourceStore store = new RecordingResourceStore();
    ToolResultFinalizer finalizer = new ToolResultFinalizer(store);

    String largeJson =
        "{\"field\":\"" + "y".repeat(ToolResultFinalizer.INLINE_MAX_UTF8_BYTES) + "\"}";
    ToolResult result =
        new ToolResult("call-1", List.of(new JsonResultContent(largeJson)), false, "{}");

    ToolResultFinalizer.Outcome outcome = finalizer.finalizeResult(TOOL_NAME, result);
    ToolResultFinalizer.Outcome.Success success =
        assertInstanceOf(ToolResultFinalizer.Outcome.Success.class, outcome);

    assertEquals(1, store.puts.size());
    assertEquals("application/json", store.puts.get(0).mediaType());
    assertEquals("demo_tool-result.json", store.puts.get(0).name());
    ResourceResultContent res =
        assertInstanceOf(ResourceResultContent.class, success.result().contents().get(0));
    assertEquals("demo_tool-result.json", res.resource().name());
  }

  @Test
  void multipleContentsPreservesNonTextAndReplacesAtFirstTextIndex() {
    RecordingResourceStore store = new RecordingResourceStore();
    ToolResultFinalizer finalizer = new ToolResultFinalizer(store);

    byte[] bin1 = new byte[] {1, 2};
    byte[] bin2 = new byte[] {3, 4};
    String largeText1 = "a".repeat(30000);
    String largeText2 = "b".repeat(30000); // 30000 + 2 + 30000 = 60002 > 50 KiB

    ToolResult result =
        new ToolResult(
            "call-1",
            List.of(
                new BinaryResultContent("image/png", bin1),
                new TextResultContent(largeText1),
                new BinaryResultContent("image/jpeg", bin2),
                new TextResultContent(largeText2)),
            false,
            "{}");

    ToolResultFinalizer.Outcome outcome = finalizer.finalizeResult(TOOL_NAME, result);
    ToolResultFinalizer.Outcome.Success success =
        assertInstanceOf(ToolResultFinalizer.Outcome.Success.class, outcome);

    // bin1, text, bin2 均入 store（3 次 put）
    assertEquals(3, store.puts.size());
    // 结果 contents: [bin1 resource, text artifact resource, bin2 resource]
    assertEquals(3, success.result().contents().size());

    ResourceResultContent res1 =
        assertInstanceOf(ResourceResultContent.class, success.result().contents().get(0));
    assertEquals("demo_tool-result-1", res1.resource().name());
    assertNull(res1.textMetadata());

    ResourceResultContent textRes =
        assertInstanceOf(ResourceResultContent.class, success.result().contents().get(1));
    assertEquals("demo_tool-result.txt", textRes.resource().name());
    assertNotNull(textRes.textMetadata());

    ResourceResultContent res2 =
        assertInstanceOf(ResourceResultContent.class, success.result().contents().get(2));
    assertEquals("demo_tool-result-3", res2.resource().name());
    assertNull(res2.textMetadata());
  }

  @Test
  void hardLimitExceededReturnsOutputTooLarge() {
    RecordingResourceStore store = new RecordingResourceStore();
    int limit = 100;
    ToolResultFinalizer finalizer = new ToolResultFinalizer(store, limit);

    ToolResult result =
        new ToolResult(
            "call-1", List.of(new TextResultContent("x".repeat(limit + 1))), false, "{}");

    ToolResultFinalizer.Outcome outcome = finalizer.finalizeResult(TOOL_NAME, result);
    ToolResultFinalizer.Outcome.Failed failed =
        assertInstanceOf(ToolResultFinalizer.Outcome.Failed.class, outcome);

    assertEquals(ToolResultFinalizer.OUTPUT_TOO_LARGE_KIND, failed.error().kind());
    assertEquals(0, store.puts.size()); // all-or-nothing: 绝不 put
  }

  @Test
  void invalidUnicodeReturnsInvalidResult() {
    RecordingResourceStore store = new RecordingResourceStore();
    ToolResultFinalizer finalizer = new ToolResultFinalizer(store);

    // 孤立未配对高代理项
    String badUnicode = "bad: \uD800 tail";
    ToolResult result =
        new ToolResult("call-1", List.of(new TextResultContent(badUnicode)), false, "{}");

    ToolResultFinalizer.Outcome outcome = finalizer.finalizeResult(TOOL_NAME, result);
    ToolResultFinalizer.Outcome.Failed failed =
        assertInstanceOf(ToolResultFinalizer.Outcome.Failed.class, outcome);

    assertEquals(ToolResultFinalizer.INVALID_RESULT_KIND, failed.error().kind());
    assertEquals(0, store.puts.size());
  }

  @Test
  void storeFailureReturnsResourceStoreFailed() {
    FailingResourceStore store = new FailingResourceStore();
    ToolResultFinalizer finalizer = new ToolResultFinalizer(store);

    String largeText = "x".repeat(ToolResultFinalizer.INLINE_MAX_UTF8_BYTES + 1);
    ToolResult result =
        new ToolResult("call-1", List.of(new TextResultContent(largeText)), false, "{}");

    ToolResultFinalizer.Outcome outcome = finalizer.finalizeResult(TOOL_NAME, result);
    ToolResultFinalizer.Outcome.StoreFailed storeFailed =
        assertInstanceOf(ToolResultFinalizer.Outcome.StoreFailed.class, outcome);

    assertEquals(ToolResultFinalizer.RESOURCE_STORE_FAILED_KIND, storeFailed.error().kind());
    assertFalse(storeFailed.error().message().contains("disk full"));
  }

  @Test
  void binaryOversizedOrInvalidMediaTypeIsRejected() {
    RecordingResourceStore store = new RecordingResourceStore();
    ToolResultFinalizer finalizer = new ToolResultFinalizer(store, 100);

    // 超过 100 字节的 binary
    ToolResult oversized =
        new ToolResult(
            "call-1", List.of(new BinaryResultContent("image/png", new byte[101])), false, "{}");
    ToolResultFinalizer.Outcome tooLarge = finalizer.finalizeResult(TOOL_NAME, oversized);
    assertEquals(
        ToolResultFinalizer.OUTPUT_TOO_LARGE_KIND,
        assertInstanceOf(ToolResultFinalizer.Outcome.Failed.class, tooLarge).error().kind());

    // 非法 mediaType
    ToolResult invalidType =
        new ToolResult(
            "call-2",
            List.of(new BinaryResultContent("IMAGE/PNG;charset=utf-8", new byte[10])),
            false,
            "{}");
    ToolResultFinalizer.Outcome invalid = finalizer.finalizeResult(TOOL_NAME, invalidType);
    assertEquals(
        ToolResultFinalizer.INVALID_RESULT_KIND,
        assertInstanceOf(ToolResultFinalizer.Outcome.Failed.class, invalid).error().kind());
  }

  @Test
  void spooledResourceResultValidation() {
    RecordingResourceStore store = new RecordingResourceStore();
    ToolResultFinalizer finalizer = new ToolResultFinalizer(store, 1000);

    String text = "line\n".repeat(4) + "x".repeat(80);
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    // 只接受当前 Platform ResourceStore 拥有的引用，并从可信 bytes 重新推导 preview。
    ResourceRef validRef = store.seed("text/plain", "spool.txt", bytes);
    TextArtifactMetadata validMeta = new TextArtifactMetadata(100, 5);
    ResourceResultContent validRes =
        new ResourceResultContent(validRef, "untrusted preview", validMeta);
    ToolResult validResult = new ToolResult("call-1", List.of(validRes), false, "{}");
    ToolResultFinalizer.Outcome success = finalizer.finalizeResult(TOOL_NAME, validResult);
    ResourceResultContent finalized =
        assertInstanceOf(
            ResourceResultContent.class,
            assertInstanceOf(ToolResultFinalizer.Outcome.Success.class, success)
                .result()
                .contents()
                .get(0));
    assertEquals(text, finalized.preview());
    assertEquals(validMeta, finalized.textMetadata());

    ResourceResultContent mismatchRes =
        new ResourceResultContent(validRef, "preview", new TextArtifactMetadata(bytes.length, 4));
    ToolResultFinalizer.Outcome invalid =
        finalizer.finalizeResult(
            TOOL_NAME, new ToolResult("call-2", List.of(mismatchRes), false, "{}"));
    assertEquals(
        ToolResultFinalizer.INVALID_RESULT_KIND,
        assertInstanceOf(ToolResultFinalizer.Outcome.Failed.class, invalid).error().kind());

    // 声明超过硬上限
    TextArtifactMetadata tooLargeMeta = new TextArtifactMetadata(5000, 10);
    ResourceRef tooLargeRef =
        new ResourceRef(
            "file:///path/to/spool.txt", "text/plain", "spool.txt", 5000L, "a".repeat(64));
    ResourceResultContent tooLargeRes =
        new ResourceResultContent(tooLargeRef, "preview", tooLargeMeta);
    ToolResultFinalizer.Outcome tooLarge =
        finalizer.finalizeResult(
            TOOL_NAME, new ToolResult("call-3", List.of(tooLargeRes), false, "{}"));
    assertEquals(
        ToolResultFinalizer.OUTPUT_TOO_LARGE_KIND,
        assertInstanceOf(ToolResultFinalizer.Outcome.Failed.class, tooLarge).error().kind());
  }

  @Test
  void daemonTextBinaryIsPersistedWithRecomputedMetadataAndPreview() {
    RecordingResourceStore store = new RecordingResourceStore();
    ToolResultFinalizer finalizer = new ToolResultFinalizer(store);
    String text = "hello\nworld\n";
    byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
    BinaryResultContent binary =
        new BinaryResultContent("text/plain", bytes, new TextArtifactMetadata(bytes.length, 2));

    ToolResultFinalizer.Outcome.Success success =
        assertInstanceOf(
            ToolResultFinalizer.Outcome.Success.class,
            finalizer.finalizeResult(
                TOOL_NAME, new ToolResult("call-1", List.of(binary), false, "{}")));
    ResourceResultContent resource =
        assertInstanceOf(ResourceResultContent.class, success.result().contents().get(0));

    assertEquals(text, resource.preview());
    assertEquals(new TextArtifactMetadata(bytes.length, 2), resource.textMetadata());
    assertEquals(1, store.puts.size());
  }

  @Test
  void daemonTextBinaryRejectsInvalidUtf8OrLineMetadataBeforeStorage() {
    RecordingResourceStore store = new RecordingResourceStore();
    ToolResultFinalizer finalizer = new ToolResultFinalizer(store);
    BinaryResultContent invalidUtf8 =
        new BinaryResultContent(
            "text/plain", new byte[] {(byte) 0xC3, (byte) 0x28}, new TextArtifactMetadata(2, 1));
    BinaryResultContent wrongLines =
        new BinaryResultContent(
            "text/plain", "a\nb".getBytes(StandardCharsets.UTF_8), new TextArtifactMetadata(3, 1));

    assertEquals(
        ToolResultFinalizer.INVALID_RESULT_KIND,
        assertInstanceOf(
                ToolResultFinalizer.Outcome.Failed.class,
                finalizer.finalizeResult(
                    TOOL_NAME, new ToolResult("call-1", List.of(invalidUtf8), false, "{}")))
            .error()
            .kind());
    assertEquals(
        ToolResultFinalizer.INVALID_RESULT_KIND,
        assertInstanceOf(
                ToolResultFinalizer.Outcome.Failed.class,
                finalizer.finalizeResult(
                    TOOL_NAME, new ToolResult("call-2", List.of(wrongLines), false, "{}")))
            .error()
            .kind());
    assertTrue(store.puts.isEmpty());
  }

  @Test
  void rejectsUnmanagedResourceWithoutEchoingItsUri() {
    RecordingResourceStore store = new RecordingResourceStore();
    ToolResultFinalizer finalizer = new ToolResultFinalizer(store);
    String secretUri = "https://example.com/private-token";
    ResourceRef external = new ResourceRef(secretUri, "image/png", "image.png", 1L, "a".repeat(64));

    ToolResultFinalizer.Outcome.Failed failed =
        assertInstanceOf(
            ToolResultFinalizer.Outcome.Failed.class,
            finalizer.finalizeResult(
                TOOL_NAME,
                new ToolResult(
                    "call-1", List.of(new ResourceResultContent(external)), false, "{}")));

    assertEquals(ToolResultFinalizer.INVALID_RESULT_KIND, failed.error().kind());
    assertFalse(failed.error().message().contains(secretUri));
    assertTrue(store.puts.isEmpty());
  }

  /** Daemon 已直传的 media 引用只做形状/体积校验并原样透传，不得再次读取或写入宿主 ResourceStore。 */
  @Test
  void daemonUploadReferencePassesThroughWithoutResourceStoreIo() {
    RecordingResourceStore store = new RecordingResourceStore();
    ToolResultFinalizer finalizer = new ToolResultFinalizer(store, 100);
    byte[] bytes = new byte[] {1, 2, 3};
    ResourceRef upload =
        new ResourceRef(
            ResourceRef.blobUploadUri(UUID.randomUUID()),
            "image/png",
            "untrusted.png",
            (long) bytes.length,
            sha256Hex(bytes));
    ResourceResultContent source = new ResourceResultContent(upload, "preview");

    ToolResultFinalizer.Outcome.Success success =
        assertInstanceOf(
            ToolResultFinalizer.Outcome.Success.class,
            finalizer.finalizeResult(
                TOOL_NAME, new ToolResult("call-1", List.of(source), false, "{}")));

    assertEquals(source, success.result().contents().getFirst());
    assertEquals(0, store.reads);
    assertTrue(store.puts.isEmpty());
  }

  /** Daemon 直传仅用于 media；文本工件元数据与超出业务上限的声明必须在任何 Store I/O 前拒绝。 */
  @Test
  void daemonUploadReferenceRejectsTextMetadataAndOversize() {
    RecordingResourceStore store = new RecordingResourceStore();
    ToolResultFinalizer finalizer = new ToolResultFinalizer(store, 100);
    byte[] bytes = new byte[] {1, 2, 3};
    ResourceRef upload =
        new ResourceRef(
            ResourceRef.blobUploadUri(UUID.randomUUID()),
            "application/octet-stream",
            null,
            (long) bytes.length,
            sha256Hex(bytes));

    ToolResultFinalizer.Outcome textArtifact =
        finalizer.finalizeResult(
            TOOL_NAME,
            new ToolResult(
                "call-1",
                List.of(
                    new ResourceResultContent(
                        upload, null, new TextArtifactMetadata(bytes.length, 1))),
                false,
                "{}"));
    ToolResultFinalizer.Outcome oversized =
        new ToolResultFinalizer(store, 2)
            .finalizeResult(
                TOOL_NAME,
                new ToolResult("call-2", List.of(new ResourceResultContent(upload)), false, "{}"));

    assertEquals(
        ToolResultFinalizer.INVALID_RESULT_KIND,
        assertInstanceOf(ToolResultFinalizer.Outcome.Failed.class, textArtifact).error().kind());
    assertEquals(
        ToolResultFinalizer.OUTPUT_TOO_LARGE_KIND,
        assertInstanceOf(ToolResultFinalizer.Outcome.Failed.class, oversized).error().kind());
    assertEquals(0, store.reads);
    assertTrue(store.puts.isEmpty());
  }

  private static final class RecordingResourceStore implements ResourceStore {
    record PutRecord(String mediaType, String name, byte[] content) {}

    final List<PutRecord> puts = new ArrayList<>();
    final Map<ResourceRef, byte[]> resources = new HashMap<>();
    int reads;

    @Override
    public ResourceRef reference(String mediaType, String name, long size, String sha256) {
      return new ResourceRef("file:///resources/" + name, mediaType, name, size, sha256);
    }

    @Override
    public ResourceRef put(String mediaType, String name, byte[] content) {
      puts.add(new PutRecord(mediaType, name, content));
      try {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String sha = HexFormat.of().formatHex(md.digest(content));
        ResourceRef ref = reference(mediaType, name, (long) content.length, sha);
        resources.put(ref, content.clone());
        return ref;
      } catch (NoSuchAlgorithmException e) {
        throw new AssertionError(e);
      }
    }

    @Override
    public byte[] read(ResourceRef resource) {
      reads++;
      byte[] content = resources.get(resource);
      if (content == null) {
        throw new IllegalArgumentException("unmanaged resource: " + resource.uri());
      }
      return content.clone();
    }

    ResourceRef seed(String mediaType, String name, byte[] content) {
      ResourceRef ref = reference(mediaType, name, (long) content.length, sha256Hex(content));
      resources.put(ref, content.clone());
      return ref;
    }
  }

  private static final class FailingResourceStore implements ResourceStore {
    @Override
    public ResourceRef reference(String mediaType, String name, long size, String sha256) {
      return new ResourceRef("file:///resources/" + name, mediaType, name, size, sha256);
    }

    @Override
    public ResourceRef put(String mediaType, String name, byte[] content) {
      throw new IllegalStateException("disk full or S3 network failure");
    }

    @Override
    public byte[] read(ResourceRef resource) {
      throw new IllegalStateException("sensitive local path");
    }
  }

  private static String sha256Hex(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException error) {
      throw new AssertionError(error);
    }
  }
}
