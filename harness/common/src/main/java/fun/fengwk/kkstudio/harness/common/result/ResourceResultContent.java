package fun.fengwk.kkstudio.harness.common.result;

import fun.fengwk.kkstudio.harness.common.resource.ResourceRef;

import java.util.Objects;

/**
 * 指向规范 Resource URI 的结果内容单元；可选 preview 让调用方在不读取资源正文的情况下展示有界文本， 可选 textMetadata 提供文本工件确切的 UTF-8
 * 字节数与物理行数。
 *
 * <p>引用校验在 {@link ResourceRef} 构造时完成；preview 使用严格 Unicode / UTF-8 字节上限。
 */
public record ResourceResultContent(
    ResourceRef resource, String preview, TextArtifactMetadata textMetadata)
    implements ResultContent {

  /** 构造无 preview 和 textMetadata 的 Resource 内容。 */
  public ResourceResultContent(ResourceRef resource) {
    this(resource, null, null);
  }

  /** 构造带 preview 但无 textMetadata 的 Resource 内容。 */
  public ResourceResultContent(ResourceRef resource, String preview) {
    this(resource, preview, null);
  }

  public ResourceResultContent {
    resource = Objects.requireNonNull(resource, "resource");
    if (preview != null
        && ResourceRef.utf8Length(preview, "preview") > ResourceRef.MAX_PREVIEW_UTF8_BYTES) {
      throw new IllegalArgumentException(
          "preview must not exceed " + ResourceRef.MAX_PREVIEW_UTF8_BYTES + " UTF-8 bytes");
    }
  }
}
