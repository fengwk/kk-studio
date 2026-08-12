package fun.fengwk.kkstudio.harness.runtime.port;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.List;
import java.util.UUID;

/**
 * terminal ToolResult 的 durable history 物化端口：在 Tool outcome Entry 插入前、同一 store 事务内调用，把瞬时 Resource
 * 引用内容（data/file/http/https/s3 等临时 URI）外部化为全局 Blob 存储引用并返回 durable 内容列表。
 *
 * <p>实现必须保持无副作用失败语义：任何一步失败都让调用方事务回滚，绝不写入部分 history。实现不得在返回值之外持久化任何 URL / URI / ResourceStore
 * 引用——持久化 message 只能携带 {@code ResourceMessageContent(blobId, name, preview)}。未注入实现时 （null），Runtime
 * 对含 Resource 引用的 ToolResult 保持 fail-closed（不可表示即拒绝），瞬时 Tool 内容可以继续以原形态 存在于 invocation result 中。
 */
public interface ToolResultHistoryMaterializer {

  /**
   * 事务内把 {@link ToolResult} 的全部 content 映射为 durable {@link AgentMessageContent} 列表（与输入 content
   * 一一对应，顺序不变）：Text/Json 原样映射，Resource 内容物化为 blob-backed 内容。
   *
   * @return 非空列表（调用方在为空时回退为空文本，与无物化路径一致）
   * @throws IllegalArgumentException 无法解析/外部化的确定性输入（含未支持的 URI scheme），调用方事务回滚
   */
  List<AgentMessageContent> materialize(UUID sessionId, ToolResult result);
}
