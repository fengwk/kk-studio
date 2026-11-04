package fun.fengwk.kkstudio.harness.spi.driver;

import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.api.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.codec.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 公共 Assistant 历史追加器：把一条已完整校验的 Assistant 消息及其全部工具槽位原子写入 Entry/ToolInvocation。
 *
 * <p>调用方已在同一 Runtime 事务内锁定 Thread 与 Driver 私有状态。本组件只追加 Assistant、全部槽位并推进 Thread head/version， 不请求
 * Work、不写 source 水位、不更新 Driver 记录；那些事实必须由调用方在同一事务内补全。工具槽位与消息中的 tool_call 必须按 callIndex
 * 精确一一对应，绝不允许只写消息或只写部分槽位。
 */
public final class AssistantHistoryAppender {

  /** 一个待写入的公共工具槽位；immediate FAILED 才允许 binding 为 null。 */
  public record ToolSlot(
      UUID invocationId,
      ToolCall call,
      ToolBinding binding,
      ToolInvocationStatus status,
      ToolInvocationError error) {

    public ToolSlot {
      invocationId = Objects.requireNonNull(invocationId, "invocationId");
      call = Objects.requireNonNull(call, "call");
      status = Objects.requireNonNull(status, "status");
      if (status != ToolInvocationStatus.WAITING_REQUEST
          && status != ToolInvocationStatus.READY
          && status != ToolInvocationStatus.FAILED) {
        throw new IllegalArgumentException(
            "assistant history tool slots must be WAITING_REQUEST, READY or immediate FAILED");
      }
      if (status == ToolInvocationStatus.FAILED) {
        if (error == null) {
          throw new IllegalArgumentException("FAILED tool slot requires an error");
        }
      } else if (error != null) {
        throw new IllegalArgumentException("non-FAILED tool slot must not carry an error");
      }
      if (binding == null && status != ToolInvocationStatus.FAILED) {
        throw new IllegalArgumentException("only immediate FAILED tool slots may omit a binding");
      }
      if (binding != null && !binding.descriptor().name().equals(call.toolName())) {
        throw new IllegalArgumentException("slot binding must match the call toolName");
      }
    }
  }

  public record Applied(
      UUID assistantEntryId, ThreadState thread, List<ToolInvocation> toolInvocations) {
    public Applied {
      Objects.requireNonNull(assistantEntryId, "assistantEntryId");
      Objects.requireNonNull(thread, "thread");
      toolInvocations = List.copyOf(Objects.requireNonNull(toolInvocations, "toolInvocations"));
    }
  }

  public Applied append(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      AgentMessage message,
      AssistantMessageMetadata metadata,
      List<ToolSlot> slots,
      Instant now) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(thread, "thread");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(slots, "slots");
    Objects.requireNonNull(now, "now");
    if (message.role() != AgentMessageRole.ASSISTANT) {
      throw new IllegalArgumentException("assistant history append requires an assistant message");
    }
    if (!thread.headEntryId().equals(path.head().id())
        || !thread.sessionId().equals(path.root().sessionId())) {
      throw new IllegalArgumentException(
          "assistant history append requires the locked thread head path");
    }
    Entry openTurn =
        path.openTurnStart()
            .orElseThrow(
                () ->
                    new IllegalArgumentException("assistant history append requires an open turn"));
    if (!(openTurn.payload() instanceof TurnStartPayload start)
        || !thread.id().equals(start.ownerThreadId())) {
      throw new IllegalArgumentException(
          "assistant history append requires an open turn owned by thread " + thread.id());
    }
    List<ToolCallMessageContent> calls = toolCalls(message);
    if (calls.size() != slots.size()) {
      throw new IllegalArgumentException("tool slots must match all assistant tool calls");
    }
    Set<UUID> invocationIds = new HashSet<>();
    for (int i = 0; i < slots.size(); i++) {
      ToolSlot slot = slots.get(i);
      ToolCallMessageContent call = calls.get(i);
      if (!invocationIds.add(slot.invocationId())
          || !slot.call().id().equals(call.toolCallId())
          || !slot.call().toolName().equals(call.toolName())
          || !slot.call().argumentsJson().equals(call.argumentsJson())) {
        throw new IllegalArgumentException(
            "tool slot " + i + " must match the assistant call and use a unique invocation id");
      }
      ToolBinding binding = slot.binding();
      if (binding != null
          && (start.capabilities() == null
              || !start.capabilities().toolBindings().contains(binding))) {
        throw new IllegalArgumentException(
            "tool slot " + i + " binding was not frozen in the open turn capabilities");
      }
      String expectedRenderer =
          binding == null
              ? HistoryPayloadMapper.UNBOUND_RENDERER_KEY
              : binding.descriptor().rendererKey();
      if (!expectedRenderer.equals(call.rendererKey())) {
        throw new IllegalArgumentException(
            "tool slot " + i + " renderer must match its frozen binding");
      }
      if (tx.tools().find(slot.invocationId()).isPresent()) {
        throw new IllegalArgumentException(
            "tool invocation " + slot.invocationId() + " already exists");
      }
    }
    UUID assistantEntryId = tx.nextId();
    tx.entries()
        .insert(
            new Entry(
                assistantEntryId,
                path.root().sessionId(),
                path.head().id(),
                new MessagePayload(message, metadata, null),
                now));
    List<ToolInvocation> invocations = new ArrayList<>(slots.size());
    for (int callIndex = 0; callIndex < slots.size(); callIndex++) {
      ToolSlot slot = slots.get(callIndex);
      invocations.add(
          new ToolInvocation(
              slot.invocationId(),
              thread.id(),
              openTurn.id(),
              assistantEntryId,
              callIndex,
              slot.call(),
              slot.binding(),
              slot.status(),
              0,
              null,
              null,
              slot.error(),
              now,
              now));
    }
    if (!invocations.isEmpty()) {
      tx.tools().insert(invocations);
    }
    ThreadState advanced = thread.advanceHead(assistantEntryId, now);
    tx.threads().update(advanced);
    return new Applied(assistantEntryId, advanced, invocations);
  }

  public static List<ToolCallMessageContent> toolCalls(AgentMessage message) {
    List<ToolCallMessageContent> calls = new ArrayList<>();
    for (AgentMessageContent content : message.contents()) {
      if (content instanceof ToolCallMessageContent call) {
        calls.add(call);
      }
    }
    return calls;
  }
}
