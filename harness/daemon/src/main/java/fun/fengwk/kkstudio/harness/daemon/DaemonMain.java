package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.daemon.coding.CodingToolsConfig;

import java.io.PrintStream;
import java.util.Objects;
import java.util.regex.Pattern;

/** Environment Daemon 独立进程入口。 */
public final class DaemonMain {

  /** 预检失败时 stderr 的稳定前缀；installer 只应解析该 marker 之后的安全诊断。 */
  static final String INVALID_CONFIGURATION_MARKER = "Invalid daemon configuration";

  /** 未知失败收敛后的固定规则，不含任何输入取值。 */
  static final String INVALID_CONFIGURATION_FALLBACK = "configuration is invalid";

  /** 共享 codec 与已知 bash 失败的安全形态：字段路径 + 规则，不含原始取值。 */
  private static final Pattern SAFE_CONFIGURATION_ERROR =
      Pattern.compile("daemon(?:\\.[A-Za-z0-9_.\\[\\]-]+)?: [^\\r\\n]+");

  /** {@code --help} / {@code -h} 的完整用法文本：列出全部 CLI 选项及其默认值。 */
  static final String USAGE =
      """
      Usage: java -jar kk-studio-daemon.jar --config <absolute-path>

      Configuration:
        --config <absolute-path>         Load daemon JSON (studioUrl, note, bashExecutable, lsp).
                                         The sibling daemon.token holds the owner-only token;
                                         the configuration parent is the runtime data directory.
        --check-config <absolute-path>   Validate configuration, sibling token and resolved bash
                                         executable, then exit. No data creation, locking,
                                         connections, process spawning or LSP startup.
                                         Managed installation uses ~/.kk-studio/daemon.json.

      Local proxy:
        http_proxy / https_proxy         Independent HTTP proxy URLs (http://host:port);
                                         uppercase variants supported, lowercase wins.
        no_proxy                        Comma-separated bypass hosts/domains/IPs; applies
                                         to environment and OS proxies. Empty proxy means
                                         DIRECT; unset proxy falls back to the JDK OS selector.
                                         JVM java.net.useSystemProxies defaults to true.

      Information:
        --help, -h                       Print this help and exit
        --version                        Print the daemon version and exit

      Machine launch:
        --base64-args <tokens...>         First argument only: each token is one application
                                         argument encoded as UTF-8 Base64 (not encryption).
                                         Decoded arguments follow the same CLI rules.

      Runtime heartbeat/reconnect intervals are fixed at PT15S/PT1S/PT30S.
      The default bash executable is bash. Unknown or extra arguments fail closed.
      """;

  private DaemonMain() {}

  /**
   * 使用 CLI 参数启动带本地 coding capabilities 的 Daemon。
   *
   * <p>唯一运行参数是 {@code --config}。HELLO 携带按需读取的同目录凭证； 若注册凭证被拒绝，握手以终态错误结束，daemon 停止重连并以非零状态退出。
   *
   * <p>数据目录在启动期以 owner-only 权限创建并持有 {@code daemon.lock}：同一目录上的第二个 Daemon
   * 立即失败，而不是并发写同一份本地数据；该锁在进程整个生命周期内持有。
   *
   * <p>{@code --check-config} 只读取并校验配置、同目录凭证与配置的 bash 可执行程序，不打开数据目录、不建立连接也不启动进程； 失败时只在 stderr
   * 输出单行安全诊断（固定前缀 + 字段路径/规则或固定规则）并以非零状态退出， 正常运行前同样先完成同一解析，因此缺失或不可执行的 bash 在启动期即失败关闭。
   *
   * <p>单个 {@code --help}/{@code -h} 或 {@code --version} 是纯信息命令：在打开数据目录或建立连接之前输出并直接返回；其余情况（含
   * 混用与多余参数）一律交给 {@link DaemonConfig#fromArgs(String[])} 解析并失败关闭。
   */
  public static void main(String[] args) throws InterruptedException {
    args = DaemonArguments.decode(args);
    if (printInfoCommand(args, System.out)) {
      return;
    }
    if (args.length > 0 && "--check-config".equals(args[0])) {
      if (!checkConfig(args, System.out, System.err)) {
        System.exit(1);
      }
      return;
    }
    DaemonConfig daemonConfig = DaemonConfig.fromArgs(args);
    DaemonProxyInitializer.install(System.getenv());
    try (DaemonDataDirectory dataDirectory = DaemonDataDirectory.open(daemonConfig.dataDir())) {
      CodingToolsConfig toolsConfig =
          CodingToolsConfig.fromRuntime(
              dataDirectory.resources(), daemonConfig.bashExecutable(), daemonConfig.lsp());
      DaemonRuntime runtime = DaemonRuntime.create(daemonConfig, toolsConfig, dataDirectory);
      Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "daemon-shutdown"));
      runtime.start();
      DaemonRuntimeState finalState = runtime.awaitTermination();
      if (finalState == DaemonRuntimeState.FAILED) {
        System.err.println("daemon failed: " + runtime.failureReason());
        System.exit(1);
      }
    }
  }

  /**
   * 运行配置预检并把结果写入调用方提供的输出流：成功打印固定成功文本，失败打印单行安全诊断。
   *
   * <p>本方法不调用 {@code System.exit}，便于测试注入输出流；进程退出码由 {@link #main(String[])} 承担。
   *
   * @return {@code true} 表示预检通过
   */
  static boolean checkConfig(String[] args, PrintStream out, PrintStream err) {
    Objects.requireNonNull(args, "args");
    Objects.requireNonNull(out, "out");
    Objects.requireNonNull(err, "err");
    try {
      if (args.length != 2) {
        throw new IllegalArgumentException("expected exactly --check-config <absolute-path>");
      }
      DaemonConfig checked = DaemonConfig.fromFile(DaemonConfig.configPath(args[1]));
      checked.registrationToken();
      out.println("Daemon configuration is valid");
      return true;
    } catch (RuntimeException error) {
      err.println(invalidConfigurationLine(error));
      return false;
    }
  }

  /**
   * 把预检失败映射为单行安全诊断。
   *
   * <p>共享 codec 与已知 bash 失败保留字段路径与规则；其余异常一律收敛为固定规则。输出不含堆栈、原始配置取值或换行， 供 installer 只解析 {@link
   * #INVALID_CONFIGURATION_MARKER} 之后的内容。
   */
  static String invalidConfigurationLine(RuntimeException error) {
    Objects.requireNonNull(error, "error");
    String message = error.getMessage();
    if (message != null && SAFE_CONFIGURATION_ERROR.matcher(message).matches()) {
      return INVALID_CONFIGURATION_MARKER + ": " + message;
    }
    return INVALID_CONFIGURATION_MARKER + ": " + INVALID_CONFIGURATION_FALLBACK;
  }

  /**
   * 处理唯一的信息命令。
   *
   * <p>只有恰好一个参数且该参数是 {@code --help}/{@code -h}/{@code --version} 时才算信息命令：这样 {@code --help <其它参数>}
   * 之类的输入仍然走 {@link DaemonConfig} 并失败关闭，不会被误当成合法调用。输出目标由调用方注入，测试无需捕获进程全局 stdout。
   *
   * @return {@code true} 表示已输出信息命令且进程应立即以状态 0 退出
   */
  static boolean printInfoCommand(String[] args, PrintStream out) {
    Objects.requireNonNull(args, "args");
    Objects.requireNonNull(out, "out");
    if (args.length != 1) {
      return false;
    }
    switch (args[0]) {
      case "--help", "-h" -> out.print(USAGE);
      case "--version" -> out.println("kk-studio-daemon " + implementationVersion());
      default -> {
        return false;
      }
    }
    return true;
  }

  /**
   * Daemon 版本：shaded JAR 的 manifest {@code Implementation-Version}；未打包（直接跑 classes/测试）时为 {@code
   * development}。
   */
  static String implementationVersion() {
    String version = DaemonMain.class.getPackage().getImplementationVersion();
    return version == null || version.isBlank() ? "development" : version;
  }
}
