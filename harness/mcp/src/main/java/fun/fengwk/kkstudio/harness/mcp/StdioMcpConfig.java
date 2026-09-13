package fun.fengwk.kkstudio.harness.mcp;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 本地 Stdio MCP 连接参数。
 *
 * <p>调用方必须提供已解析完成的 command（argv 列表）、绝对 cwd 与 env； 不得在 toString、日志或错误中回显 command、cwd 或 env 内容。
 *
 * <p>cwd 以当前 Daemon 所在 OS 的 {@link Path} 语义判定为绝对路径：该模块描述本机 stdio 进程，因此不接受其它 OS 的词法。
 */
public record StdioMcpConfig(List<String> command, String cwd, Map<String, String> env) {

  public StdioMcpConfig {
    Objects.requireNonNull(command, "command");
    if (command.isEmpty()) {
      throw new IllegalArgumentException("command must not be empty");
    }
    for (String arg : command) {
      if (arg == null || arg.isBlank()) {
        throw new IllegalArgumentException("command argument must not be blank");
      }
    }
    command = List.copyOf(command);
    if (cwd == null || cwd.isBlank()) {
      throw new IllegalArgumentException("cwd must not be blank");
    }
    try {
      if (!Path.of(cwd).isAbsolute()) {
        throw new IllegalArgumentException("cwd must be an absolute path");
      }
    } catch (InvalidPathException error) {
      throw new IllegalArgumentException("cwd is not a valid absolute path");
    }
    Objects.requireNonNull(env, "env");
    env = Map.copyOf(env);
  }

  @Override
  public String toString() {
    return "StdioMcpConfig[commandArgs=" + command.size() + ", envVars=" + env.size() + "]";
  }
}
