package fun.fengwk.kkstudio.harness.model.limits;

import fun.fengwk.kkstudio.harness.model.provider.ProviderType;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * 代码内 {@link RequestLimits} 预设注册表（PLAN 11.1）。
 *
 * <p>按 Provider 协议与官方域名匹配：只有 {@code ANTHROPIC} 直连 {@code api.anthropic.com}、{@code OPENAI} / {@code
 * OPENAI_RESPONSES} 官方 {@code api.openai.com} 与 {@code GOOGLE} inline {@code
 * generativelanguage.googleapis.com} 命中预设；代理、私有网关等其余端点全部未知。ProviderType 只决定协议，Provider
 * 与模型配置不携带限制字段。预设值注明口径与依据，上线前按实际端点复核。
 */
public final class RequestLimitsPresets {

  /** Anthropic 直连：最终请求体 BODY 上限 128,000,000 bytes。 */
  private static final long CLAUDE_BODY_MAX_BYTES = 128_000_000L;

  /** Anthropic 直连：单图 BASE64 编码后 10,000,000 bytes。 */
  private static final long CLAUDE_IMAGE_SINGLE_ENCODED_BYTES = 10_000_000L;

  /** Anthropic 直连：200k 窗口模型单请求 100 张（更大窗口的模型 600 张）。请求不携带窗口大小，统一取更严的 100 张。 */
  private static final int CLAUDE_IMAGE_MAX_COUNT = 100;

  /** Anthropic 直连：图片边长 ≤ 8000 px。 */
  private static final int CLAUDE_IMAGE_MAX_SIDE = 8000;

  /** Anthropic 直连：一个请求超过 20 张图片时每张边长 ≤ 3000 px。 */
  private static final int CLAUDE_IMAGE_MANY_THRESHOLD = 20;

  /** Anthropic 直连：超过 20 张后的每张边长上限。 */
  private static final int CLAUDE_IMAGE_MANY_MAX_SIDE = 3000;

  /** OpenAI 官方：payload 512,000,000 bytes（仅图片载荷，不推广为通用 body 限制）。 */
  private static final long OPENAI_IMAGE_TOTAL_BYTES = 512_000_000L;

  /** OpenAI 官方：单请求 1,500 张图片。 */
  private static final int OPENAI_IMAGE_MAX_COUNT = 1500;

  /** OpenAI 官方：单个文件 < 50,000,000 bytes，「小于 X」归一为 X − 1。 */
  private static final long OPENAI_FILE_SINGLE_RAW_BYTES = 50_000_000L - 1;

  /** OpenAI 官方：单请求全部文件合计 ≤ 50,000,000 RAW bytes。 */
  private static final long OPENAI_FILE_TOTAL_RAW_BYTES = 50_000_000L;

  /** Gemini inline：20,000,000-byte body，官方为保守值。 */
  private static final long GEMINI_BODY_MAX_BYTES = 20_000_000L;

  private static final RequestLimits CLAUDE_DIRECT =
      new RequestLimits(
          CLAUDE_BODY_MAX_BYTES,
          new ImageRequestLimits(
              null,
              CLAUDE_IMAGE_SINGLE_ENCODED_BYTES,
              null,
              CLAUDE_IMAGE_MAX_COUNT,
              CLAUDE_IMAGE_MAX_SIDE,
              CLAUDE_IMAGE_MANY_THRESHOLD,
              CLAUDE_IMAGE_MANY_MAX_SIDE),
          DocumentRequestLimits.UNKNOWN,
          AudioRequestLimits.UNKNOWN,
          VideoRequestLimits.UNKNOWN,
          null,
          "https://platform.claude.com/docs/en/api/overview#request-size-limits,"
              + " https://platform.claude.com/docs/en/build-with-claude/vision",
          "2026-11-10");

  private static final RequestLimits OPENAI_OFFICIAL =
      new RequestLimits(
          // Images and vision 的 512 MB 只约束图片载荷，不推广为通用 body 限制
          null,
          new ImageRequestLimits(
              null, null, OPENAI_IMAGE_TOTAL_BYTES, OPENAI_IMAGE_MAX_COUNT, null, null, null),
          new DocumentRequestLimits(
              OPENAI_FILE_SINGLE_RAW_BYTES, OPENAI_FILE_TOTAL_RAW_BYTES, null),
          AudioRequestLimits.UNKNOWN,
          VideoRequestLimits.UNKNOWN,
          null,
          "https://developers.openai.com/api/docs/guides/images-vision,"
              + " https://developers.openai.com/api/docs/guides/file-inputs",
          "2026-11-10");

  private static final RequestLimits GEMINI_INLINE =
      new RequestLimits(
          GEMINI_BODY_MAX_BYTES,
          ImageRequestLimits.UNKNOWN,
          DocumentRequestLimits.UNKNOWN,
          AudioRequestLimits.UNKNOWN,
          VideoRequestLimits.UNKNOWN,
          null,
          "https://ai.google.dev/gemini-api/docs/image-understanding,"
              + " https://ai.google.dev/gemini-api/docs/file-input-methods",
          "2026-11-10");

  private RequestLimitsPresets() {}

  /**
   * 返回该协议与端点的限制快照；未命中官方域名时返回全未知快照。
   *
   * @param type Provider 协议类型
   * @param endpoint 请求端点 baseUrl
   */
  public static RequestLimits resolve(ProviderType type, String endpoint) {
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(endpoint, "endpoint");
    return switch (type) {
      case ANTHROPIC -> officialHost(endpoint, "api.anthropic.com") ? CLAUDE_DIRECT : unknown();
      case OPENAI, OPENAI_RESPONSES -> officialHost(endpoint, "api.openai.com")
          ? OPENAI_OFFICIAL
          : unknown();
      case GOOGLE -> officialHost(endpoint, "generativelanguage.googleapis.com")
          ? GEMINI_INLINE
          : unknown();
    };
  }

  /** 全未知快照：代理、私有网关等非官方端点唯一合法结果。 */
  public static RequestLimits unknown() {
    return new RequestLimits(
        null,
        ImageRequestLimits.UNKNOWN,
        DocumentRequestLimits.UNKNOWN,
        AudioRequestLimits.UNKNOWN,
        VideoRequestLimits.UNKNOWN,
        null,
        null,
        null);
  }

  private static boolean officialHost(String endpoint, String expectedHost) {
    URI uri;
    try {
      uri = URI.create(endpoint.trim());
    } catch (IllegalArgumentException invalid) {
      return false;
    }
    String host = uri.getHost();
    return host != null && expectedHost.equalsIgnoreCase(host.toLowerCase(Locale.ROOT));
  }
}
