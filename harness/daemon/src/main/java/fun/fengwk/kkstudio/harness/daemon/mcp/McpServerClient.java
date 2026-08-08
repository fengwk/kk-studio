package fun.fengwk.kkstudio.harness.daemon.mcp;

import java.util.List;

/**
 * Daemon 本地 MCP server client 端口。
 *
 * <p>屏蔽 LangChain4j 类型：registry 与固定桥接工具只依赖本端口与自有 records；LangChain 适配器实现本端口。
 */
public interface McpServerClient {

  String name();

  /** 返回 READY server 的工具摘要（含完整输入 schema JSON）；结果可缓存。 */
  List<McpToolSpec> listTools();

  /** 调用指定工具；未知工具/上游错误以 {@link McpCallOutcome#isError()} 表达，不抛异常。 */
  McpCallOutcome call(McpToolRequest request);

  /** 关闭底层 client；调用方保证每个 client 恰好关闭一次。 */
  void close();
}
