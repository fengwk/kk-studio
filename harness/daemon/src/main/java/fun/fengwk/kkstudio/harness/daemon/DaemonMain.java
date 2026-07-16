package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.daemon.coding.ArtifactSource;
import fun.fengwk.kkstudio.harness.daemon.coding.CodingTools;
import fun.fengwk.kkstudio.harness.daemon.coding.CodingToolsConfig;

/** Environment Daemon 独立进程入口。 */
public final class DaemonMain {

  private DaemonMain() {}

  /** 使用连接属性及可选的 {@code kkstudio.daemon.environment-root} 启动带本地 coding tools 的 Daemon。 */
  public static void main(String[] args) throws InterruptedException {
    CodingToolsConfig config = CodingToolsConfig.fromSystemProperties();
    DaemonToolRegistry toolRegistry = new DaemonToolRegistry();
    CodingTools.registerAll(toolRegistry, config);
    ArtifactSource artifactSource =
        config.artifactSink() instanceof ArtifactSource source ? source : null;
    DaemonRuntime runtime =
        artifactSource == null
            ? new DaemonRuntime(DaemonConfig.fromSystemProperties(), toolRegistry)
            : new DaemonRuntime(DaemonConfig.fromSystemProperties(), toolRegistry, artifactSource);
    Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "daemon-shutdown"));
    runtime.start();
    Thread.currentThread().join();
  }
}
