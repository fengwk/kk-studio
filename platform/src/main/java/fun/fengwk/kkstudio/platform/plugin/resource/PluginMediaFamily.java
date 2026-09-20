package fun.fengwk.kkstudio.platform.plugin.resource;

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
  DOCUMENT
}
