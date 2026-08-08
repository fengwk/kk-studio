package fun.fengwk.kkstudio.harness.daemon.mcp.langchain;

import dev.langchain4j.mcp.client.DefaultMcpClient;

import fun.fengwk.kkstudio.harness.daemon.mcp.McpServerClient;
import fun.fengwk.kkstudio.harness.daemon.mcp.McpServerClientFactory;
import fun.fengwk.kkstudio.harness.daemon.mcp.McpServerConfig;

import java.time.Duration;

/** 生产 MCP client 工厂：按配置构建 LangChain4j transport/client 并完成初始化握手。 */
public final class LangChainMcpClientFactory implements McpServerClientFactory {

  @Override
  public McpServerClient create(McpServerConfig config, Duration defaultTimeout) {
    Duration timeout =
        config.timeoutSeconds() == null
            ? defaultTimeout
            : Duration.ofSeconds(config.timeoutSeconds());
    DefaultMcpClient client =
        DefaultMcpClient.builder()
            .transport(LangChainMcpTransportFactory.create(config, timeout))
            .key(config.name())
            .clientName("kk-studio-daemon")
            .initializationTimeout(timeout)
            .toolExecutionTimeout(timeout)
            .autoHealthCheck(false)
            .build();
    LangChainMcpServerClient wrapper = new LangChainMcpServerClient(config.name(), client);
    try {
      // listTools 触发 initialize 握手；失败时内部恰好关闭一次，避免泄漏半初始化 client。
      wrapper.listTools();
    } catch (RuntimeException error) {
      client.close();
      throw error;
    }
    return wrapper;
  }
}
