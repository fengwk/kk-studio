package fun.fengwk.kkstudio.harness.daemon.mcp;

import java.time.Duration;

/**
 * MCP client 创建端口。
 *
 * <p>实现必须完成 server 初始化握手；失败抛 {@link RuntimeException} 且内部对已创建的 LangChain client 恰好关闭一次，registry
 * 只负责关闭成功创建的 client。
 */
public interface McpServerClientFactory {

  /** 创建并初始化 {@code config} 对应的 client；{@code defaultTimeout} 在未配置 timeoutSeconds 时生效。 */
  McpServerClient create(McpServerConfig config, Duration defaultTimeout);
}
