package fun.fengwk.kkstudio.harness.daemon;

/** Environment Daemon 独立进程入口。工具由部署代码在启动前注册。 */
public final class DaemonMain {

  private DaemonMain() {}

  /**
   * 使用 {@code -Dkkstudio.daemon.gateway-uri}、{@code -Dkkstudio.daemon.workspace-id} 和
   * {@code -Dkkstudio.daemon.environment-id} 启动空工具 Daemon。
   */
  public static void main(String[] args) throws InterruptedException {
    DaemonToolRegistry toolRegistry = new DaemonToolRegistry();
    DaemonRuntime runtime = new DaemonRuntime(DaemonConfig.fromSystemProperties(), toolRegistry);
    Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "daemon-shutdown"));
    runtime.start();
    Thread.currentThread().join();
  }
}
