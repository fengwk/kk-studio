package fun.fengwk.kkstudio.harness.runtime.model.provider;

/** 视频内容；source 保存不经 Provider SDK 解释的 URI 或内联编码。 */
public record ProviderVideoBlock(String mediaType, String source) implements ProviderContentBlock {

  public ProviderVideoBlock {
    if (mediaType == null || !mediaType.startsWith("video/")) {
      throw new IllegalArgumentException("mediaType must be a video MIME type");
    }
    if (source == null || source.isBlank()) {
      throw new IllegalArgumentException("source must not be blank");
    }
  }
}
