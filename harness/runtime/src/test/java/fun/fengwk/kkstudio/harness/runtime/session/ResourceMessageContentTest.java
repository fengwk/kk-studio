package fun.fengwk.kkstudio.harness.runtime.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;

import java.util.UUID;

/** ResourceMessageContent：durable blob 引用（blobId/name/preview）；preview 可空且仅受 UTF-8 16384 字节上限约束。 */
class ResourceMessageContentTest {

  private static final UUID BLOB_ID = new UUID(0L, 1L);

  @Test
  void allowsNullPreviewAndExactByteLimit() {
    assertDoesNotThrow(() -> new ResourceMessageContent(BLOB_ID, "a.txt", null));
    assertDoesNotThrow(
        () ->
            new ResourceMessageContent(
                BLOB_ID, "a.txt", "a".repeat(ResourceRef.MAX_PREVIEW_UTF8_BYTES)));
    // 多字节字符按 UTF-8 字节计：5461 个"好"恰好 16383 字节。
    assertDoesNotThrow(
        () ->
            new ResourceMessageContent(
                BLOB_ID, "a.txt", "好".repeat(ResourceRef.MAX_PREVIEW_UTF8_BYTES / 3)));
  }

  @Test
  void rejectsOversizedPreviewByUtf8Bytes() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResourceMessageContent(
                BLOB_ID, "a.txt", "a".repeat(ResourceRef.MAX_PREVIEW_UTF8_BYTES + 1)));
    // 5462 个"好"为 16386 字节，超过 16384 上限。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResourceMessageContent(
                BLOB_ID, "a.txt", "好".repeat(ResourceRef.MAX_PREVIEW_UTF8_BYTES / 3 + 1)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceMessageContent(BLOB_ID, "a.txt", "a\uD800b"));
  }

  @Test
  void rejectsNullBlobIdAndBlankOrNonAsciiName() {
    assertThrows(NullPointerException.class, () -> new ResourceMessageContent(null, "a.txt", "x"));
    assertThrows(
        IllegalArgumentException.class, () -> new ResourceMessageContent(BLOB_ID, "", "x"));
    assertThrows(
        IllegalArgumentException.class, () -> new ResourceMessageContent(BLOB_ID, " ", "x"));
    assertThrows(
        IllegalArgumentException.class, () -> new ResourceMessageContent(BLOB_ID, "a\u0001b", "x"));
  }

  @Test
  void keepsPreviewUntouched() {
    ResourceMessageContent content = new ResourceMessageContent(BLOB_ID, "a.txt", "preview");
    assertEquals(BLOB_ID, content.blobId());
    assertEquals("a.txt", content.name());
    assertEquals("preview", content.preview());
  }
}
