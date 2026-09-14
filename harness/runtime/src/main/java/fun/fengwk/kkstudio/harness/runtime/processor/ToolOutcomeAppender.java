package fun.fengwk.kkstudio.harness.runtime.processor;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.common.result.ResourceResultContent;
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
 * <p>SUCCEEDED 的 CUSTOM effects 按冻结顺序位于 ToolResult 之前；返回的 {@code headEntryId} 即 ToolResult Entry。本
 * appender 绝不 attach/更新 ToolInvocation 行：Terminal Tool 行始终表示 outcome 尚未进入 ToolResult
 * Entry，调用方在同一事务完成 Entry append 后物理删除子行与父 ModelInvocation。注入 {@link ToolResultHistoryMaterializer}
 * 时（可为 null），SUCCEEDED 结果在 Entry 插入前、同一事务内物化为 blob-backed durable 内容：持久化 message 绝不携带 瞬时 Resource
 * URI / ResourceStore 引用。
 */
@Slf4j
public final class ToolOutcomeAppender {

  private static final HistoryPayloadMapper PAYLOAD_MAPPER = new HistoryPayloadMapper();

  private ToolOutcomeAppender() {}

  /** 无物化端口的 append：SUCCEEDED Resource 结果安全降级为 metadata-only durable 文本。 */
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
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(invocation, "invocation");
    Objects.requireNonNull(now, "now");
    if (!invocation.status().isTerminal()) {
      throw new IllegalArgumentException("tool outcome append requires a terminal invocation");
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
      List<AgentMessageContent> contents =
          materializer.materialize(sessionId, invocation.call().toolName(), invocation.result());
      if (contents == null || contents.isEmpty()) {
        contents = List.of(new TextMessageContent(""));
      }
      payload = PAYLOAD_MAPPER.toolResultPayload(invocation, contents);
    } else {
      if (invocation.status() == ToolInvocationStatus.SUCCEEDED
          && invocation.result().contents().stream()
              .anyMatch(ResourceResultContent.class::isInstance)) {
        log.warn(
            "ToolResultHistoryMaterializer is unavailable; persisting resource result as"
                + " metadata-only text: sessionId={}, toolCallId={}, toolName={}",
            sessionId,
            invocation.call().id(),
            invocation.call().toolName());
      }
      // 非 SUCCEEDED（FAILED/CANCELLED/UNKNOWN）与无物化端口一律走普通 payload 路径；
      // materializer 只在 SUCCEEDED 且注入时被调用。
      payload = PAYLOAD_MAPPER.toolResultPayload(invocation);
    }
    UUID headEntryId = tx.nextId();
    tx.insertEntry(new Entry(headEntryId, sessionId, parent, payload, now));
    return new Applied(headEntryId);
  }

  /** append 操作的返回值：包含追加后最新的 head entry id。 */
  public record Applied(UUID headEntryId) {
    public Applied {
      Objects.requireNonNull(headEntryId, "headEntryId");
    }
  }
}
