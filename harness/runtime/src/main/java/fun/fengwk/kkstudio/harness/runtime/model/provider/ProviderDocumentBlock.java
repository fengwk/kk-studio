package fun.fengwk.kkstudio.harness.runtime.model.provider;

/** 文档内容；source 保存 URI（包括合法 data URI），由 Provider adapter 按 URI 解释。 */
public record ProviderDocumentBlock(String mediaType, String source)
    implements ProviderContentBlock {

  public ProviderDocumentBlock {
    mediaType = requireNonBlank(mediaType, "mediaType");
    source = requireNonBlank(source, "source");
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }
}
