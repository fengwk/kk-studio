package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.daemon.coding.CodingTools;
import fun.fengwk.kkstudio.harness.daemon.coding.CodingToolsConfig;
import fun.fengwk.kkstudio.harness.daemon.identity.EnvironmentIdentity;
import fun.fengwk.kkstudio.harness.daemon.skill.DaemonSkillRegistry;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;

/** Environment Daemon 独立进程入口。 */
public final class DaemonMain {

  private DaemonMain() {}

  /**
   * 使用 CLI 参数启动带本地 coding tools 与 skill 发现的 Daemon。
   *
   * <p>权威参数：{@code --environment-name}、可重复 {@code --skill-dir}；连接参数见 {@link
   * DaemonConfig#fromArgs(String[])}。Environment 身份（canonical UUID）从 {@code
   * CodingToolsConfig.environmentRoot()} 加载/创建后随 runtime 参与每个 envelope 的作用域校验。
   */
  public static void main(String[] args) throws InterruptedException {
    DaemonConfig daemonConfig = DaemonConfig.fromArgs(args);
    CodingToolsConfig toolsConfig = CodingToolsConfig.fromSystemProperties();
    EnvironmentId environmentId = EnvironmentIdentity.loadOrCreate(toolsConfig.environmentRoot());
    DaemonToolRegistry toolRegistry = new DaemonToolRegistry();
    CodingTools.registerAll(toolRegistry, toolsConfig);
    DaemonSkillRegistry skillRegistry = DaemonSkillRegistry.discover(daemonConfig.skillDirs());
    DaemonRuntime runtime =
        new DaemonRuntime(
            daemonConfig, environmentId, toolRegistry, skillRegistry, toolsConfig.resourceStore());
    Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "daemon-shutdown"));
    runtime.start();
    Thread.currentThread().join();
  }
}
