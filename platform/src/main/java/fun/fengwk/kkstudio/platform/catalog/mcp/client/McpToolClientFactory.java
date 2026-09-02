package fun.fengwk.kkstudio.platform.catalog.mcp.client;

/** 生产 {@link McpToolClient} 工厂端口。 */
public interface McpToolClientFactory {

  /**
   * 按配置创建并握手一个全新 client（Streamable HTTP only）。
   *
   * @throws RuntimeException 连接、握手或工具枚举失败；调用方无需回滚任何资源
   */
  McpToolClient create(McpConnectionSpec spec);
}
