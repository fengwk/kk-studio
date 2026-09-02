package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.daemon.coding.CodingToolsConfig;
import fun.fengwk.kkstudio.harness.daemon.skill.DaemonSkillRegistry;

/** Environment Daemon 独立进程入口。 */
public final class DaemonMain {

  private DaemonMain() {}

  /**
   * 使用 CLI 参数启动带本地 coding capabilities 与 skill 发现的 Daemon。
   *
   * <p>权威参数：{@code --registration-token}、可选且唯一 {@code --note}、唯一 {@code --environment-root}、可重复
   * {@code --skill-dir}；连接参数见 {@link DaemonConfig#fromArgs(String[])}。 HELLO 携带 {@code
   * --registration-token} 认证；若注册凭证被拒绝，握手以终态错误结束，daemon 停止重连并以非零状态退出。
   */
  public static void main(String[] args) throws InterruptedException {
    DaemonConfig daemonConfig = DaemonConfig.fromArgs(args);
    CodingToolsConfig toolsConfig =
        CodingToolsConfig.fromSystemProperties(daemonConfig.environmentRoot());
    DaemonSkillRegistry skillRegistry = DaemonSkillRegistry.discover(daemonConfig.skillDirs());
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
