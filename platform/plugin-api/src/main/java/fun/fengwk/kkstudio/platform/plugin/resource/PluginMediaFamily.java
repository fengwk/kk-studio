package fun.fengwk.kkstudio.platform.plugin.resource;

import java.util.Locale;

/**
 * Plugin 声明要暂存的媒体族。
 *
 * <p>调用方按自己的协议知道媒体是哪一类（图片、音频、视频、文档），但不知道也不该猜测精确的 MIME subtype：精确类型必须由实现嗅探并复核，因此窄接口只接受族而不是 伪造的
 * {@code image/jpeg} 之类的声明。
 */
public enum PluginMediaFamily {

  /** 图片：{@code image/*}。 */
  IMAGE,

  /** 音频：{@code audio/*}。 */
  AUDIO,

  /** 视频：{@code video/*}。 */
  VIDEO,

  /** 文档或其他非媒体字节流。 */
  DOCUMENT;

  /**
   * 嗅探出的权威 media type 是否属于本族。
   *
   * <p>族只在调用方无法知道精确 subtype 时代替「声明一个具体类型」；判定始终由实现用嗅探结果执行，因此声明本身不是信任来源。
   */
  public boolean accepts(String mediaType) {
    if (mediaType == null || mediaType.isBlank()) {
      return false;
    }
    String normalized = mediaType.toLowerCase(Locale.ROOT);
    return switch (this) {
      case IMAGE -> normalized.startsWith("image/");
      case AUDIO -> normalized.startsWith("audio/");
      case VIDEO -> normalized.startsWith("video/");
      case DOCUMENT -> !normalized.isEmpty() && normalized.indexOf('/') > 0;
    };
  }
}
