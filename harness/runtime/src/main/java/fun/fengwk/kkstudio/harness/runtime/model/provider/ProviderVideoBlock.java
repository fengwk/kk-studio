package fun.fengwk.kkstudio.harness.runtime.model.provider;

/** 视频内容；source 保存 URI（包括合法 data URI），由 Provider adapter 按 URI 解释。 */
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
