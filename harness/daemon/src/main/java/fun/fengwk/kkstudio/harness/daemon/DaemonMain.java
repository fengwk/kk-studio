package fun.fengwk.kkstudio.harness.daemon;

import fun.fengwk.kkstudio.harness.daemon.coding.CodingToolsConfig;
import fun.fengwk.kkstudio.harness.daemon.mcp.McpConfig;
import fun.fengwk.kkstudio.harness.daemon.mcp.McpConfigParser;
import fun.fengwk.kkstudio.harness.daemon.mcp.McpServerRegistry;
import fun.fengwk.kkstudio.harness.daemon.mcp.langchain.LangChainMcpClientFactory;
import fun.fengwk.kkstudio.harness.daemon.skill.DaemonSkillRegistry;

/** Environment Daemon 独立进程入口。 */
public final class DaemonMain {

  private DaemonMain() {}

  /**
   * 使用 CLI 参数启动带本地 coding capabilities、skill 发现与 MCP server 的 Daemon。
   *
   * <p>权威参数：{@code --environment-name}、可选且唯一 {@code --note}、唯一 {@code --environment-root}、可重复
   * {@code --skill-dir} 与可选 {@code --mcp-config}；连接参数见 {@link
   * DaemonConfig#fromArgs(String[])}。{@code --environment-name} 是 canonical 逻辑路由身份，HELLO
   * 声称该名称；若该名称已被另一个 live daemon 持有，握手会以终态冲突错误结束，daemon 停止重连并以非零状态退出。
   */
  public static void main(String[] args) throws InterruptedException {
    DaemonConfig daemonConfig = DaemonConfig.fromArgs(args);
    CodingToolsConfig toolsConfig =
        CodingToolsConfig.fromSystemProperties(daemonConfig.environmentRoot());
    DaemonSkillRegistry skillRegistry = DaemonSkillRegistry.discover(daemonConfig.skillDirs());
    McpConfig mcpConfig =
        daemonConfig.mcpConfigPath() == null
            ? McpConfig.empty()
            : McpConfigParser.parse(daemonConfig.mcpConfigPath());
    // 每个配置的 server 独立初始化：单个失败只记为 FAILED，不影响 coding capabilities/skills 启动。
    McpServerRegistry mcpRegistry =
        new McpServerRegistry(
            mcpConfig, new LangChainMcpClientFactory(), daemonConfig.defaultToolTimeout());
    mcpRegistry.start();
    DaemonRuntime runtime =
        DaemonRuntime.create(daemonConfig, toolsConfig, skillRegistry, mcpRegistry);
    Runtime.getRuntime().addShutdownHook(new Thread(runtime::close, "daemon-shutdown"));
    runtime.start();
    DaemonRuntimeState finalState = runtime.awaitTermination();
    if (finalState == DaemonRuntimeState.FAILED) {
      System.err.println("daemon failed: " + runtime.failureReason());
      System.exit(1);
    }
  }
}
