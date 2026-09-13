package fun.fengwk.kkstudio.harness.daemon.mcp;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Daemon 侧解析校验完成的 Local MCP 规范化配置模型。
 *
 * <p>禁止在 toString、日志或错误中回显 command、cwd、env 或 payload 内容。
 */
public record DaemonLocalMcpConfig(
    String serverId,
    long configVersion,
    String environmentId,
    List<String> command,
    String cwd,
    Map<String, String> env,
    boolean enabled,
    long timeoutMillis) {

  public DaemonLocalMcpConfig {
    Objects.requireNonNull(serverId, "serverId");
    if (serverId.isBlank()) {
      throw new IllegalArgumentException("serverId must not be blank");
    }
    if (configVersion < 0) {
      throw new IllegalArgumentException("configVersion must not be negative");
    }
    Objects.requireNonNull(environmentId, "environmentId");
    if (environmentId.isBlank()) {
      throw new IllegalArgumentException("environmentId must not be blank");
    }
    Objects.requireNonNull(command, "command");
    if (command.isEmpty()) {
      throw new IllegalArgumentException("command must not be empty");
    }
    command = List.copyOf(command);
    Objects.requireNonNull(cwd, "cwd");
    if (cwd.isBlank()) {
      throw new IllegalArgumentException("cwd must not be blank");
    }
    Objects.requireNonNull(env, "env");
    env = Map.copyOf(env);
    if (timeoutMillis <= 0) {
      throw new IllegalArgumentException("timeoutMillis must be positive");
    }
  }

  @Override
  public String toString() {
    return "DaemonLocalMcpConfig[serverId=" + serverId + ", configVersion=" + configVersion + "]";
  }
}
