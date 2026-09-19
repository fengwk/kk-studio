package fun.fengwk.kkstudio.platform.catalog.mcp.service;

import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerCreateDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerUpdateDTO;

import java.util.Map;
import java.util.regex.Pattern;

/** MCP server CRUD 输入规范化与校验。 */
public final class McpServerMutationValidator {

  public static final int NAME_MAX_LENGTH = 32;
  private static final String RESOURCE = "mcp_server";
  private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z][a-z0-9_]*$");

  private McpServerMutationValidator() {}

  /** 规范化 create 输入。 */
  public static NormalizedCreate normalizeCreate(McpServerCreateDTO createDTO) {
    if (createDTO == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " body must not be null");
    }
    return new NormalizedCreate(
        requireName(createDTO.getName()),
        normalizeHttpConfig(
            createDTO.getUrl(),
            createDTO.getHeaders(),
            createDTO.getEnabled(),
            createDTO.getTimeoutMillis()));
  }

  /** 规范化 update 输入。 */
  public static NormalizedUpdate normalizeUpdate(McpServerUpdateDTO updateDTO) {
    if (updateDTO == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " body must not be null");
    }
    return new NormalizedUpdate(
        normalizeHttpConfig(
            updateDTO.getUrl(),
            updateDTO.getHeaders(),
            updateDTO.getEnabled(),
            updateDTO.getTimeoutMillis()));
  }

  public static String requireName(String name) {
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

  /** 校验并规范化显式 HTTP 配置；{@code enabled} 为空时按 true 处理。 */
  public static HttpConfig normalizeHttpConfig(
      String url, Map<String, String> headers, Boolean enabled, Long timeoutMillis) {
    return new HttpConfig(
        McpConfigParser.requireUrl(url),
        McpConfigParser.normalizeHeaders(headers),
        enabled == null || enabled,
        McpConfigParser.normalizeTimeoutMillis(timeoutMillis));
  }

  /** 归一化后的显式 HTTP 配置。 */
  public record HttpConfig(
      String url, Map<String, String> headers, boolean enabled, long timeoutMillis) {}

  /** create 归一化值。 */
  public record NormalizedCreate(String name, HttpConfig config) {}

  /** update 归一化值。 */
  public record NormalizedUpdate(HttpConfig config) {}
}
