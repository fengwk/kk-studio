package fun.fengwk.kkstudio.harness.provider.gemini;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Gemini 端点 URI 解析与安全规范化工具。 */
final class GeminiEndpoints {

  private GeminiEndpoints() {}

  /**
   * 将 ProviderDescriptor 的配置 baseUrl 校验并安全追加模型路径及 SSE 参数。
   *
   * <ul>
   *   <li>支持 http / https 协议；
   *   <li>必须包含合法 host；
   *   <li>严格禁止 user-info、query、fragment；
   *   <li>保留已验证 endpoint 的 raw authority（包含 IPv6 和端口）与已转义 raw base path，避免双重转义；
   *   <li>去除 baseUrl 尾部多余斜杠；
   *   <li>使用 RFC 3986 path-segment 语义对 modelName 进行 UTF-8 百分号编码（仅保留 unreserved 字符）；
   *   <li>追加 /models/{encodedModel}:streamGenerateContent?alt=sse；
   *   <li>严禁把 API Key 拼入 URL 中；
   *   <li>异常消息绝不回显 endpoint 或 modelName 原始内容。
   * </ul>
   */
  static URI resolveStreamUri(String endpoint, String modelName) {
    if (modelName == null || modelName.isBlank()) {
      throw new IllegalArgumentException("modelName must not be blank");
    }
    URI baseUri = resolveBaseUri(endpoint);
    String rawPath = baseUri.getRawPath();
    if (rawPath == null) {
      rawPath = "";
    }
    while (rawPath.endsWith("/")) {
      rawPath = rawPath.substring(0, rawPath.length() - 1);
    }
    String encodedModel = encodePathSegment(modelName.trim());
    String streamPath = rawPath + "/models/" + encodedModel + ":streamGenerateContent?alt=sse";
    return URI.create(
        baseUri.getScheme().toLowerCase(Locale.ROOT)
            + "://"
            + baseUri.getRawAuthority()
            + streamPath);
  }

  /** 将 ProviderDescriptor 的配置 baseUrl 进行基础合法性检查并规范化。 */
  static URI resolveBaseUri(String endpoint) {
    if (endpoint == null || endpoint.isBlank()) {
      throw new IllegalArgumentException("endpoint must not be blank");
    }
    URI uri;
    try {
      uri = URI.create(endpoint);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("endpoint is not a valid URI");
    }
    String scheme = uri.getScheme();
    if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
      throw new IllegalArgumentException("endpoint scheme must be http or https");
    }
    if (uri.getRawUserInfo() != null) {
      throw new IllegalArgumentException("endpoint must not contain user-info");
    }
    if (uri.getRawQuery() != null) {
      throw new IllegalArgumentException("endpoint must not contain query");
    }
    if (uri.getRawFragment() != null) {
      throw new IllegalArgumentException("endpoint must not contain fragment");
    }
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      throw new IllegalArgumentException("endpoint must contain a valid host");
    }
    String rawPath = uri.getRawPath();
    if (rawPath == null) {
      rawPath = "";
    }
    while (rawPath.endsWith("/")) {
      rawPath = rawPath.substring(0, rawPath.length() - 1);
    }
    return URI.create(scheme.toLowerCase(Locale.ROOT) + "://" + uri.getRawAuthority() + rawPath);
  }

  /**
   * 按照 RFC 3986 path-segment 语义对字符串的 UTF-8 字节进行百分号编码。
   *
   * <p>仅 unreserved 字符 (ALPHA / DIGIT / "-" / "." / "_" / "~") 保持原样，其余所有字符均进行大写 %XX 编码。
   */
  static String encodePathSegment(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    StringBuilder sb = new StringBuilder(bytes.length * 3);
    for (byte b : bytes) {
      int c = b & 0xFF;
      if (isUnreserved(c)) {
        sb.append((char) c);
      } else {
        sb.append('%');
        char hex1 = Character.forDigit((c >> 4) & 0xF, 16);
        char hex2 = Character.forDigit(c & 0xF, 16);
        sb.append(Character.toUpperCase(hex1));
        sb.append(Character.toUpperCase(hex2));
      }
    }
    return sb.toString();
  }

  private static boolean isUnreserved(int c) {
    return (c >= 'a' && c <= 'z')
        || (c >= 'A' && c <= 'Z')
        || (c >= '0' && c <= '9')
        || c == '-'
        || c == '.'
        || c == '_'
        || c == '~';
  }
}
