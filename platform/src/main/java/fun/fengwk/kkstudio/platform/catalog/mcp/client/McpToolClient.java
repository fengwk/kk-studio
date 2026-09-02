package fun.fengwk.kkstudio.platform.catalog.mcp.client;

import java.util.List;

/**
 * Platform 端 MCP server client 端口：屏蔽 LangChain4j 类型，仅暴露发现与调用。
 *
 * <p>实现必须按 Streamable HTTP 传输连接并在构造时完成 initialize + tools/list 握手；调用方保证每个实例恰好 {@link #close()} 一次。
 */
public interface McpToolClient extends AutoCloseable {

  /** 返回远端工具规格（含 source name、description 与 JSON input schema）。 */
  List<McpRemoteToolSpec> listTools();

  /**
   * 调用远端工具并返回文本结果。
   *
   * @param sourceToolName 远端原始工具名
   * @param argumentsJson JSON object 参数文本
   * @param toolCallId 结果 ToolResult 使用的 call id
   */
  McpToolCallOutcome callTool(String sourceToolName, String argumentsJson, String toolCallId);

  /** 关闭底层 client；恰好一次。 */
  @Override
  void close();
}
