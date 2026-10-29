package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.common.json.StrictJson;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Runtime 唯一的结果读取入口：terminal Invocation 优先，行已删除时按 Session 反查 ToolResult Entry。
 *
 * <p>两条路径都从同一权威 terminal 事实投影，调用方无需感知当前/历史差异。归属校验绝不依赖调用方已鉴权：Invocation 必须通过公共 {@code threadId +
 * turnStartEntryId} 归属于目标 Thread；历史 Entry 必须位于该 Thread 当前 head 的实际路径上，且其最近 TurnStart 必须由该 Thread
 * 发起。仅匹配路径中更早的 owner TurnStart 会让共享父历史的子 Thread 结果泄漏给父 Thread，因此绝不使用。
 */
public final class ToolOutcomeQuery {

  private static final HistoryPayloadMapper PAYLOAD_MAPPER = new HistoryPayloadMapper();

  private final HarnessStore store;

  public ToolOutcomeQuery(HarnessStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  public Optional<ToolOutcomeView> find(UUID threadId, UUID invocationId) {
    return store.transaction(
        tx -> {
          ThreadState thread =
              tx.findThread(threadId)
                  .orElseThrow(
                      () ->
                          new HarnessRuntimeNotFoundException(
                              "thread " + threadId + " does not exist"));
          return find(tx, thread, invocationId);
        });
  }

  /** 供已锁定 Thread 的控制路径复用；调用方仍须传入真实 owner Thread。 */
  public static Optional<ToolOutcomeView> find(
      HarnessStore.Transaction tx, ThreadState thread, UUID invocationId) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(thread, "thread");
    Objects.requireNonNull(invocationId, "invocationId");
    ToolInvocation invocation = tx.findToolInvocation(invocationId).orElse(null);
    if (invocation != null) {
      if (!invocation.status().isTerminal()
          || !invocation.threadId().equals(thread.id())
          || !turnStartOwnedBy(tx, invocation.turnStartEntryId(), thread.id())) {
        return Optional.empty();
      }
      return Optional.of(
          fromPayload(thread.id(), PAYLOAD_MAPPER.toolResultPayload(invocation), null));
    }
    return tx.findToolResultEntryByInvocationId(thread.sessionId(), invocationId)
        .flatMap(entry -> findMaterialized(tx, thread, entry, invocationId));
  }

  /** Invocation 的 turn start 必须真实由目标 Thread 发起，而不是依赖任何 Model 行反查。 */
  private static boolean turnStartOwnedBy(
      HarnessStore.Transaction tx, UUID turnStartEntryId, UUID threadId) {
    EntryPath path;
    try {
      path = tx.loadEntryPath(turnStartEntryId);
    } catch (IllegalArgumentException broken) {
      throw new IllegalStateException(
          "tool invocation " + turnStartEntryId + " lost its turn start path", broken);
    }
    return path.head().payload() instanceof TurnStartPayload start
        && start.ownerThreadId().equals(threadId);
  }

  private static Optional<ToolOutcomeView> findMaterialized(
      HarnessStore.Transaction tx, ThreadState thread, Entry found, UUID invocationId) {
    EntryPath headPath;
    try {
      headPath = tx.loadEntryPath(thread.headEntryId());
    } catch (IllegalArgumentException broken) {
      throw new IllegalStateException(
          "thread " + thread.id() + " lost its head entry path", broken);
    }
    List<Entry> path = headPath.entries();
    int resultIndex = indexOf(path, found.id());
    if (resultIndex < 0) {
      // Entry 存在于同一 Session 的其它 fork/子 Thread 分支，不属于本 Thread 当前可达历史。
      return Optional.empty();
    }
    Entry resultEntry = path.get(resultIndex);
    Entry resultTurnStart = nearestTurnStart(path, resultIndex);
    if (resultTurnStart == null
        || !(resultTurnStart.payload() instanceof TurnStartPayload start)
        || !start.ownerThreadId().equals(thread.id())) {
      return Optional.empty();
    }
    MessagePayload payload = messagePayload(resultEntry);
    ToolResultMetadata metadata = payload.toolResultMetadata();
    if (metadata == null || metadata.synthetic() || !invocationId.equals(metadata.invocationId())) {
      throw new IllegalStateException(
          "tool result entry " + resultEntry.id() + " lost its invocation identity");
    }
    validateMaterializedIdentity(path, resultIndex, resultTurnStart, metadata, payload);
    return Optional.of(fromPayload(thread.id(), payload, resultEntry.id()));
  }

  /** 校验 assistantEntryId、callIndex 与 ToolResult 内容确实属于最近 TurnStart 内的同一真实调用。 */
  private static void validateMaterializedIdentity(
      List<Entry> path,
      int resultIndex,
      Entry resultTurnStart,
      ToolResultMetadata metadata,
      MessagePayload resultPayload) {
    int assistantIndex = indexOf(path, metadata.assistantEntryId());
    if (assistantIndex < 0 || assistantIndex >= resultIndex) {
      throw new IllegalStateException(
          "tool result "
              + metadata.invocationId()
              + " lost its assistant entry on the thread path");
    }
    Entry assistantEntry = path.get(assistantIndex);
    if (!(assistantEntry.payload() instanceof MessagePayload assistantPayload)
        || assistantPayload.message().role() != AgentMessageRole.ASSISTANT) {
      throw new IllegalStateException(
          "tool result "
              + metadata.invocationId()
              + " assistant entry is not an assistant message");
    }
    Entry assistantTurnStart = nearestTurnStart(path, assistantIndex);
    if (assistantTurnStart == null || !assistantTurnStart.id().equals(resultTurnStart.id())) {
      throw new IllegalStateException(
          "tool result " + metadata.invocationId() + " assistant entry is from another turn");
    }
    List<ToolCallMessageContent> calls = toolCalls(assistantPayload);
    if (metadata.callIndex() >= calls.size()) {
      throw new IllegalStateException(
          "tool result "
              + metadata.invocationId()
              + " callIndex "
              + metadata.callIndex()
              + " exceeds the assistant tool calls");
    }
    ToolCallMessageContent call = calls.get(metadata.callIndex());
    ToolResultMessageContent result = singleToolResult(resultPayload);
    boolean success = metadata.status() == ToolResultStatus.SUCCEEDED;
    if (!call.toolCallId().equals(metadata.toolCallId())
        || !call.toolCallId().equals(result.toolCallId())
        || !call.toolName().equals(result.toolName())
        || !call.rendererKey().equals(result.rendererKey())
        || (!success && !result.error())) {
      throw new IllegalStateException(
          "tool result " + metadata.invocationId() + " does not match its assistant tool call");
    }
  }

  private static List<ToolCallMessageContent> toolCalls(MessagePayload assistant) {
    List<ToolCallMessageContent> calls = new ArrayList<>();
    for (AgentMessageContent content : assistant.message().contents()) {
      if (content instanceof ToolCallMessageContent call) {
        calls.add(call);
      }
    }
    return calls;
  }

  private static ToolResultMessageContent singleToolResult(MessagePayload payload) {
    if (payload.message().contents().size() != 1
        || !(payload.message().contents().getFirst() instanceof ToolResultMessageContent result)) {
      throw new IllegalStateException("tool outcome payload lost its tool result content");
    }
    return result;
  }

  private static int indexOf(List<Entry> path, UUID entryId) {
    for (int i = 0; i < path.size(); i++) {
      if (path.get(i).id().equals(entryId)) {
        return i;
      }
    }
    return -1;
  }

  /** 只取指定位置之前最近的 TurnStart，绝不把更早的 owner Turn 当成当前归属。 */
  private static Entry nearestTurnStart(List<Entry> path, int beforeIndex) {
    for (int i = beforeIndex - 1; i >= 0; i--) {
      if (path.get(i).payload() instanceof TurnStartPayload) {
        return path.get(i);
      }
    }
    return null;
  }

  private static ToolOutcomeView fromPayload(
      UUID threadId, MessagePayload payload, UUID resultEntryId) {
    ToolResultMetadata metadata = payload.toolResultMetadata();
    if (metadata == null || metadata.synthetic() || metadata.invocationId() == null) {
      throw new IllegalStateException("tool outcome payload lost its real invocation metadata");
    }
    ToolResultMessageContent result = singleToolResult(payload);
    ToolInvocationError error =
        metadata.status() == ToolResultStatus.SUCCEEDED ? null : decodeError(result.detailsJson());
    return new ToolOutcomeView(
        metadata.invocationId(),
        threadId,
        metadata.assistantEntryId(),
        metadata.toolCallId(),
        metadata.callIndex(),
        metadata.status(),
        result.error(),
        result.contents(),
        error,
        metadata.inputReceipt(),
        resultEntryId);
  }

  private static ToolInvocationError decodeError(String detailsJson) {
    try {
      return StrictJson.standard()
          .read(detailsJson, ToolInvocationError.class, "tool outcome error");
    } catch (RuntimeException broken) {
      throw new IllegalStateException("tool outcome lost its durable error details", broken);
    }
  }

  private static MessagePayload messagePayload(Entry entry) {
    if (!(entry.payload() instanceof MessagePayload payload)) {
      throw new IllegalStateException("tool result entry " + entry.id() + " is not a message");
    }
    return payload;
  }
}
