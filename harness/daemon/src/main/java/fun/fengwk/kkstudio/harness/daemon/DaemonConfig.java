package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Daemon 独立进程的连接与执行配置。
 *
 * <p>连接、身份、说明、environment root 与本地数据目录的唯一配置来源是 CLI：{@code --registration-token}、gateway 连接参数、可选且唯一
 * {@code --note}、唯一 {@code --environment-root} 与唯一必填绝对 {@code --data-dir}。{@code --data-dir} 承载受管
 * Git checkout、不可变 skill 正文与 manifest；它由 Daemon 自行创建。{@code --registration-token} 是该 Environment
 * 颁发的 HELLO 注册凭证。Daemon 不配置也不持有 Environment UUID，连接建立后由 Gateway 在 WELCOME 消息中下发。environment root
 * 默认启动用户 canonical HOME，只是宿主展示元数据，不构成任何工具的默认目录。
 *
 * <p>不存在的 Skill 来源不再由 CLI 覆盖：来源是 Platform 的受管配置，Daemon 只按请求扫描。{@code --note} 会进入受信任的模型 SYSTEM
 * Prompt，只能由可信操作者设置，禁止放入凭证、秘密或不可信外部文本。
 */
public record DaemonConfig(
    URI gatewayUri,
    String registrationToken,
    Duration heartbeatInterval,
    Duration initialReconnectDelay,
    Duration maxReconnectDelay,
    Duration defaultToolTimeout,
    String note,
    Path environmentRoot,
    Path dataDir) {

  public DaemonConfig {
    gatewayUri = Objects.requireNonNull(gatewayUri, "gatewayUri");
    if (!"ws".equals(gatewayUri.getScheme()) && !"wss".equals(gatewayUri.getScheme())) {
      throw new IllegalArgumentException("gatewayUri must use ws or wss");
    }
    registrationToken = requireNonBlank(registrationToken, "registrationToken");
    heartbeatInterval = requirePositive(heartbeatInterval, "heartbeatInterval");
    initialReconnectDelay = requireNonNegative(initialReconnectDelay, "initialReconnectDelay");
    maxReconnectDelay = requirePositive(maxReconnectDelay, "maxReconnectDelay");
    if (initialReconnectDelay.compareTo(maxReconnectDelay) > 0) {
      throw new IllegalArgumentException("initialReconnectDelay must not exceed maxReconnectDelay");
    }
    defaultToolTimeout = requirePositive(defaultToolTimeout, "defaultToolTimeout");
    note = note == null ? null : DaemonEnvironmentInfo.validateNote(note);
    environmentRoot = canonicalDirectory(environmentRoot, "environmentRoot");
    dataDir = requireAbsoluteDirectory(dataDir);
  }

  /** 解析 CLI 参数。{@code --data-dir} 必须显式给出且为绝对路径；已删除 {@code --skill-dir} 及其默认目录回退。 */
  public static DaemonConfig fromArgs(String[] args) {
    Objects.requireNonNull(args, "args");
    String gatewayUri = null;
    String registrationToken = null;
    String heartbeat = null;
    String reconnectInitial = null;
    String reconnectMax = null;
    String toolTimeout = null;
    String environmentRoot = null;
    String note = null;
    String dataDir = null;

    for (int index = 0; index < args.length; index++) {
      String arg = args[index];
      switch (arg) {
        case "--gateway-uri" -> gatewayUri = requireArgValue(args, ++index, arg);
        case "--registration-token" -> registrationToken = requireArgValue(args, ++index, arg);
        case "--heartbeat" -> heartbeat = requireArgValue(args, ++index, arg);
        case "--reconnect-initial" -> reconnectInitial = requireArgValue(args, ++index, arg);
        case "--reconnect-max" -> reconnectMax = requireArgValue(args, ++index, arg);
        case "--tool-timeout" -> toolTimeout = requireArgValue(args, ++index, arg);
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
        case "--data-dir" -> {
          if (dataDir != null) {
            throw new IllegalArgumentException("--data-dir may only be specified once");
          }
          dataDir = requireArgValue(args, ++index, arg);
        }
        default -> throw new IllegalArgumentException("unknown argument: " + arg);
      }
    }

    return new DaemonConfig(
        URI.create(requirePresent(gatewayUri, "gateway-uri")),
        requirePresent(registrationToken, "registration-token"),
        parseDuration(heartbeat, Duration.ofSeconds(15)),
        parseDuration(reconnectInitial, Duration.ofSeconds(1)),
        parseDuration(reconnectMax, Duration.ofSeconds(30)),
        parseDuration(toolTimeout, Duration.ofMinutes(5)),
        note,
        environmentRoot == null ? defaultEnvironmentRoot() : Path.of(environmentRoot),
        Path.of(requirePresent(dataDir, "data-dir")));
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

  /** 数据目录必须显式绝对；不必预先存在，Daemon 会创建它。 */
  private static Path requireAbsoluteDirectory(Path value) {
    Path path = Objects.requireNonNull(value, "dataDir");
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException("dataDir must be an absolute path");
    }
    return path.normalize();
  }
}
