package fun.fengwk.kkstudio.harness.runtime.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;

import java.util.UUID;

/**
 * ResourceMessageContent：durable blob 引用（blobId/name/artifactPath/totalBytes/totalLines/preview）。
 * 普通媒体三项 artifact 字段为 null；文本工件三项必须全部非 null 且满足规范路径与非负计数。
 */
class ResourceMessageContentTest {

  private static final UUID BLOB_ID = new UUID(0L, 1L);
  private static final String CANONICAL_PATH =
      "/.artifacts/tool-results/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222.txt";

  @Test
  void mediaFactoryCreatesValidMediaResource() {
    ResourceMessageContent content = ResourceMessageContent.media(BLOB_ID, "image.png");
    assertEquals(BLOB_ID, content.blobId());
    assertEquals("image.png", content.name());
    assertNull(content.artifactPath());
    assertNull(content.totalBytes());
    assertNull(content.totalLines());
    assertNull(content.preview());
    assertFalse(content.isTextArtifact());
  }

  @Test
  void artifactFactoryCreatesValidTextArtifactResource() {
    ResourceMessageContent content =
        ResourceMessageContent.artifact(
            BLOB_ID, "result.txt", CANONICAL_PATH, 1024L, 50L, "preview text");
    assertEquals(BLOB_ID, content.blobId());
    assertEquals("result.txt", content.name());
    assertEquals(CANONICAL_PATH, content.artifactPath());
    assertEquals(1024L, content.totalBytes());
    assertEquals(50L, content.totalLines());
    assertEquals("preview text", content.preview());
    assertTrue(content.isTextArtifact());
  }

  @Test
  void allowsNullPreviewAndExactByteLimit() {
    assertDoesNotThrow(() -> ResourceMessageContent.media(BLOB_ID, "a.txt", null));
    assertDoesNotThrow(
        () ->
            ResourceMessageContent.media(
                BLOB_ID, "a.txt", "a".repeat(ResourceRef.MAX_PREVIEW_UTF8_BYTES)));
    // 多字节字符按 UTF-8 字节计：5461 个"好"恰好 16383 字节。
    assertDoesNotThrow(
        () ->
            ResourceMessageContent.media(
                BLOB_ID, "a.txt", "好".repeat(ResourceRef.MAX_PREVIEW_UTF8_BYTES / 3)));
  }

  @Test
  void rejectsOversizedPreviewByUtf8Bytes() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ResourceMessageContent.media(
                BLOB_ID, "a.txt", "a".repeat(ResourceRef.MAX_PREVIEW_UTF8_BYTES + 1)));
    // 5462 个"好"为 16386 字节，超过 16384 上限。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ResourceMessageContent.media(
                BLOB_ID, "a.txt", "好".repeat(ResourceRef.MAX_PREVIEW_UTF8_BYTES / 3 + 1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> ResourceMessageContent.media(BLOB_ID, "a.txt", "a\uD800b"));
  }

  @Test
  void rejectsNullBlobIdAndBlankOrNonAsciiName() {
    assertThrows(
        NullPointerException.class, () -> ResourceMessageContent.media(null, "a.txt", "x"));
    assertThrows(
        IllegalArgumentException.class, () -> ResourceMessageContent.media(BLOB_ID, "", "x"));
    assertThrows(
        IllegalArgumentException.class, () -> ResourceMessageContent.media(BLOB_ID, " ", "x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ResourceMessageContent.media(BLOB_ID, "a\u0001b", "x"));
  }

  @Test
  void rejectsPartialOrInvalidArtifactFields() {
    // 缺失部分工件字段
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceMessageContent(BLOB_ID, "a.txt", CANONICAL_PATH, 100L, null, "x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceMessageContent(BLOB_ID, "a.txt", CANONICAL_PATH, null, 10L, "x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceMessageContent(BLOB_ID, "a.txt", null, 100L, 10L, "x"));

    // 负数字节或行数
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceMessageContent(BLOB_ID, "a.txt", CANONICAL_PATH, -1L, 10L, "x"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceMessageContent(BLOB_ID, "a.txt", CANONICAL_PATH, 100L, -1L, "x"));

    // 非法工件路径
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResourceMessageContent(BLOB_ID, "a.txt", "/wrong/path/result.txt", 100L, 10L, "x"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResourceMessageContent(
                BLOB_ID,
                "a.txt",
                "/.artifacts/tool-results/not-uuid/22222222-2222-2222-2222-222222222222.txt",
                100L,
                10L,
                "x"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResourceMessageContent(
                BLOB_ID,
                "a.txt",
                "/.artifacts/tool-results/11111111-1111-1111-1111-111111111111/22222222-2222-2222-2222-222222222222.bin",
                100L,
                10L,
                "x"));
  }
}
