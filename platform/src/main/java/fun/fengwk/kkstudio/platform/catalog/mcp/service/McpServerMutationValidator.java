package fun.fengwk.kkstudio.platform.catalog.mcp.service;

import fun.fengwk.kkstudio.platform.catalog.mcp.service.McpConfigParser.ParsedMcpConfig;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerCreateDTO;
import fun.fengwk.kkstudio.share.ai.mcp.McpServerUpdateDTO;

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
    String name = requireName(createDTO.getName());
    ParsedMcpConfig config = McpConfigParser.parse(createDTO.getConfigJson());
    return new NormalizedCreate(name, config);
  }

  /** 规范化 update 输入。 */
  public static NormalizedUpdate normalizeUpdate(McpServerUpdateDTO updateDTO) {
    if (updateDTO == null) {
      throw new AiValidationException(RESOURCE, RESOURCE + " body must not be null");
    }
    ParsedMcpConfig config = McpConfigParser.parse(updateDTO.getConfigJson());
    return new NormalizedUpdate(config);
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

  /** create 归一化值。 */
  public record NormalizedCreate(String name, ParsedMcpConfig config) {}

  /** update 归一化值。 */
  public record NormalizedUpdate(ParsedMcpConfig config) {}
}
