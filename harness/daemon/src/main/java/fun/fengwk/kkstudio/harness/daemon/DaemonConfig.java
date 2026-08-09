package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Daemon 独立进程的连接与执行配置。
 *
 * <p>连接、身份、workdir、skill 与 MCP 的唯一配置来源是 CLI：{@code --environment-name}、gateway 连接参数、唯一 {@code
 * --workdir}、可重复 {@code --skill-dir} 与可选 {@code --mcp-config}。{@code --environment-name} 是
 * canonical 路由身份，必须是规范的 {@link EnvironmentName}。workdir 默认启动用户 canonical HOME；skill/MCP
 * 路径不使用服务端托管配置。
 */
public record DaemonConfig(
    URI gatewayUri,
    EnvironmentName environmentName,
    String daemonId,
    Duration heartbeatInterval,
    Duration initialReconnectDelay,
    Duration maxReconnectDelay,
    Duration defaultToolTimeout,
    String gatewayToken,
    Path workdir,
    List<Path> skillDirs,
    Path mcpConfigPath) {

  public DaemonConfig {
    gatewayUri = Objects.requireNonNull(gatewayUri, "gatewayUri");
    if (!"ws".equals(gatewayUri.getScheme()) && !"wss".equals(gatewayUri.getScheme())) {
      throw new IllegalArgumentException("gatewayUri must use ws or wss");
    }
    environmentName = Objects.requireNonNull(environmentName, "environmentName");
    daemonId = requireNonBlank(daemonId, "daemonId");
    heartbeatInterval = requirePositive(heartbeatInterval, "heartbeatInterval");
    initialReconnectDelay = requireNonNegative(initialReconnectDelay, "initialReconnectDelay");
    maxReconnectDelay = requirePositive(maxReconnectDelay, "maxReconnectDelay");
    if (initialReconnectDelay.compareTo(maxReconnectDelay) > 0) {
      throw new IllegalArgumentException("initialReconnectDelay must not exceed maxReconnectDelay");
    }
    defaultToolTimeout = requirePositive(defaultToolTimeout, "defaultToolTimeout");
    gatewayToken = requireNonBlank(gatewayToken, "gatewayToken");
    workdir = canonicalDirectory(workdir, "workdir");
    skillDirs =
        List.copyOf(Objects.requireNonNull(skillDirs, "skillDirs")).stream()
            .map(
                path ->
                    Objects.requireNonNull(path, "skillDirs element").toAbsolutePath().normalize())
            .toList();
    mcpConfigPath = mcpConfigPath == null ? null : mcpConfigPath.toAbsolutePath().normalize();
  }

  /** 解析 CLI 参数。未给出 {@code --skill-dir} 时默认 {@code ~/.agents/skills}（仅当该目录存在时纳入）。 */
  public static DaemonConfig fromArgs(String[] args) {
    Objects.requireNonNull(args, "args");
    String environmentName = null;
    String gatewayUri = null;
    String daemonId = null;
    String gatewayToken = null;
    String heartbeat = null;
    String reconnectInitial = null;
    String reconnectMax = null;
    String toolTimeout = null;
    String mcpConfig = null;
    String workdir = null;
    List<Path> skillDirs = new ArrayList<>();
    boolean skillDirExplicit = false;

    for (int index = 0; index < args.length; index++) {
      String arg = args[index];
      switch (arg) {
        case "--environment-name" -> environmentName = requireArgValue(args, ++index, arg);
        case "--gateway-uri" -> gatewayUri = requireArgValue(args, ++index, arg);
        case "--daemon-id" -> daemonId = requireArgValue(args, ++index, arg);
        case "--gateway-token" -> gatewayToken = requireArgValue(args, ++index, arg);
        case "--heartbeat" -> heartbeat = requireArgValue(args, ++index, arg);
        case "--reconnect-initial" -> reconnectInitial = requireArgValue(args, ++index, arg);
        case "--reconnect-max" -> reconnectMax = requireArgValue(args, ++index, arg);
        case "--tool-timeout" -> toolTimeout = requireArgValue(args, ++index, arg);
        case "--mcp-config" -> mcpConfig = requireArgValue(args, ++index, arg);
        case "--workdir" -> {
          if (workdir != null) {
            throw new IllegalArgumentException("--workdir may only be specified once");
          }
          workdir = requireArgValue(args, ++index, arg);
        }
        case "--skill-dir" -> {
          skillDirExplicit = true;
          skillDirs.add(Path.of(requireArgValue(args, ++index, arg)));
        }
        default -> throw new IllegalArgumentException("unknown argument: " + arg);
      }
    }

    if (daemonId == null || daemonId.isBlank()) {
      daemonId = UUID.randomUUID().toString();
    }

    if (!skillDirExplicit) {
      Path defaultSkillDir = defaultSkillDir();
      if (Files.isDirectory(defaultSkillDir)) {
        skillDirs.add(defaultSkillDir);
      }
    }

    return new DaemonConfig(
        URI.create(requirePresent(gatewayUri, "gateway-uri")),
        new EnvironmentName(requirePresent(environmentName, "environment-name")),
        requirePresent(daemonId, "daemon-id"),
        parseDuration(heartbeat, Duration.ofSeconds(15)),
        parseDuration(reconnectInitial, Duration.ofSeconds(1)),
        parseDuration(reconnectMax, Duration.ofSeconds(30)),
        parseDuration(toolTimeout, Duration.ofMinutes(5)),
        requirePresent(gatewayToken, "gateway-token"),
        workdir == null ? defaultWorkdir() : Path.of(workdir),
        skillDirs,
        mcpConfig == null ? null : Path.of(mcpConfig));
  }

  /** 默认本地 skill 根目录：{@code ~/.agents/skills}。 */
  public static Path defaultSkillDir() {
    return Path.of(System.getProperty("user.home"), ".agents", "skills");
  }

  /** 默认执行目录：启动用户 HOME 的 canonical 目录。 */
  public static Path defaultWorkdir() {
    return canonicalDirectory(Path.of(System.getProperty("user.home")), "user.home");
  }

  private static String requireArgValue(String[] args, int index, String flag) {
    if (index >= args.length) {
      throw new IllegalArgumentException("missing value for " + flag);
    }
    String value = args[index];
    if (value == null || value.isBlank() || value.startsWith("--")) {
      throw new IllegalArgumentException("missing value for " + flag);
    }
    return value;
  }

  private static String requirePresent(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("missing required configuration: " + name);
    }
    return value;
  }

  private static Duration parseDuration(String value, Duration defaultValue) {
    if (value == null || value.isBlank()) {
      return defaultValue;
    }
    return Duration.parse(value);
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static Duration requirePositive(Duration value, String name) {
    value = Objects.requireNonNull(value, name);
    if (value.isNegative() || value.isZero()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
    return value;
  }

  private static Duration requireNonNegative(Duration value, String name) {
    value = Objects.requireNonNull(value, name);
    if (value.isNegative()) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
    return value;
  }

  private static Path canonicalDirectory(Path value, String name) {
    try {
      Path path = Objects.requireNonNull(value, name).toRealPath();
      if (!Files.isDirectory(path)) {
        throw new IllegalArgumentException(name + " must be an existing directory");
      }
      return path;
    } catch (IOException error) {
      throw new IllegalArgumentException(name + " must be an existing directory", error);
    }
  }
}
