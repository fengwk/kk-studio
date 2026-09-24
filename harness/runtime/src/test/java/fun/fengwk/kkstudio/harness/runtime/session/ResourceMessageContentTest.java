package fun.fengwk.kkstudio.harness.runtime.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;
import fun.fengwk.kkstudio.harness.runtime.model.ImageInputTier;

import java.util.UUID;

/**
 * ResourceMessageContent：durable blob 引用（blobId/name/totalBytes/totalLines/preview/imageTier）。
 * 普通媒体的 totals 为 null；外部化文本的 totals 必须成对出现且非负，且绝不携带图片输入档位。
 */
class ResourceMessageContentTest {

  private static final UUID BLOB_ID = new UUID(0L, 1L);

  @Test
  void mediaFactoryCreatesValidMediaResource() {
    ResourceMessageContent content = ResourceMessageContent.media(BLOB_ID, "image.png");
    assertEquals(BLOB_ID, content.blobId());
    assertEquals("image.png", content.name());
    assertNull(content.totalBytes());
    assertNull(content.totalLines());
    assertNull(content.preview());
    assertFalse(content.isExternalizedText());
  }

  @Test
  void externalizedTextFactoryCreatesValidResource() {
    ResourceMessageContent content =
        ResourceMessageContent.externalizedText(BLOB_ID, "result.txt", 1024L, 50L, "preview text");
    assertEquals(BLOB_ID, content.blobId());
    assertEquals("result.txt", content.name());
    assertEquals(1024L, content.totalBytes());
    assertEquals(50L, content.totalLines());
    assertEquals("preview text", content.preview());
    assertTrue(content.isExternalizedText());
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

  /** 意图：图片输入档位属于普通媒体；文本工件携带档位是无意义事实，必须显式拒绝。 */
  @Test
  void imageTierIsCarriedOnlyByPlainMediaResources() {
    assertEquals(
        ImageInputTier.P1080,
        ResourceMessageContent.media(BLOB_ID, "photo.png", "preview", ImageInputTier.P1080)
            .imageTier());
    assertEquals(
        ImageInputTier.ORIGINAL,
        ResourceMessageContent.media(BLOB_ID, "photo.png", null, ImageInputTier.ORIGINAL)
            .imageTier());
    // 未显式选择档位：durable 事实为 null，平台默认（720P）由物化边界决定。
    assertNull(ResourceMessageContent.media(BLOB_ID, "photo.png").imageTier());
    assertNull(ResourceMessageContent.media(BLOB_ID, "photo.png", "preview").imageTier());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResourceMessageContent(
                BLOB_ID, "result.txt", 100L, 10L, "preview", ImageInputTier.P720));
    assertNull(
        ResourceMessageContent.externalizedText(BLOB_ID, "result.txt", 100L, 10L, "preview")
            .imageTier());
  }

  @Test
  void rejectsPartialOrInvalidExternalizedTextTotals() {
    // 外部化文本 totals 必须成对出现。
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceMessageContent(BLOB_ID, "a.txt", 100L, null, "x", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceMessageContent(BLOB_ID, "a.txt", null, 10L, "x", null));

    // totals 不允许负数。
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceMessageContent(BLOB_ID, "a.txt", -1L, 10L, "x", null));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceMessageContent(BLOB_ID, "a.txt", 100L, -1L, "x", null));
  }
}
