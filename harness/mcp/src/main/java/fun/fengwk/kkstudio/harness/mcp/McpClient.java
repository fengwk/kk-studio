package fun.fengwk.kkstudio.harness.mcp;

import java.util.List;

/**
 * 跨 Platform/Daemon 复用的 MCP client 接口。
 *
 * <p>屏蔽底层 LangChain4j 类型，提供工具发现、工具调用及生命周期关闭。
 *
 * <p>所有操作都要求调用方显式提供总预算 deadline 与调用级取消令牌：库不提供无预算的隐式默认，避免出现无法限时的生产调用。
 */
public interface McpClient extends AutoCloseable {

  /**
   * 在给定的 operation deadline 限制内列出工具规格。
   *
   * @param deadline 操作截止时间（覆盖初始化之后剩余的总预算）
   * @param token 本次调用的取消令牌
   */
  List<McpToolDefinition> listTools(McpDeadline deadline, McpCancellationToken token);

  /**
   * 在给定的 operation deadline 限制内调用工具。
   *
   * @param name 工具名称
   * @param argumentsJson 参数 JSON 文本
   * @param deadline 操作截止时间（覆盖初始化之后剩余的总预算）
   * @param token 本次调用的取消令牌
   */
  McpToolCallResult callTool(
      String name, String argumentsJson, McpDeadline deadline, McpCancellationToken token);

  /** 关闭底层连接与进程，安全幂等。 */
  @Override
  void close();
}
