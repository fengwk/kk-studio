package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Daemon 独立进程的连接与本地执行配置。
 *
 * <p>连接、身份、说明、本地执行程序与数据目录的唯一配置来源是 CLI：{@code --registration-token-file}、gateway 连接参数、可选且唯一 {@code
 * --note}、可选 {@code --data-dir} 与三个可选的本地执行程序 {@code --bash-executable}、{@code
 * --lsp-bridge-command}、 {@code --javap-executable}。已删除的 {@code --tool-timeout} 作为未知参数 fail
 * closed：执行超时只由 Tool definition 默认值与调用显式值决定。
 *
 * <p>{@code --registration-token-file} 指向 owner-only 普通文件：凭证文本只存在于该文件，进程参数、环境变量与日志都不携带它；本 record
 * 只保存路径，因此 {@code equals}/{@code hashCode}/{@code toString} 不会扩散凭证。已删除的 {@code
 * --registration-token} 作为未知参数 fail closed，不提供兼容回退。
 *
 * <p>{@code --data-dir} 承载本地大文本输出与 daemon 进程锁；省略时为 {@link #defaultDataDir()}。二进制结果不落本地 Resource
 * 仓库，而是由 Daemon 直传对象存储。Daemon 不配置也不持有 Environment UUID，连接建立后由 Gateway 在 WELCOME 消息中下发。
 *
 * <p>{@code --note} 会进入受信任的模型 SYSTEM Prompt，只能由可信操作者设置，禁止放入凭证、秘密或不可信外部文本。
 */
public record DaemonConfig(
    URI gatewayUri,
    Path registrationTokenFile,
    Duration heartbeatInterval,
    Duration initialReconnectDelay,
    Duration maxReconnectDelay,
    String note,
    Path dataDir,
    String bashExecutable,
    String lspBridgeCommand,
    String javapExecutable) {

  /** 未显式配置时的 bash 可执行文件。 */
  public static final String DEFAULT_BASH_EXECUTABLE = "bash";

  /** 未显式配置时的 javap 可执行文件。 */
  public static final String DEFAULT_JAVAP_EXECUTABLE = "javap";

  public DaemonConfig {
    gatewayUri = Objects.requireNonNull(gatewayUri, "gatewayUri");
    if (!"ws".equals(gatewayUri.getScheme()) && !"wss".equals(gatewayUri.getScheme())) {
      throw new IllegalArgumentException("gatewayUri must use ws or wss");
    }
    registrationTokenFile = DaemonTokenFile.validate(registrationTokenFile);
    heartbeatInterval = requirePositive(heartbeatInterval, "heartbeatInterval");
    initialReconnectDelay = requireNonNegative(initialReconnectDelay, "initialReconnectDelay");
    maxReconnectDelay = requirePositive(maxReconnectDelay, "maxReconnectDelay");
    if (initialReconnectDelay.compareTo(maxReconnectDelay) > 0) {
      throw new IllegalArgumentException("initialReconnectDelay must not exceed maxReconnectDelay");
    }
    note = note == null ? null : DaemonEnvironmentInfo.validateNote(note);
    dataDir = requireAbsoluteDirectory(dataDir);
    bashExecutable = blankToDefault(bashExecutable, DEFAULT_BASH_EXECUTABLE, "bashExecutable");
    lspBridgeCommand = blankToNull(lspBridgeCommand);
    javapExecutable = blankToDefault(javapExecutable, DEFAULT_JAVAP_EXECUTABLE, "javapExecutable");
  }

  /** 便捷构造器：本地执行程序使用默认值，LSP bridge 处于禁用状态。 */
  public DaemonConfig(
      URI gatewayUri,
      Path registrationTokenFile,
      Duration heartbeatInterval,
      Duration initialReconnectDelay,
      Duration maxReconnectDelay,
      String note,
      Path dataDir) {
    this(
        gatewayUri,
        registrationTokenFile,
        heartbeatInterval,
        initialReconnectDelay,
        maxReconnectDelay,
        note,
        dataDir,
        DEFAULT_BASH_EXECUTABLE,
        null,
        DEFAULT_JAVAP_EXECUTABLE);
  }

  /** 解析 CLI 参数。{@code --data-dir} 可省略并回退到 {@link #defaultDataDir()}；任何未声明参数都失败。 */
  public static DaemonConfig fromArgs(String[] args) {
    Objects.requireNonNull(args, "args");
    String gatewayUri = null;
    String registrationTokenFile = null;
    String heartbeat = null;
    String reconnectInitial = null;
    String reconnectMax = null;
    String note = null;
    String dataDir = null;
    String bashExecutable = null;
    String lspBridgeCommand = null;
    String javapExecutable = null;

    for (int index = 0; index < args.length; index++) {
      String arg = args[index];
      switch (arg) {
        case "--gateway-uri" -> gatewayUri = requireArgValue(args, ++index, arg);
        case "--registration-token-file" -> {
          if (registrationTokenFile != null) {
            throw new IllegalArgumentException(
                "--registration-token-file may only be specified once");
          }
          registrationTokenFile = requireArgValue(args, ++index, arg);
        }
        case "--heartbeat" -> heartbeat = requireArgValue(args, ++index, arg);
        case "--reconnect-initial" -> reconnectInitial = requireArgValue(args, ++index, arg);
        case "--reconnect-max" -> reconnectMax = requireArgValue(args, ++index, arg);
        case "--bash-executable" -> bashExecutable = requireArgValue(args, ++index, arg);
        case "--lsp-bridge-command" -> lspBridgeCommand = requireArgValue(args, ++index, arg);
        case "--javap-executable" -> javapExecutable = requireArgValue(args, ++index, arg);
        case "--note" -> {
          if (note != null) {
            throw new IllegalArgumentException("--note may only be specified once");
          }
          note = requireArgValue(args, ++index, arg);
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
        Path.of(requirePresent(registrationTokenFile, "registration-token-file")),
        parseDuration(heartbeat, Duration.ofSeconds(15)),
        parseDuration(reconnectInitial, Duration.ofSeconds(1)),
        parseDuration(reconnectMax, Duration.ofSeconds(30)),
        note,
        dataDir == null ? defaultDataDir() : Path.of(dataDir),
        bashExecutable,
        lspBridgeCommand,
        javapExecutable);
  }

  /** 默认数据目录：启动用户 HOME 下的 {@code .kk-studio}。 */
  public static Path defaultDataDir() {
    return DaemonDataDirectory.defaultRoot();
  }

  /**
   * 按需读取注册凭证文本。凭证不缓存在字段中，因此不会随 record 的 {@code equals}/{@code hashCode}/{@code toString}
   * 扩散到日志或诊断输出。
   *
   * @throws IllegalStateException 文件被删除、不可读或为空
   */
  public String registrationToken() {
    return DaemonTokenFile.read(registrationTokenFile);
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

  private static String blankToDefault(String value, String defaultValue, String name) {
    String normalized = blankToNull(value);
    return normalized == null ? requireNonBlank(defaultValue, name) : normalized;
  }

  private static String requireNonBlank(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " must not be blank");
    }
    return value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /** 数据目录可省略，但一旦显式给出就必须绝对；不必预先存在，Daemon 会创建它。 */
  private static Path requireAbsoluteDirectory(Path value) {
    Path path = Objects.requireNonNull(value, "dataDir");
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException("dataDir must be an absolute path");
    }
    return path.normalize();
  }
}
