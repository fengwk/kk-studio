package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.daemon.coding.CodingToolsConfig;

import java.io.PrintStream;
import java.util.Objects;

/** Environment Daemon 独立进程入口。 */
public final class DaemonMain {

  /** {@code --help} / {@code -h} 的完整用法文本：列出全部 CLI 选项及其默认值。 */
  static final String USAGE =
      """
      Usage: java -jar kk-studio-daemon.jar [options]

      Connection:
        --gateway-uri <uri>              Environment server WebSocket gateway; scheme must be ws
                                         or wss (required)
        --registration-token-file <path> Absolute owner-only file holding the registration
                                         token; the token text is never passed in argv (required)
        --heartbeat <duration>           Heartbeat interval (default: PT15S)
        --reconnect-initial <duration>   Initial reconnect backoff (default: PT1S)
        --reconnect-max <duration>       Maximum reconnect backoff (default: PT30S)

      Host identity:
        --note <text>                    Single-line note shown to the model; at most once
        --data-dir <path>                Absolute daemon data directory holding the process lock
                                         and published text output
                                         (default: ~/.kk-studio)

      Local executables:
        --bash-executable <path>         bash executable for process.exec (default: bash)
        --lsp-bridge-command <command>   LSP bridge command (default: disabled when omitted)
        --javap-executable <path>        javap executable for class decompilation
                                         (default: javap)

      Information:
        --help, -h                       Print this help and exit
        --version                        Print the daemon version and exit

      Durations use ISO-8601 form (for example PT30S or PT5M). Unknown arguments fail closed.
      """;

  private DaemonMain() {}

  /**
   * 使用 CLI 参数启动带本地 coding capabilities 的 Daemon。
   *
   * <p>权威参数：{@code --registration-token-file}、可选且唯一 {@code --note}、可选 {@code --data-dir}
   * 与三个可选的本地执行程序参数；连接参数见 {@link DaemonConfig#fromArgs(String[])}。HELLO 携带从 {@code
   * --registration-token-file} 按需读取的凭证；若注册凭证被拒绝， 握手以终态错误结束，daemon 停止重连并以非零状态退出。
   *
   * <p>数据目录在启动期以 owner-only 权限创建并持有 {@code daemon.lock}：同一目录上的第二个 Daemon
   * 立即失败，而不是并发写同一份本地数据；该锁在进程整个生命周期内持有。
   *
   * <p>单个 {@code --help}/{@code -h} 或 {@code --version} 是纯信息命令：在打开数据目录或建立连接之前输出并直接返回；其余情况（含
   * 混用与多余参数）一律交给 {@link DaemonConfig#fromArgs(String[])} 解析并失败关闭。
   */
  public static void main(String[] args) throws InterruptedException {
    Objects.requireNonNull(args, "args");
    if (printInfoCommand(args, System.out)) {
      return;
    }
    DaemonConfig daemonConfig = DaemonConfig.fromArgs(args);
    try (DaemonDataDirectory dataDirectory = DaemonDataDirectory.open(daemonConfig.dataDir())) {
      CodingToolsConfig toolsConfig =
          CodingToolsConfig.fromCli(
              dataDirectory.resources(),
              daemonConfig.bashExecutable(),
              daemonConfig.lspBridgeCommand(),
              daemonConfig.javapExecutable());
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
