package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 把一个 terminal Tool invocation 的 durable effects 与 ToolResult 原子追加到 branch。
 *
 * <p>SUCCEEDED 的 CUSTOM effects 按冻结顺序位于 ToolResult 之前；{@code resultEntryId} 永远指向 ToolResult
 * Entry。调用方负责在同一事务更新返回的 invocation 与最终 Thread head。
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
    Objects.requireNonNull(invocation, "invocation");
    Objects.requireNonNull(now, "now");
    if (!invocation.status().isTerminal() || invocation.resultEntryId() != null) {
      throw new IllegalArgumentException(
          "tool outcome append requires an unattached terminal invocation");
    }
    UUID parent = parentEntryId;
    if (invocation.status() == ToolInvocationStatus.SUCCEEDED) {
      for (CustomEntryPayload effect : invocation.effects().customEntries()) {
        UUID effectEntryId = tx.nextId();
        tx.insertEntry(new Entry(effectEntryId, sessionId, parent, effect, now));
        parent = effectEntryId;
      }
    } else if (!invocation.effects().isEmpty()) {
      throw new IllegalStateException("non-succeeded tool invocation must not carry effects");
    }
    UUID resultEntryId = tx.nextId();
    tx.insertEntry(
        new Entry(
            resultEntryId, sessionId, parent, PAYLOAD_MAPPER.toolResultPayload(invocation), now));
    return new Applied(invocation.attachResultEntry(resultEntryId, now), resultEntryId);
  }

  /** 已追加 outcome 的 invocation 与新的 branch head（即 ToolResult Entry）。 */
  public record Applied(ToolInvocation invocation, UUID headEntryId) {}
}
