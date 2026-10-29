package fun.fengwk.kkstudio.harness.runtime.port;

import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.util.UUID;

/**
 * 单工具 terminal 的稳定 outcome 物化端口：在同一 store 事务内校验并消费已上传的瞬时 {@code blob-upload} 引用，返回可直接持久化、 也可直接写入
 * Entry 的稳定 {@link ToolResult}。
 *
 * <p>返回结果的 Text/JSON 内容保持不变；Resource 必须改为 Session 已持有或已转移的 {@code kkstudio:/resources/<blobId>}
 * 引用，并携带权威文件名与 blob 事实。实现不得自行访问 data/http/https URI。任何确定性拒绝抛 {@link
 * ToolOutcomeMaterializationException}；数据库或存储基础设施失败必须原样传播，由调用方等待恢复。
 */
public interface ToolResultHistoryMaterializer {

  /**
   * 事务内把终态工具结果转换为稳定 outcome。
   *
   * @param sessionId 结果归属的 Session
   * @param toolName 工具名称
   * @param result Gateway 已终态化的工具结果
   * @return 稳定结果；所有 Resource 已指向 Session 持有的 Blob
   * @throws ToolOutcomeMaterializationException 引用不可稳定表达
   */
  ToolResult materialize(UUID sessionId, String toolName, ToolResult result);
}
