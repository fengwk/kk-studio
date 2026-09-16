package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.daemon.coding.CodingToolsConfig;
import fun.fengwk.kkstudio.harness.daemon.skill.DaemonSkillRegistry;

import java.nio.file.Path;

/** Environment Daemon 独立进程入口。 */
public final class DaemonMain {

  private DaemonMain() {}

  /**
   * 使用 CLI 参数启动带本地 coding capabilities 与持久 Skill 目录的 Daemon。
   *
   * <p>权威参数：{@code --registration-token-file}、可选且唯一 {@code --note}、唯一 {@code --environment-root}、可选
   * {@code --data-dir} 与三个可选的本地执行程序参数；连接参数见 {@link DaemonConfig#fromArgs(String[])}。HELLO 携带从
   * {@code --registration-token-file} 按需读取的凭证；若注册凭证被拒绝， 握手以终态错误结束，daemon 停止重连并以非零状态退出。
   *
   * <p>数据目录在启动期以 owner-only 权限创建并持有 {@code daemon.lock}：同一目录上的第二个 Daemon
   * 立即失败，而不是并发写同一份本地数据；该锁在进程整个生命周期内持有。
   *
   * <p>Skill 来源由 Platform 的受管配置决定：启动只恢复数据目录中上次成功发布的目录，不扫描任何本地默认目录。
   */
  public static void main(String[] args) throws InterruptedException {
    DaemonConfig daemonConfig = DaemonConfig.fromArgs(args);
    try (DaemonDataDirectory dataDirectory = DaemonDataDirectory.open(daemonConfig.dataDir())) {
      CodingToolsConfig toolsConfig =
          CodingToolsConfig.fromCli(
              daemonConfig.environmentRoot(),
              dataDirectory.resources(),
              daemonConfig.bashExecutable(),
              daemonConfig.lspBridgeCommand(),
              daemonConfig.javapExecutable());
      DaemonSkillRegistry skillRegistry =
          DaemonSkillRegistry.open(
              daemonConfig.dataDir(), Path.of(System.getProperty("user.home")));
      DaemonRuntime runtime = DaemonRuntime.create(daemonConfig, toolsConfig, skillRegistry);
      Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "daemon-shutdown"));
      runtime.start();
      DaemonRuntimeState finalState = runtime.awaitTermination();
      if (finalState == DaemonRuntimeState.FAILED) {
        System.err.println("daemon failed: " + runtime.failureReason());
        System.exit(1);
      }
    }
  }
}
