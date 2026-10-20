package fun.fengwk.kkstudio.harness.runtime.port;

import java.util.UUID;

/**
 * 从 realtime payload 的 origin 节点回拉一条 canonical event JSON 的窄端口。
 *
 * <p>只有 PostgreSQL NOTIFY 承载不下的 oversize event 才走该端口：参考信封只携带 origin、Thread 与 payload id，不携带 URL，
 * 也不携带事件内容。实现必须从可信的 {@code cluster_node} 租约解析 origin，并使用内部认证；失败不重试，直接抛 {@link
 * fun.fengwk.kkstudio.harness.runtime.realtime.RealtimePayloadUnavailableException}。调用方在失败时只触发该
 * Thread 的 RESYNC，绝不把缺失 payload 伪装成完整事件。
 */
@FunctionalInterface
public interface RealtimePayloadFetcher {

  /**
   * 回拉指定 payload。
   *
   * @param originNodeId 事件产生节点实例 UUID
   * @param threadId 参考信封声明的归属 Thread
   * @param payloadId origin 短期 buffer 中的 payload UUID
   * @return canonical {@code RealtimeEvent} JSON
   */
  String fetch(UUID originNodeId, UUID threadId, UUID payloadId);
}
