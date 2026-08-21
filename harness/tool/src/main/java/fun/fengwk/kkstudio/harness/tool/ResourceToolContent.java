package fun.fengwk.kkstudio.harness.tool;

import java.util.Objects;

/**
 * 指向规范 Resource URI 的内容单元；可选 preview 让调用方在不读取资源正文的情况下展示有界文本。
 *
 * <p>引用校验在 {@link ResourceRef} 构造时完成；preview 使用与 Session Resource 内容相同的严格 Unicode / UTF-8 字节上限。
 */
public record ResourceToolContent(ResourceRef resource, String preview) implements ToolContent {

  /** 构造无 preview 的 Resource 内容。 */
  public ResourceToolContent(ResourceRef resource) {
    this(resource, null);
  }

  public ResourceToolContent {
    resource = Objects.requireNonNull(resource, "resource");
    if (preview != null
        && ResourceRef.utf8Length(preview, "preview") > ResourceRef.MAX_PREVIEW_UTF8_BYTES) {
      throw new IllegalArgumentException(
          "preview must not exceed " + ResourceRef.MAX_PREVIEW_UTF8_BYTES + " UTF-8 bytes");
    }
  }
}
