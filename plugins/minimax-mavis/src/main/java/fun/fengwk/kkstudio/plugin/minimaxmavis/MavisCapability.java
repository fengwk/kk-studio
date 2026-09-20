package fun.fengwk.kkstudio.plugin.minimaxmavis;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * MiniMax Mavis 的 15 项模型能力与网关 MCP endpoint 的稳定映射。
 *
 * <p>枚举顺序就是模型工具列表顺序；{@link #toolName()} 是模型可见的稳定工具名（{@code mavis_} 前缀 + 本枚举 id），schema 资源按 {@link
 * #id()} 命名，因此一个能力的工具名、请求 schema 资源与线上 endpoint 只有一处事实源。
 *
 * <p>{@link #requestTimeout()} 取自网关的观测超时：生成类与语音合成 600 秒、多模态理解 300 秒、其余 120 秒。
 */
public enum MavisCapability {
  WEB_SEARCH("web-search", "web_search", 120),
  EXTRACT_WEB("extract-web", "extract_content_from_websites", 120),
  IMAGE_SEARCH("image-search", "images_search_and_download", 120),
  REVERSE_IMAGE("reverse-image", "image_reverse_search", 120),
  UNDERSTAND_IMAGE("understand-image", "images_understand", 300),
  UNDERSTAND_AUDIO("understand-audio", "audios_understand", 300),
  UNDERSTAND_VIDEO("understand-video", "videos_understand", 300),
  ASR("asr", "listen_audio", 120),
  LIST_VOICES("list-voices", "get_voice_list", 120),
  TTS("tts", "synthesize_speech", 600),
  TTS_BATCH("tts-batch", "batch_text_to_audio", 600),
  GENERATE_IMAGE("generate-image", "image_synthesize", 600),
  GENERATE_MUSIC("generate-music", "batch_text_to_music", 600),
  SUBMIT_VIDEO("submit-video", "submit_video_generation", 600),
  QUERY_VIDEO("query-video", "query_video_generation", 120);

  private static final String TOOL_NAME_PREFIX = "mavis_";

  private final String id;
  private final String endpoint;
  private final Duration requestTimeout;

  MavisCapability(String id, String endpoint, int timeoutSeconds) {
    this.id = id;
    this.endpoint = endpoint;
    this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
  }

  /** kebab-case 能力标识，同时决定工具 schema 资源名。 */
  public String id() {
    return id;
  }

  /** 模型可见的稳定工具名。 */
  public String toolName() {
    return TOOL_NAME_PREFIX + id.replace('-', '_');
  }

  /** MCP endpoint 名，即 {@code /mavis/api/v1/mcp/} 之后的路径段。 */
  public String endpoint() {
    return endpoint;
  }

  /** 单次请求超时。 */
  public Duration requestTimeout() {
    return requestTimeout;
  }

  /** 按工具名查找能力；未知名返回空。 */
  public static Optional<MavisCapability> fromToolName(String toolName) {
    if (toolName == null || toolName.isBlank()) {
      return Optional.empty();
    }
    String normalized = toolName.trim().toLowerCase(Locale.ROOT);
    for (MavisCapability capability : values()) {
      if (capability.toolName().equals(normalized)) {
        return Optional.of(capability);
      }
    }
    return Optional.empty();
  }
}
