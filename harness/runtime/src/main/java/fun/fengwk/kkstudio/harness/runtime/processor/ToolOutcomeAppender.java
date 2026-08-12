package fun.fengwk.kkstudio.harness.runtime.processor;

import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 把一个 terminal Tool invocation 的 durable effects 与 ToolResult 原子追加到 branch。
 *
 * <p>SUCCEEDED 的 CUSTOM effects 按冻结顺序位于 ToolResult 之前；{@code resultEntryId} 永远指向 ToolResult
 * Entry。调用方负责在同一事务更新返回的 invocation 与最终 Thread head。注入 {@link ToolResultHistoryMaterializer} 时（可为
 * null），SUCCEEDED 结果在 Entry 插入前、同一事务内物化为 blob-backed durable 内容：持久化 message 绝不携带 瞬时 Resource URI /
 * ResourceStore 引用。
 */
public final class ToolOutcomeAppender {

  private static final HistoryPayloadMapper PAYLOAD_MAPPER = new HistoryPayloadMapper();

  private ToolOutcomeAppender() {}

  /** 无物化端口的 append：SUCCEEDED 结果含 Resource 引用时 fail-closed（不可表示即拒绝）。 */
  public static Applied append(
      HarnessStore.Transaction tx,
      UUID sessionId,
      UUID parentEntryId,
      ToolInvocation invocation,
      Instant now) {
    return append(tx, sessionId, parentEntryId, invocation, now, null);
  }

  public static Applied append(
      HarnessStore.Transaction tx,
      UUID sessionId,
      UUID parentEntryId,
      ToolInvocation invocation,
      Instant now,
      ToolResultHistoryMaterializer materializer) {
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
    MessagePayload payload;
    if (invocation.status() == ToolInvocationStatus.SUCCEEDED && materializer != null) {
      List<AgentMessageContent> contents = materializer.materialize(sessionId, invocation.result());
      if (contents == null || contents.isEmpty()) {
        contents = List.of(new TextMessageContent(""));
      }
      payload = PAYLOAD_MAPPER.toolResultPayload(invocation, contents);
    } else {
      // 非 SUCCEEDED（FAILED/CANCELLED/UNKNOWN）与无物化端口一律走普通失败/成功 payload 路径，
      // materializer 只在 SUCCEEDED 且注入时被调用。
      payload = PAYLOAD_MAPPER.toolResultPayload(invocation);
    }
    UUID resultEntryId = tx.nextId();
    tx.insertEntry(new Entry(resultEntryId, sessionId, parent, payload, now));
    return new Applied(invocation.attachResultEntry(resultEntryId, now), resultEntryId);
  }

  /** 已追加 outcome 的 invocation 与新的 branch head（即 ToolResult Entry）。 */
  public record Applied(ToolInvocation invocation, UUID headEntryId) {}
}
