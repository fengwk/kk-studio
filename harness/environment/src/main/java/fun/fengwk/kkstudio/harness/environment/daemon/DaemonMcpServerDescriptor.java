package fun.fengwk.kkstudio.harness.environment.daemon;

import java.util.List;
import java.util.Objects;

/**
 * Daemon READY 中暴露的 MCP server 摘要。
 *
 * <p>仅包含可安全上报的短字段：name、status、有界错误摘要与工具 name/description。headers、environment、命令、URL、本地路径与完整 schema
 * 永不进入 READY wire。FAILED server 不允许携带工具摘要。
 */
public record DaemonMcpServerDescriptor(
    String name, DaemonMcpServerStatus status, String error, List<DaemonMcpToolDescriptor> tools) {

  /** 错误摘要的字符上限；超出上限的原始错误在 daemon 侧先做有界化。 */
  public static final int MAX_ERROR_CHARS = 500;

  private static final String NAME_PATTERN = "[a-z][a-z0-9-]{0,63}";

  public DaemonMcpServerDescriptor {
    if (name == null || !name.matches(NAME_PATTERN)) {
      throw new IllegalArgumentException(
          "name must be a canonical lowercase hyphenated name (1-64 chars): " + name);
    }
    status = Objects.requireNonNull(status, "status");
    if (error != null) {
      if (error.isBlank()) {
        error = null;
      } else if (error.length() > MAX_ERROR_CHARS) {
        throw new IllegalArgumentException(
            "error must not exceed " + MAX_ERROR_CHARS + " characters");
      }
    }
    if (status == DaemonMcpServerStatus.READY && error != null) {
      throw new IllegalArgumentException("READY server must not carry an error");
    }
    tools = List.copyOf(Objects.requireNonNull(tools, "tools"));
    if (status == DaemonMcpServerStatus.FAILED && !tools.isEmpty()) {
      throw new IllegalArgumentException("FAILED server must not carry tool summaries");
    }
    long unique = tools.stream().map(DaemonMcpToolDescriptor::name).distinct().count();
    if (unique != tools.size()) {
      throw new IllegalArgumentException("duplicate MCP tool name: " + name);
    }
  }
}
