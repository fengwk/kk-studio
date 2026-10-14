package fun.fengwk.kkstudio.platform.catalog.mcp.service;

import fun.fengwk.kkstudio.platform.error.AiValidationException;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP Server 显式 HTTP 配置的校验与规范化。
 *
 * <p>安全边界：错误信息中绝不泄露 URL、header 值或凭据。
 */
public final class McpConfigParser {

  private static final String RESOURCE = "mcp_server";
  public static final long DEFAULT_TIMEOUT_MILLIS = 60_000L;
  public static final int URL_MAX_LENGTH = 2048;
  public static final int HEADER_NAME_MAX_LENGTH = 256;
  public static final int HEADER_VALUE_MAX_LENGTH = 8192;

  private static final Pattern WHOLE_VAR_PATTERN =
      Pattern.compile("^\\$\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}$");

  private McpConfigParser() {}

  /** 校验并规范化 endpoint URL：必须是绝对 http/https 地址且不含 user-info 凭据。 */
  public static String requireUrl(String url) {
    if (url == null || url.isBlank()) {
      throw new AiValidationException(RESOURCE, "url must not be blank");
    }
    String trimmed = url.strip();
    if (!url.equals(trimmed)) {
      throw new AiValidationException(RESOURCE, "url must not contain surrounding whitespace");
    }
    if (trimmed.length() > URL_MAX_LENGTH) {
      throw new AiValidationException(
          RESOURCE, "url must not exceed " + URL_MAX_LENGTH + " characters");
    }
    try {
      URI uri = new URI(trimmed);
      if (!uri.isAbsolute()) {
        throw new AiValidationException(RESOURCE, "url must be an absolute http or https URL");
      }
      String scheme = uri.getScheme();
      if (scheme == null
          || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
        throw new AiValidationException(RESOURCE, "url scheme must be http or https");
      }
      if (uri.getHost() == null || uri.getHost().isBlank()) {
        throw new AiValidationException(RESOURCE, "url must contain a valid host");
      }
      if (uri.getUserInfo() != null || uri.getRawUserInfo() != null) {
        throw new AiValidationException(RESOURCE, "url must not contain user-info credentials");
      }
    } catch (URISyntaxException error) {
      throw new AiValidationException(RESOURCE, "url format is invalid");
    }
    return trimmed;
  }

  /** 校验并规范化自定义 header 映射；{@code null} 归一化为空映射。 */
  public static Map<String, String> normalizeHeaders(Map<String, String> headers) {
    if (headers == null || headers.isEmpty()) {
      return Map.of();
    }
    Map<String, String> normalized = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      String key = entry.getKey();
      if (key == null || key.isBlank()) {
        throw new AiValidationException(RESOURCE, "header names must not be blank");
      }
      if (key.length() > HEADER_NAME_MAX_LENGTH) {
        throw new AiValidationException(
            RESOURCE, "header name must not exceed " + HEADER_NAME_MAX_LENGTH + " characters");
      }
      if (key.codePoints().anyMatch(Character::isISOControl)) {
        throw new AiValidationException(RESOURCE, "header names must not contain control chars");
      }
      String value = entry.getValue();
      if (value == null) {
        value = "";
      }
      if (value.length() > HEADER_VALUE_MAX_LENGTH) {
        throw new AiValidationException(
            RESOURCE, "header value must not exceed " + HEADER_VALUE_MAX_LENGTH + " characters");
      }
      if (value.codePoints().anyMatch(Character::isISOControl)) {
        throw new AiValidationException(RESOURCE, "header values must not contain control chars");
      }
      normalized.put(key, value);
    }
    return Collections.unmodifiableMap(normalized);
  }

  /** 校验正整数毫秒超时；{@code null} 归一化为 {@link #DEFAULT_TIMEOUT_MILLIS}。 */
  public static long normalizeTimeoutMillis(Long timeoutMillis) {
    if (timeoutMillis == null) {
      return DEFAULT_TIMEOUT_MILLIS;
    }
    if (timeoutMillis <= 0) {
      throw new AiValidationException(RESOURCE, "timeoutMillis must be positive: " + timeoutMillis);
    }
    return timeoutMillis;
  }

  /** 解析并替换 header 值中的整值 {@code ${VAR}} 占位符。 */
  public static Map<String, String> resolveHeaders(
      Map<String, String> headers, Function<String, String> envProvider) {
    if (headers == null || headers.isEmpty()) {
      return Map.of();
    }
    Objects.requireNonNull(envProvider, "envProvider");
    Map<String, String> resolved = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      String key = entry.getKey();
      String value = entry.getValue();
      if (value != null) {
        Matcher matcher = WHOLE_VAR_PATTERN.matcher(value.trim());
        if (matcher.matches()) {
          String varName = matcher.group(1);
          String resolvedVal = envProvider.apply(varName);
          if (resolvedVal == null || resolvedVal.isBlank()) {
            throw new AiValidationException(
                RESOURCE, "required environment variable not found: " + varName);
          }
          resolved.put(key, resolvedVal);
          continue;
        }
      }
      resolved.put(key, value);
    }
    return Collections.unmodifiableMap(resolved);
  }
}
