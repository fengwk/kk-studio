package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonOperatingSystem;

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
 * <p>连接、身份、说明、environment root、skill 与 MCP 的唯一配置来源是 CLI：{@code --environment-name}、gateway
 * 连接参数、可选且唯一 {@code --note}、唯一 {@code --environment-root}、可重复 {@code --skill-dir} 与可选 {@code
 * --mcp-config}。{@code --environment-name} 是 canonical 路由身份，必须是规范的 {@link
 * EnvironmentName}。environment root 默认启动用户 canonical HOME；skill/MCP 路径不使用服务端托管配置。{@code --note}
 * 会进入受信任的模型 SYSTEM Prompt，只能由可信操作者设置，禁止放入凭证、秘密或不可信外部文本。
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
    String note,
    Path environmentRoot,
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
    note = note == null ? null : DaemonEnvironmentInfo.validateNote(note);
    environmentRoot = canonicalDirectory(environmentRoot, "environmentRoot");
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
    String environmentRoot = null;
    String note = null;
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
        case "--note" -> {
          if (note != null) {
            throw new IllegalArgumentException("--note may only be specified once");
          }
          note = requireArgValue(args, ++index, arg);
        }
        case "--environment-root" -> {
          if (environmentRoot != null) {
            throw new IllegalArgumentException("--environment-root may only be specified once");
          }
          environmentRoot = requireArgValue(args, ++index, arg);
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
        note,
        environmentRoot == null ? defaultEnvironmentRoot() : Path.of(environmentRoot),
        skillDirs,
        mcpConfig == null ? null : Path.of(mcpConfig));
  }

  /** 默认本地 skill 根目录：{@code ~/.agents/skills}。 */
  public static Path defaultSkillDir() {
    return Path.of(System.getProperty("user.home"), ".agents", "skills");
  }

  /** 默认 Environment Root：启动用户 HOME 的 canonical 目录。 */
  public static Path defaultEnvironmentRoot() {
    return canonicalDirectory(Path.of(System.getProperty("user.home")), "user.home");
  }

  /** 可信操作者设置的显式 note 优先；省略时按 Daemon 实测 OS 生成稳定默认说明。 */
  public String effectiveNote(DaemonOperatingSystem operatingSystem) {
    Objects.requireNonNull(operatingSystem, "operatingSystem");
    if (note != null) {
      return note;
    }
    return switch (operatingSystem) {
      case WINDOWS -> "Windows environment.";
      case WSL -> "WSL environment. Windows files may be accessible under /mnt/<drive>, and some Windows"
          + " commands may be invocable from WSL.";
      case LINUX -> "Linux environment.";
      case MACOS -> "macOS environment.";
    };
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
