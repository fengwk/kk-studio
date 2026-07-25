package fun.fengwk.kkstudio.harness.model.provider;

/** 图片内容；source 保存不经 Provider SDK 解释的 URI 或内联编码。 */
public record ProviderImageBlock(String mediaType, String source) implements ProviderContentBlock {

  public ProviderImageBlock {
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
