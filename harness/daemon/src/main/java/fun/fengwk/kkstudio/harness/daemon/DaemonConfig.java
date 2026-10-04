package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.daemon.coding.ExecutableResolver;
import fun.fengwk.kkstudio.harness.daemon.coding.LspDiscovery;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.share.ai.environment.DaemonConfiguration;
import fun.fengwk.kkstudio.share.ai.environment.DaemonConfigurationCodec;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;

/**
 * Daemon 独立进程的连接与本地执行配置。
 *
 * <p>唯一外部配置来源是 {@code --config} 指定的 JSON 文件；其父目录是运行数据目录， 同目录的 {@code daemon.token} 是 owner-only
 * 注册凭证文件。本 record 只保存凭证路径， 因此 {@code equals}/{@code hashCode}/{@code toString} 不会扩散凭证。 内联 LSP 经共享
 * codec 校验，不自动安装语言服务器。
 *
 * <p>数据目录承载大文本输出与进程锁。二进制结果由 Daemon 直传对象存储。 Environment UUID 由 Gateway 在 WELCOME 消息中下发。
 *
 * <p>note 会进入受信任的模型 SYSTEM Prompt，只能由可信操作者设置，禁止放入凭证、秘密或不可信外部文本。
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
    LspDiscovery lsp) {

  /** 未显式配置时的 bash 可执行文件。 */
  public static final String DEFAULT_BASH_EXECUTABLE = "bash";

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
    lsp = Objects.requireNonNull(lsp, "lsp");
  }

  /** 便捷构造器：本地执行程序使用默认值，未配置任何 LSP 服务器。 */
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
        LspDiscovery.empty());
  }

  /** 正常运行仅接受唯一的 {@code --config <absolute-path>}。 */
  public static DaemonConfig fromArgs(String[] args) {
    Objects.requireNonNull(args, "args");
    if (args.length != 2 || !"--config".equals(args[0])) {
      throw new IllegalArgumentException("expected exactly --config <absolute-path>");
    }
    return fromFile(configPath(args[1]));
  }

  /**
   * 只读取与校验配置、同目录凭证路径与配置的 bash 可执行程序：不创建目录、锁、连接或启动任何进程。
   *
   * <p>bash 按宿主 PATH/绝对路径只读解析为实际路径，无法解析时立即失败关闭， 避免安装成功后才在执行期暴露缺失。
   */
  static DaemonConfig fromFile(Path file) {
    if (!file.isAbsolute()) {
      throw new IllegalArgumentException("configuration path must be absolute");
    }
    Path normalized = file.normalize();
    DaemonConfiguration configuration = DaemonConfigurationCodec.read(normalized);
    return new DaemonConfig(
        DaemonConfigurationCodec.gatewayUri(configuration.getStudioUrl()),
        normalized.resolveSibling("daemon.token"),
        Duration.ofSeconds(15),
        Duration.ofSeconds(1),
        Duration.ofSeconds(30),
        configuration.getNote(),
        normalized.getParent(),
        resolveBash(configuration.getBashExecutable()),
        LspDiscovery.fromConfiguration(configuration.getLsp()));
  }

  /**
   * 解析配置或默认的 bash 可执行程序为宿主路径。
   *
   * <p>错误只给字段路径与规则，不回显配置取值；解析只做文件系统探测，不启动进程。
   */
  private static String resolveBash(String configuredBash) {
    String command = configuredBash == null ? DEFAULT_BASH_EXECUTABLE : configuredBash;
    return ExecutableResolver.resolve(command)
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "daemon.bashExecutable: must resolve to an executable on PATH or an absolute"
                        + " executable path"));
  }

  static Path configPath(String value) {
    if (value == null || value.isBlank() || value.startsWith("--")) {
      throw new IllegalArgumentException("configuration path must be absolute");
    }
    try {
      Path path = Path.of(value);
      if (path.isAbsolute()) {
        return path;
      }
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("configuration path must be absolute");
    }
    throw new IllegalArgumentException("configuration path must be absolute");
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

  /** 内部运行时数据目录必须绝对；直接构造时不必预先存在，Daemon 会创建它。 */
  private static Path requireAbsoluteDirectory(Path value) {
    Path path = Objects.requireNonNull(value, "dataDir");
    if (!path.isAbsolute()) {
      throw new IllegalArgumentException("dataDir must be an absolute path");
    }
    return path.normalize();
  }
}
