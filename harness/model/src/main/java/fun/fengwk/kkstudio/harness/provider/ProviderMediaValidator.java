package fun.fengwk.kkstudio.harness.provider;

import java.util.Base64;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provider 协议共用的媒体类型白名单与 Base64 data URI 校验纯辅助。
 *
 * <p>白名单判定保持大小写敏感：协议可以先规范化 MIME（OpenAI Responses、Gemini），也可以要求声明值精确匹配（Anthropic）。data URI 解析遵循 RFC
 * 2397 的大小写规则，payload 始终逐字节保留。
 */
public final class ProviderMediaValidator {

  /** 用户内容可接受的图片 MIME。 */
  public static final Set<String> IMAGE_MEDIA_TYPES =
      Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

  /** 用户内容可接受的文档 MIME。 */
  public static final Set<String> DOCUMENT_MEDIA_TYPES = Set.of("application/pdf");

  private static final Pattern BASE64_DATA_URI_PATTERN =
      Pattern.compile("^data:([^;,]+);base64,(.*)$", Pattern.CASE_INSENSITIVE);

  private ProviderMediaValidator() {}

  /**
   * 判断媒体类型是否在给定白名单内（区分大小写）。
   *
   * @param mediaType 媒体类型，按原样比较
   * @param allowedTypes 白名单
   * @return 命中白名单时返回 {@code true}
   */
  public static boolean isAllowedMediaType(String mediaType, Set<String> allowedTypes) {
    return mediaType != null && allowedTypes.contains(mediaType);
  }

  /** 严格解析出的 Base64 data URI。 */
  public record Base64DataUri(String mediaType, String payload) {}

  /**
   * 严格解析 Base64 data URI；形态不严格、媒体类型为空、payload 为空或非法 Base64 时返回 {@code null}。
   *
   * @param source 待解析字符串
   * @return 解析结果，或 {@code null}
   */
  public static Base64DataUri parseBase64DataUri(String source) {
    Matcher matcher = source == null ? null : BASE64_DATA_URI_PATTERN.matcher(source);
    if (matcher == null || !matcher.matches()) {
      return null;
    }
    String mediaType = matcher.group(1);
    String payload = matcher.group(2);
    if (mediaType.isBlank() || !isNonEmptyBase64(payload)) {
      return null;
    }
    return new Base64DataUri(mediaType, payload);
  }

  /**
   * 提取声明值与 data URI 媒体类型精确相等（区分大小写）的 Base64 payload。
   *
   * <p>该变体保留 Anthropic 的精确前缀语义：{@code data:<mediaType>;base64,} 必须逐字一致。
   *
   * @param source 待解析字符串
   * @param expectedMediaType 期望的媒体类型
   * @return 合法且匹配的 payload，或 {@code null}
   */
  public static String extractBase64Data(String source, String expectedMediaType) {
    if (source == null || source.isBlank()) {
      return null;
    }
    String prefix = "data:" + expectedMediaType + ";base64,";
    if (!source.startsWith(prefix)) {
      return null;
    }
    String payload = source.substring(prefix.length());
    return isNonEmptyBase64(payload) ? payload : null;
  }

  /** 只校验 payload 可解码且非空，不保留解码后的字节。 */
  private static boolean isNonEmptyBase64(String payload) {
    if (payload.isEmpty()) {
      return false;
    }
    try {
      return Base64.getDecoder().decode(payload).length > 0;
    } catch (IllegalArgumentException invalid) {
      return false;
    }
  }
}
