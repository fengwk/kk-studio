package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.daemon.coding.ArtifactSource;
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
   * DaemonConfig#fromArgs(String[])}。
   */
  public static void main(String[] args) throws InterruptedException {
    DaemonConfig daemonConfig = DaemonConfig.fromArgs(args);
    CodingToolsConfig toolsConfig = CodingToolsConfig.fromSystemProperties();
    DaemonToolRegistry toolRegistry = new DaemonToolRegistry();
    CodingTools.registerAll(toolRegistry, toolsConfig);
    DaemonSkillRegistry skillRegistry = DaemonSkillRegistry.discover(daemonConfig.skillDirs());
    ArtifactSource artifactSource =
        toolsConfig.artifactSink() instanceof ArtifactSource source ? source : null;
    DaemonRuntime runtime =
        artifactSource == null
            ? new DaemonRuntime(daemonConfig, toolRegistry, skillRegistry)
            : new DaemonRuntime(daemonConfig, toolRegistry, skillRegistry, artifactSource);
    Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "daemon-shutdown"));
    runtime.start();
    Thread.currentThread().join();
  }
}
