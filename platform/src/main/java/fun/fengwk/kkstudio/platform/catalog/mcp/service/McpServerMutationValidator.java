package fun.fengwk.kkstudio.platform.catalog.mcp.service;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerCreateDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerUpdateDTO;

import java.util.regex.Pattern;

/**
 * MCP server CRUD 输入规范化与校验（不含 bearer token 语义；token 三态由服务层处理）。
 *
 * <p>规则：name 必须 {@code ^[a-z][a-z0-9_]*$} 且 ≤32 字符；url 非空白且 ≤2048；timeoutMillis 正整数。
 */
public final class McpServerMutationValidator {

  /** Server 名最大长度。 */
  public static final int NAME_MAX_LENGTH = 32;

  /** URL 最大长度。 */
  public static final int URL_MAX_LENGTH = 2048;

  /** Bearer token 最大长度。 */
  public static final int BEARER_TOKEN_MAX_LENGTH = 2048;

  private static final String RESOURCE = "mcp_server";
  private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");

  private McpServerMutationValidator() {}

  /** 规范化 create 输入；返回不可变归一化值。 */
  public static NormalizedCreate normalizeCreate(McpServerCreateDTO createDTO) {
    if (createDTO == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " body must not be null");
    }
    String name = requireName(createDTO.getName());
    String url = requireUrl(createDTO.getUrl());
    String bearerToken = normalizeToken(createDTO.getBearerToken(), "bearerToken");
    long timeoutMillis = requireTimeout(createDTO.getTimeoutMillis());
    return new NormalizedCreate(name, url, bearerToken, timeoutMillis);
  }

  /** 规范化 update 输入：字段为 null 表示不修改。 */
  public static NormalizedUpdate normalizeUpdate(McpServerUpdateDTO updateDTO) {
    if (updateDTO == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " body must not be null");
    }
    String url = updateDTO.getUrl() == null ? null : requireUrl(updateDTO.getUrl());
    String bearerToken =
        updateDTO.getBearerToken() == null
            ? null
            : normalizeToken(updateDTO.getBearerToken(), "bearerToken");
    Long timeoutMillis =
        updateDTO.getTimeoutMillis() == null ? null : requireTimeout(updateDTO.getTimeoutMillis());
    return new NormalizedUpdate(url, bearerToken, timeoutMillis);
  }

  private static String requireName(String name) {
    if (name == null || name.isBlank()) {
      throw new AiValidationException(RESOURCE, RESOURCE + " name must not be blank");
    }
    if (!NAME_PATTERN.matcher(name).matches()) {
      throw new AiValidationException(
          RESOURCE, RESOURCE + " name must match ^[a-z][a-z0-9_]*$: " + name);
    }
    if (name.length() > NAME_MAX_LENGTH) {
      throw new AiValidationException(
          RESOURCE, RESOURCE + " name must not exceed " + NAME_MAX_LENGTH + " characters");
    }
    return name;
  }

  private static String requireUrl(String url) {
    if (url == null || url.isBlank()) {
      throw new AiValidationException(RESOURCE, RESOURCE + " url must not be blank");
    }
    String trimmed = url.strip();
    if (!url.equals(trimmed)) {
      throw new AiValidationException(
          RESOURCE, RESOURCE + " url must not contain surrounding whitespace");
    }
    if (trimmed.length() > URL_MAX_LENGTH) {
      throw new AiValidationException(
          RESOURCE, RESOURCE + " url must not exceed " + URL_MAX_LENGTH + " characters");
    }
    return trimmed;
  }

  private static String normalizeToken(String token, String field) {
    if (token == null) {
      return null;
    }
    String trimmed = token.strip();
    if (trimmed.isEmpty()) {
      // 空白 token 视为清除语义（与空字符串一致）。
      return "";
    }
    if (!token.equals(trimmed)) {
      throw new AiValidationException(
          RESOURCE, RESOURCE + " " + field + " must not contain surrounding whitespace");
    }
    if (trimmed.length() > BEARER_TOKEN_MAX_LENGTH) {
      throw new AiValidationException(
          RESOURCE,
          RESOURCE + " " + field + " must not exceed " + BEARER_TOKEN_MAX_LENGTH + " characters");
    }
    return trimmed;
  }

  private static long requireTimeout(Long timeoutMillis) {
    if (timeoutMillis == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " timeoutMillis must not be null");
    }
    if (timeoutMillis <= 0) {
      throw new AiValidationException(
          RESOURCE, RESOURCE + " timeoutMillis must be positive: " + timeoutMillis);
    }
    return timeoutMillis;
  }

  /** create 归一化值。 */
  public record NormalizedCreate(String name, String url, String bearerToken, long timeoutMillis) {}

  /** update 归一化值（null 字段表示不修改）。 */
  public record NormalizedUpdate(String url, String bearerToken, Long timeoutMillis) {}
}
