package fun.fengwk.kkstudio.canvas.function;

public enum CanvasResourceKind {
  IMAGE,
  VIDEO,
  AUDIO,
  TEXT;

  /**
   * 根据 MIME mediaType 解析资源类型。
   *
   * @param mediaType blob MIME 媒体类型
   * @return 对应的 CanvasResourceKind
   * @throws IllegalArgumentException 当 mediaType 为 null 或类型不受支持时
   */
  public static CanvasResourceKind fromMediaType(String mediaType) {
    if (mediaType == null) {
      throw new IllegalArgumentException("blob mediaType must not be null");
    }
    if (mediaType.startsWith("image/")) {
      return IMAGE;
    }
    if (mediaType.startsWith("video/")) {
      return VIDEO;
    }
    if (mediaType.startsWith("audio/")) {
      return AUDIO;
    }
    if (mediaType.startsWith("text/")) {
      return TEXT;
    }
    throw new IllegalArgumentException("unsupported blob mediaType: " + mediaType);
  }
}
