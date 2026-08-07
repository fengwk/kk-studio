package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.daemon.coding.CodingTools;
import fun.fengwk.kkstudio.harness.daemon.coding.CodingToolsConfig;
import fun.fengwk.kkstudio.harness.daemon.skill.DaemonSkillRegistry;

/** Environment Daemon 独立进程入口。 */
public final class DaemonMain {

  private DaemonMain() {}

  /**
   * 使用 CLI 参数启动带本地 coding tools 与 skill 发现的 Daemon。
   *
   * <p>权威参数：{@code --environment-name}、可重复 {@code --skill-dir}；连接参数见 {@link
   * DaemonConfig#fromArgs(String[])}。{@code --environment-name} 是 canonical 逻辑路由身份，HELLO
   * 声称该名称；若该名称已被 另一个 live daemon 持有，握手会以终态冲突错误结束，daemon 停止重连并以非零状态退出。
   */
  public static void main(String[] args) throws InterruptedException {
    DaemonConfig daemonConfig = DaemonConfig.fromArgs(args);
    CodingToolsConfig toolsConfig = CodingToolsConfig.fromSystemProperties();
    DaemonToolRegistry toolRegistry = new DaemonToolRegistry();
    CodingTools.registerAll(toolRegistry, toolsConfig);
    DaemonSkillRegistry skillRegistry = DaemonSkillRegistry.discover(daemonConfig.skillDirs());
    DaemonRuntime runtime =
        new DaemonRuntime(daemonConfig, toolRegistry, skillRegistry, toolsConfig.resourceStore());
    Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "daemon-shutdown"));
    runtime.start();
    DaemonRuntimeState finalState = runtime.awaitTermination();
    if (finalState == DaemonRuntimeState.FAILED) {
      System.err.println("daemon failed: " + runtime.failureReason());
      System.exit(1);
    }
  }
}
