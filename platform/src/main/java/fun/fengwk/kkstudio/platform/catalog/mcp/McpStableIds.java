package fun.fengwk.kkstudio.platform.catalog.mcp;

import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * MCP 工具的稳定全局身份。
 *
 * <p>身份由 mcp_tool 行的稳定 UUID 派生：AgentToolId = {@code mcp.<32 位小写 hex uuid>}；ContributionId =
 * contributor {@code platform.mcp} + localName {@code tool.<32 位小写 hex uuid>}。refresh/update 按
 * {@code (serverId, sourceName)} 保留既有 UUID，因此身份跨发现周期稳定；新工具生成新 UUID。
 */
public final class McpStableIds {

  /** MCP 工具贡献的唯一 contributor 身份。 */
  public static final ContributorId CONTRIBUTOR_ID = new ContributorId("platform.mcp");

  /** AgentToolId 前缀。 */
  public static final String AGENT_TOOL_ID_PREFIX = "mcp.";

  /** ContributionId localName 前缀。 */
  public static final String LOCAL_NAME_PREFIX = "tool.";

  private McpStableIds() {}

  /** 返回工具稳定 UUID 对应的 AgentToolId。 */
  public static AgentToolId agentToolId(UUID toolId) {
    Objects.requireNonNull(toolId, "toolId");
    return new AgentToolId(AGENT_TOOL_ID_PREFIX + canonicalUuid(toolId));
  }

  /** 返回工具稳定 UUID 对应的 ContributionId localName。 */
  public static String localName(UUID toolId) {
    Objects.requireNonNull(toolId, "toolId");
    return LOCAL_NAME_PREFIX + canonicalUuid(toolId);
  }

  /** 从 canonical AgentToolId 值解析工具 UUID；格式非法时返回 empty。 */
  public static Optional<UUID> parseAgentToolId(String value) {
    if (value == null || !value.startsWith(AGENT_TOOL_ID_PREFIX)) {
      return Optional.empty();
    }
    return parseCanonicalUuid(value.substring(AGENT_TOOL_ID_PREFIX.length()));
  }

  /** 返回 canonical（小写、无连字符）的 32 位 hex UUID 文本。 */
  public static String canonicalUuid(UUID value) {
    return Objects.requireNonNull(value, "value").toString().replace("-", "");
  }

  /** 解析 32 位小写 hex UUID；非法（包含大写、非 hex 或长度不为 32）返回 empty。 */
  public static Optional<UUID> parseCanonicalUuid(String value) {
    if (value == null || value.length() != 32) {
      return Optional.empty();
    }
    StringBuilder dashed = new StringBuilder(36);
    for (int index = 0; index < 32; index++) {
      char current = value.charAt(index);
      boolean isHex = (current >= '0' && current <= '9') || (current >= 'a' && current <= 'f');
      if (!isHex) {
        return Optional.empty();
      }
      if (index == 8 || index == 12 || index == 16 || index == 20) {
        dashed.append('-');
      }
      dashed.append(current);
    }
    try {
      return Optional.of(UUID.fromString(dashed.toString()));
    } catch (IllegalArgumentException error) {
      return Optional.empty();
    }
  }
}
