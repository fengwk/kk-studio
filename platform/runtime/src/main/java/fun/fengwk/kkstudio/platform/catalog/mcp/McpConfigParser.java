package fun.fengwk.kkstudio.platform.catalog.mcp;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.common.error.DomainException;

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
 * MCP Server 名称与显式 HTTP 配置的校验与规范化，CRUD 与配置同步共用同一套规则。
 *
 * <p>安全边界：错误信息中绝不泄露 URL、header 值或凭据。
 */
public final class McpConfigParser {

  private static final String RESOURCE = "mcp_server";
  private static final int NAME_MAX_LENGTH = 32;
  private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");
  public static final long DEFAULT_TIMEOUT_MILLIS = 60_000L;
  public static final int URL_MAX_LENGTH = 2048;
  private static final int HEADER_NAME_MAX_LENGTH = 256;
  private static final int HEADER_VALUE_MAX_LENGTH = 8192;

  private static final Pattern WHOLE_VAR_PATTERN =
      Pattern.compile("^\\$\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}$");

  private static final JsonMapper HEADERS_MAPPER = JsonMapper.builder().build();

  private McpConfigParser() {}

  /** 校验 Server 名：{@code ^[a-z][a-z0-9_]*$}，不超过 32 个字符。 */
  public static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw DomainException.validation(RESOURCE, RESOURCE + " name must not be blank");
    }
    if (!NAME_PATTERN.matcher(name).matches()) {
      throw DomainException.validation(
          RESOURCE, RESOURCE + " name must match ^[a-z][a-z0-9_]*$: " + name);
    }
    if (name.length() > NAME_MAX_LENGTH) {
      throw DomainException.validation(
          RESOURCE, RESOURCE + " name must not exceed " + NAME_MAX_LENGTH + " characters");
    }
    return name;
  }

  /** 校验并规范化显式 HTTP 配置；{@code enabled} 为空时按 true、{@code timeoutMillis} 为空时按默认值处理（创建与导入）。 */
  public static HttpConfig normalizeHttpConfig(
      String url, Map<String, String> headers, Boolean enabled, Long timeoutMillis) {
    return new HttpConfig(
        requireUrl(url),
        normalizeHeaders(headers),
        enabled == null || enabled,
        timeoutMillis == null
            ? DEFAULT_TIMEOUT_MILLIS
            : requirePositiveTimeoutMillis(timeoutMillis));
  }

  /** 校验并规范化显式 HTTP 配置；更新是整体替换，enabled 与 timeoutMillis 必填且不补默认值。 */
  public static HttpConfig requireHttpConfig(
      String url, Map<String, String> headers, boolean enabled, long timeoutMillis) {
    return new HttpConfig(
        requireUrl(url),
        normalizeHeaders(headers),
        enabled,
        requirePositiveTimeoutMillis(timeoutMillis));
  }

  /** 归一化后的显式 HTTP 配置；headers 可能内嵌凭据，不进入 {@link #toString()}。 */
  public record HttpConfig(
      String url, Map<String, String> headers, boolean enabled, long timeoutMillis) {

    @Override
    public String toString() {
      return "HttpConfig[enabled=" + enabled + ", timeoutMillis=" + timeoutMillis + "]";
    }
  }

  /** 校验并规范化 endpoint URL：必须是绝对 http/https 地址且不含 user-info 凭据。 */
  private static String requireUrl(String url) {
    if (url == null || url.isBlank()) {
      throw DomainException.validation(RESOURCE, "url must not be blank");
    }
    String trimmed = url.strip();
    if (!url.equals(trimmed)) {
      throw DomainException.validation(RESOURCE, "url must not contain surrounding whitespace");
    }
    if (trimmed.length() > URL_MAX_LENGTH) {
      throw DomainException.validation(
          RESOURCE, "url must not exceed " + URL_MAX_LENGTH + " characters");
    }
    try {
      URI uri = new URI(trimmed);
      if (!uri.isAbsolute()) {
        throw DomainException.validation(RESOURCE, "url must be an absolute http or https URL");
      }
      String scheme = uri.getScheme();
      if (scheme == null
          || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))) {
        throw DomainException.validation(RESOURCE, "url scheme must be http or https");
      }
      if (uri.getHost() == null || uri.getHost().isBlank()) {
        throw DomainException.validation(RESOURCE, "url must contain a valid host");
      }
      if (uri.getUserInfo() != null || uri.getRawUserInfo() != null) {
        throw DomainException.validation(RESOURCE, "url must not contain user-info credentials");
      }
    } catch (URISyntaxException error) {
      throw DomainException.validation(RESOURCE, "url format is invalid");
    }
    return trimmed;
  }

  /** 校验并规范化自定义 header 映射；{@code null} 归一化为空映射。 */
  private static Map<String, String> normalizeHeaders(Map<String, String> headers) {
    if (headers == null || headers.isEmpty()) {
      return Map.of();
    }
    Map<String, String> normalized = new LinkedHashMap<>();
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      String key = entry.getKey();
      if (key == null || key.isBlank()) {
        throw DomainException.validation(RESOURCE, "header names must not be blank");
      }
      if (key.length() > HEADER_NAME_MAX_LENGTH) {
        throw DomainException.validation(
            RESOURCE, "header name must not exceed " + HEADER_NAME_MAX_LENGTH + " characters");
      }
      if (key.codePoints().anyMatch(Character::isISOControl)) {
        throw DomainException.validation(RESOURCE, "header names must not contain control chars");
      }
      String value = entry.getValue();
      if (value == null) {
        value = "";
      }
      if (value.length() > HEADER_VALUE_MAX_LENGTH) {
        throw DomainException.validation(
            RESOURCE, "header value must not exceed " + HEADER_VALUE_MAX_LENGTH + " characters");
      }
      if (value.codePoints().anyMatch(Character::isISOControl)) {
        throw DomainException.validation(RESOURCE, "header values must not contain control chars");
      }
      normalized.put(key, value);
    }
    return Collections.unmodifiableMap(normalized);
  }

  /** 解析秘密更新携带的 header 文本：必须是 header 名到字符串值的 JSON 对象。 */
  public static Map<String, String> parseHeaders(String headersJson) {
    if (headersJson == null || headersJson.isBlank()) {
      throw DomainException.validation(RESOURCE, "headers must be a JSON object of string values");
    }
    JsonNode root;
    try {
      root = HEADERS_MAPPER.readTree(headersJson);
    } catch (IllegalArgumentException | JacksonException error) {
      throw DomainException.validation(
          RESOURCE, "headers must be a JSON object of string values", error);
    }
    if (root == null || !root.isObject()) {
      throw DomainException.validation(RESOURCE, "headers must be a JSON object of string values");
    }
    Map<String, String> parsed = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : root.properties()) {
      JsonNode value = entry.getValue();
      if (!value.isString()) {
        throw DomainException.validation(
            RESOURCE, "headers must be a JSON object of string values");
      }
      parsed.put(entry.getKey(), value.stringValue());
    }
    return normalizeHeaders(parsed);
  }

  /** 校验正整数毫秒超时。 */
  private static long requirePositiveTimeoutMillis(long timeoutMillis) {
    if (timeoutMillis <= 0) {
      throw DomainException.validation(
          RESOURCE, "timeoutMillis must be positive: " + timeoutMillis);
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
            throw DomainException.validation(
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
