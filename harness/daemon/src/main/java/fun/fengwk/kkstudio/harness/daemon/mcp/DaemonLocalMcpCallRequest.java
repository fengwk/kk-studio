package fun.fengwk.kkstudio.harness.daemon.mcp;

import java.util.Objects;

/** 解析后的 mcp.local.call 请求。 */
public record DaemonLocalMcpCallRequest(
    DaemonLocalMcpConfig config, String toolName, String argumentsJson) {

  public DaemonLocalMcpCallRequest {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(toolName, "toolName");
    if (toolName.isBlank()) {
      throw new IllegalArgumentException("toolName must not be blank");
    }
    argumentsJson = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
  }

  @Override
  public String toString() {
    return "DaemonLocalMcpCallRequest[serverId="
        + config.serverId()
        + ", toolName="
        + toolName
        + "]";
  }
}
