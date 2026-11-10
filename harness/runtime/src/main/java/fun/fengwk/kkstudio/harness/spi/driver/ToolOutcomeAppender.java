package fun.fengwk.kkstudio.harness.spi.driver;

import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.codec.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 把一个 terminal Tool invocation 的稳定 durable outcome 原子追加到 branch。
 *
 * <p>返回的 {@code headEntryId} 即 ToolResult Entry。本 appender 绝不 attach/更新 ToolInvocation 行，也绝不消费
 * upload：资源已在单工具 terminal 事务内转移为 Session 引用，此处只做稳定内容到 Entry 的纯映射。调用方在同一事务完成 Entry append
 * 后物理删除工具子行并通知 Driver 清理私有 generation 状态。
 */
public final class ToolOutcomeAppender {

  private static final HistoryPayloadMapper PAYLOAD_MAPPER = new HistoryPayloadMapper();

  private ToolOutcomeAppender() {}

  public static Applied append(
      HarnessStore.Transaction tx,
      UUID sessionId,
      UUID parentEntryId,
      ToolInvocation invocation,
      Instant now) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(invocation, "invocation");
    Objects.requireNonNull(now, "now");
    if (!invocation.status().isTerminal()) {
      throw new IllegalArgumentException("tool outcome append requires a terminal invocation");
    }
    MessagePayload payload = PAYLOAD_MAPPER.toolResultPayload(invocation);
    UUID headEntryId = tx.nextId();
    tx.entries().insert(new Entry(headEntryId, sessionId, parentEntryId, payload, now));
    return new Applied(headEntryId);
  }

  /** append 操作的返回值：包含追加后最新的 head entry id。 */
  public record Applied(UUID headEntryId) {
    public Applied {
      Objects.requireNonNull(headEntryId, "headEntryId");
    }
  }
}
