package fun.fengwk.kkstudio.platform.catalog.mcp;

import fun.fengwk.kkstudio.harness.contributor.api.ContributorId;
import fun.fengwk.kkstudio.platform.error.AiValidationException;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * MCP 工具与 Server 的稳定全局身份解析器。
 *
 * <p>ContributionId 由 mcp_tool 行的稳定 UUID 派生：contributor {@code platform.mcp} + localName {@code
 * tool.<32 位小写 hex uuid>}。模型可见工具身份是 mcp_tool.model_name，不由本类派生。
 *
 * <p>所有 MCP 外部与路径 UUID 参数均在此严格校验规范的小写连字符格式（36 字符）。
 */
public final class McpStableIds {

  /** MCP 工具贡献的唯一 contributor 身份。 */
  public static final ContributorId CONTRIBUTOR_ID = new ContributorId("platform.mcp");

  /** ContributionId localName 前缀。 */
  public static final String LOCAL_NAME_PREFIX = "tool.";

  private McpStableIds() {}

  /** 返回工具稳定 UUID 对应的 ContributionId localName。 */
  public static String localName(UUID toolId) {
    Objects.requireNonNull(toolId, "toolId");
    return LOCAL_NAME_PREFIX + canonicalUuid(toolId);
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
    return Optional.of(UUID.fromString(dashed.toString()));
  }

  /** 判断字符串是否为标准的小写带连字符 36 字符 UUID（不接受大写或无连字符）。 */
  public static boolean isCanonicalDashedUuid(String value) {
    if (value == null || value.length() != 36) {
      return false;
    }
    for (int i = 0; i < 36; i++) {
      char c = value.charAt(i);
      if (i == 8 || i == 13 || i == 18 || i == 23) {
        if (c != '-') {
          return false;
        }
      } else {
        if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
          return false;
        }
      }
    }
    return true;
  }

  /** 严格校验并解析 canonical 小写带连字符 UUID；不满足时抛出 {@link AiValidationException}。 */
  public static UUID requireCanonicalDashedUuid(String value, String fieldName) {
    if (!isCanonicalDashedUuid(value)) {
      throw new AiValidationException(
          "mcp_server", fieldName + " must be a canonical lowercase dashed UUID: " + value);
    }
    return UUID.fromString(value);
  }
}
