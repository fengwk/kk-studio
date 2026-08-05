package fun.fengwk.kkstudio.harness.runtime.session;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.tool.ResourceRef;

/** ResourceMessageContent：preview 可空且仅受 UTF-8 16384 字节上限约束。 */
class ResourceMessageContentTest {

  private static final ResourceRef RESOURCE =
      new ResourceRef(
          "https://example.com/a.txt",
          "text/plain",
          "a.txt",
          3L,
          "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");

  @Test
  void allowsNullPreviewAndExactByteLimit() {
    assertDoesNotThrow(() -> new ResourceMessageContent(RESOURCE, null));
    assertDoesNotThrow(
        () -> new ResourceMessageContent(RESOURCE, "a".repeat(ResourceRef.MAX_PREVIEW_UTF8_BYTES)));
    // 多字节字符按 UTF-8 字节计：5461 个"好"恰好 16383 字节。
    assertDoesNotThrow(
        () ->
            new ResourceMessageContent(
                RESOURCE, "好".repeat(ResourceRef.MAX_PREVIEW_UTF8_BYTES / 3)));
  }

  @Test
  void rejectsOversizedPreviewByUtf8Bytes() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResourceMessageContent(
                RESOURCE, "a".repeat(ResourceRef.MAX_PREVIEW_UTF8_BYTES + 1)));
    // 5462 个"好"为 16386 字节，超过 16384 上限。
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ResourceMessageContent(
                RESOURCE, "好".repeat(ResourceRef.MAX_PREVIEW_UTF8_BYTES / 3 + 1)));
    assertThrows(
        IllegalArgumentException.class, () -> new ResourceMessageContent(RESOURCE, "a\uD800b"));
  }

  @Test
  void rejectsNullResourceAndKeepsPreviewUntouched() {
    assertThrows(NullPointerException.class, () -> new ResourceMessageContent(null, "x"));
    ResourceMessageContent content = new ResourceMessageContent(RESOURCE, "preview");
    assertEquals(RESOURCE, content.resource());
    assertEquals("preview", content.preview());
  }
}
