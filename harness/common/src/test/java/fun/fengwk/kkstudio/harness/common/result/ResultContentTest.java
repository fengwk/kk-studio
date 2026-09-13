package fun.fengwk.kkstudio.harness.common.result;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;

/** 结果内容类型（Text, Json, Resource, Binary）的不变量、安全边界与防御性复制测试。 */
class ResultContentTest {

  /** 验证纯文本结果内容拒绝 null，并保留包含空字符串在内的文本。 */
  @Test
  void textResultContentValidatesNotNull() {
    TextResultContent content = new TextResultContent("ok");
    assertEquals("ok", content.text());
    assertEquals("", new TextResultContent("").text());
    assertThrows(IllegalArgumentException.class, () -> new TextResultContent(null));
  }

  /** 恰好 1 MiB UTF-8 的合法 JSON 接受；超一字节拒绝；未配对代理项拒绝（全部发生在树解析之前）。 */
  @Test
  void boundsRawJsonBeforeParsing() {
    String atLimit = "\"" + "a".repeat(JsonResultContent.MAX_JSON_UTF8_BYTES - 2) + "\"";
    assertEquals(atLimit, new JsonResultContent(atLimit).json());

    String overLimit = "\"" + "a".repeat(JsonResultContent.MAX_JSON_UTF8_BYTES - 1) + "\"";
    assertThrows(IllegalArgumentException.class, () -> new JsonResultContent(overLimit));

    // 多字节字符：按 UTF-8 字节计数（3 字节/字符），而非字符数。
    int cjkChars = (JsonResultContent.MAX_JSON_UTF8_BYTES - 2) / 3;
    String cjkAtLimit = "\"" + "中".repeat(cjkChars) + "\"";
    assertEquals(cjkAtLimit, new JsonResultContent(cjkAtLimit).json());
    String cjkOver = "\"" + "中".repeat(cjkChars + 1) + "\"";
    assertThrows(IllegalArgumentException.class, () -> new JsonResultContent(cjkOver));

    // 未配对代理项与非法 JSON 仍严格拒绝。
    assertThrows(IllegalArgumentException.class, () -> new JsonResultContent("\"\uD800\""));
    assertThrows(IllegalArgumentException.class, () -> new JsonResultContent("not json"));
  }

  /** 验证 ResourceResultContent 拒绝 null 资源引用，无 preview 构造时默认为 null。 */
  @Test
  void resourceResultContentRejectsNullResource() {
    assertThrows(NullPointerException.class, () -> new ResourceResultContent(null));
    ResourceRef ref = new ResourceRef("https://example.com/a", "text/plain", "a", null, null);
    ResourceResultContent content = new ResourceResultContent(ref);
    assertSame(ref, content.resource());
    assertNull(content.preview());
  }

  /** 验证 preview 恰好 16 KiB 可接受，并保留多字节 Unicode 原文。 */
  @Test
  void acceptsPreviewAtExactUtf8Limit() {
    ResourceRef ref = new ResourceRef("https://example.com/a", "text/plain", "a", null, null);
    String preview = "中".repeat(ResourceRef.MAX_PREVIEW_UTF8_BYTES / 3) + "x";

    ResourceResultContent content = new ResourceResultContent(ref, preview);

    assertEquals(ResourceRef.MAX_PREVIEW_UTF8_BYTES, ResourceRef.utf8Length(preview, "preview"));
    assertEquals(preview, content.preview());
  }

  /** 验证 preview 超过 16 KiB 或包含未配对 surrogate 时直接拒绝。 */
  @Test
  void rejectsOversizedOrInvalidUnicodePreview() {
    ResourceRef ref = new ResourceRef("https://example.com/a", "text/plain", "a", null, null);
    assertThrows(
        IllegalArgumentException.class,
        () -> new ResourceResultContent(ref, "x".repeat(ResourceRef.MAX_PREVIEW_UTF8_BYTES + 1)));
    assertThrows(
        IllegalArgumentException.class, () -> new ResourceResultContent(ref, "valid\uD800"));
  }

  /** 验证内联二进制内容的防御性复制与尺寸统计。 */
  @Test
  void binaryResultContentDefensiveCopiesAndValidatesMediaType() {
    byte[] original = new byte[] {1, 2, 3};
    BinaryResultContent binary = new BinaryResultContent("application/octet-stream", original);
    assertEquals(3, binary.size());
    assertArrayEquals(new byte[] {1, 2, 3}, binary.content());
    assertNull(binary.textMetadata());

    // 防御性复制：修改外部原始数组不影响内部。
    original[0] = 99;
    assertArrayEquals(new byte[] {1, 2, 3}, binary.content());

    // 防御性复制：修改 getter 返回的数组不影响内部。
    byte[] fromGetter = binary.content();
    fromGetter[0] = 88;
    assertArrayEquals(new byte[] {1, 2, 3}, binary.content());
    assertNotSame(fromGetter, binary.content());

    // 拒绝空白 mediaType 与 null 字节数组。
    assertThrows(IllegalArgumentException.class, () -> new BinaryResultContent("", new byte[] {1}));
    assertThrows(
        IllegalArgumentException.class, () -> new BinaryResultContent("  ", new byte[] {1}));
    assertThrows(
        IllegalArgumentException.class, () -> new BinaryResultContent(null, new byte[] {1}));
    assertThrows(NullPointerException.class, () -> new BinaryResultContent("text/plain", null));

    TextArtifactMetadata metadata = new TextArtifactMetadata(3, 1);
    BinaryResultContent textBinary =
        new BinaryResultContent("text/plain", new byte[] {1, 2, 3}, metadata);
    assertSame(metadata, textBinary.textMetadata());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BinaryResultContent(
                "text/plain", new byte[] {1, 2, 3}, new TextArtifactMetadata(2, 1)));
  }

  /** 验证 TextArtifactMetadata 校验非负字节数与行数。 */
  @Test
  void textArtifactMetadataValidatesNonNegative() {
    TextArtifactMetadata meta = new TextArtifactMetadata(100, 10);
    assertEquals(100, meta.totalBytes());
    assertEquals(10, meta.totalLines());
    assertThrows(IllegalArgumentException.class, () -> new TextArtifactMetadata(-1, 0));
    assertThrows(IllegalArgumentException.class, () -> new TextArtifactMetadata(0, -1));
  }

  /** 验证 ResourceResultContent 兼容构造器与 textMetadata 字段保持。 */
  @Test
  void resourceResultContentCompatibilityAndTextMetadata() {
    ResourceRef ref = new ResourceRef("https://example.com/a", "text/plain", "a", null, null);
    ResourceResultContent noPreview = new ResourceResultContent(ref);
    assertNull(noPreview.preview());
    assertNull(noPreview.textMetadata());

    ResourceResultContent withPreview = new ResourceResultContent(ref, "preview");
    assertEquals("preview", withPreview.preview());
    assertNull(withPreview.textMetadata());

    TextArtifactMetadata meta = new TextArtifactMetadata(1024, 25);
    ResourceResultContent withMeta = new ResourceResultContent(ref, "preview", meta);
    assertEquals("preview", withMeta.preview());
    assertSame(meta, withMeta.textMetadata());
  }
}
