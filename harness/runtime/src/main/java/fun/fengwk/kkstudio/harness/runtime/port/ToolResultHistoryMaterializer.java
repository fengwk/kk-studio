package fun.fengwk.kkstudio.harness.runtime.port;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.List;

/**
 * terminal ToolResult 的 durable history 物化端口：在 Tool outcome Entry 插入前、同一 store 事务内调用，把 Platform
 * ResourceStore 拥有的瞬时 Resource 外部化为全局 Blob 存储引用并返回 durable 内容列表。
 *
 * <p>实现只能通过受控 ResourceStore 读取其拥有的引用，不得自行解析或访问任意 data/file/http/https/s3 URI。任何一步失败都让调用方事务回滚，
 * 绝不写入部分 history；持久化 message 只能携带 durable {@code ResourceMessageContent}。
 */
public interface ToolResultHistoryMaterializer {

  /**
   * 事务内把 {@link ToolResult} 的全部 content 映射为 durable {@link AgentMessageContent} 列表（与输入 content
   * 一一对应，顺序不变）：Text/Json 原样映射，Resource 内容物化为 blob-backed 内容。
   *
   * @param context 物化上下文（包含 sessionId, threadId, invocationId, toolName）
   * @param result 终态工具结果
   * @return 非空列表（调用方在为空时回退为空文本，与无物化路径一致）
   * @throws IllegalArgumentException 引用不受 ResourceStore 管理、完整性校验失败或内容不可外部化
   */
  List<AgentMessageContent> materialize(ToolResultHistoryContext context, ToolResult result);
}
