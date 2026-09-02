package fun.fengwk.kkstudio.platform.catalog.mcp;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * MCP server 名与远端工具名到模型可见工具名的规范化规则。
 *
 * <p>模型可见工具名固定为 {@code mcp_<server_name>_<normalized_source_tool_name>}。normalize 规则：lowercase、 所有非
 * {@code [a-z0-9]} 字符替换为 {@code '_'}、连续 {@code '_'} 合并、去除首尾 {@code '_'}。规范化结果必须满足 {@link
 * fun.fengwk.kkstudio.harness.tool.ToolDescriptor} 的 name 语法且 ≤64 字符、不可空；超长或冲突直接拒绝， 绝不追加 hash 后缀。
 */
public final class McpToolNameNormalizer {

  /** 模型可见工具名的最大长度（ToolDescriptor name 列与 mcp_tool.model_name 列宽一致）。 */
  public static final int MAX_MODEL_NAME_LENGTH = 64;

  private static final String PREFIX = "mcp_";
  private static final Pattern SERVER_NAME = Pattern.compile("^[a-z][a-z0-9_]*$");
  private static final Pattern VALID_MODEL_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");

  private McpToolNameNormalizer() {}

  /**
   * 规范化远端 source tool name。
   *
   * @throws IllegalArgumentException 名称为空白或规范化后为空
   */
  public static String normalizeSourceToolName(String sourceToolName) {
    if (sourceToolName == null || sourceToolName.isBlank()) {
      throw new IllegalArgumentException("source tool name must not be blank");
    }
    String replaced = sourceToolName.toLowerCase().replaceAll("[^a-z0-9]", "_");
    String merged = replaced.replaceAll("_+", "_");
    String trimmed = trimUnderscores(merged);
    if (trimmed.isEmpty()) {
      throw new IllegalArgumentException(
          "source tool name normalizes to an empty identifier: " + sourceToolName);
    }
    return trimmed;
  }

  /** 返回该 server/source 对的完整模型可见工具名（长度不校验，供冲突检测前的展示）。 */
  public static String modelToolName(String serverName, String sourceToolName) {
    Objects.requireNonNull(serverName, "serverName");
    if (!SERVER_NAME.matcher(serverName).matches()) {
      throw new IllegalArgumentException("server name must match ^[a-z][a-z0-9_]*$: " + serverName);
    }
    return PREFIX + serverName + "_" + normalizeSourceToolName(sourceToolName);
  }

  /**
   * 返回模型可见工具名；超过 {@value #MAX_MODEL_NAME_LENGTH} 字符时抛出，绝不追加 hash 后缀。
   *
   * @throws IllegalArgumentException 规范化结果超长
   */
  public static String requireModelToolName(String serverName, String sourceToolName) {
    String modelName = modelToolName(serverName, sourceToolName);
    if (modelName.length() > MAX_MODEL_NAME_LENGTH) {
      throw new IllegalArgumentException(
          "normalized model tool name exceeds "
              + MAX_MODEL_NAME_LENGTH
              + " characters: "
              + modelName);
    }
    return modelName;
  }

  /** 是否满足 ToolDescriptor name 语法（字母开头，仅字母、数字、_、-）。 */
  public static boolean isValidToolDescriptorName(String modelName) {
    return modelName != null && VALID_MODEL_NAME.matcher(modelName).matches();
  }

  private static String trimUnderscores(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && value.charAt(start) == '_') {
      start++;
    }
    while (end > start && value.charAt(end - 1) == '_') {
      end--;
    }
    return value.substring(start, end);
  }
}
