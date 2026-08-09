package fun.fengwk.kkstudio.harness.runtime.session;

/** 视频消息内容，source 是 URI 或内联编码。 */
public record VideoMessageContent(String mediaType, String source) implements AgentMessageContent {
  public VideoMessageContent {
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
