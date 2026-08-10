package fun.fengwk.kkstudio.harness.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** ResourceToolContent 约束引用非空，并共享 Resource preview 的严格 Unicode / UTF-8 边界。 */
class ResourceToolContentTest {

  @Test
  void rejectsNullResource() {
    assertThrows(NullPointerException.class, () -> new ResourceToolContent(null));
  }

  @Test
  void keepsTheExactResourceRef() {
    ResourceRef ref = new ResourceRef("https://example.com/a", "text/plain", "a", null, null);
    ResourceToolContent content = new ResourceToolContent(ref);
    assertSame(ref, content.resource());
    assertNull(content.preview());
  }

  /** preview 恰好 16 KiB 可接受，并保留多字节 Unicode 原文。 */
  @Test
  void acceptsPreviewAtExactUtf8Limit() {
    ResourceRef ref = new ResourceRef("https://example.com/a", "text/plain", "a", null, null);
    String preview = "中".repeat(ResourceRef.MAX_PREVIEW_UTF8_BYTES / 3) + "x";

    ResourceToolContent content = new ResourceToolContent(ref, preview);

    assertEquals(ResourceRef.MAX_PREVIEW_UTF8_BYTES, ResourceRef.utf8Length(preview, "preview"));
    assertEquals(preview, content.preview());
  }

  /** preview 超过 16 KiB 或包含未配对 surrogate 时在 Tool 边界直接拒绝。 */
  @Test
  void rejectsOversizedOrInvalidUnicodePreview() {
    ResourceRef ref = new ResourceRef("https://example.com/a", "text/plain", "a", null, null);
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceToolContent(ref, "x".repeat(ResourceRef.MAX_PREVIEW_UTF8_BYTES + 1)));
    assertThrows(IllegalArgumentException.class, () -> new ResourceToolContent(ref, "valid\uD800"));
  }
}
