package fun.fengwk.kkstudio.platform.harness.store;

import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.EntryType;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.codec.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.codec.ToolResultJsonCodec;

import java.util.ArrayList;
import java.util.List;

/**
 * ToolInvocation 写入的引用校验与列编码：新调用必须落在真实 Thread/Turn 分支上、与 assistant 消息里的同一 callIndex 完全一致， 且所有 JSONB
 * 列都按本模块的持久协议编码。
 *
 * <p>这些校验只读既有事实，绝不产生锁或写入；先后顺序即拒绝顺序，调用方必须按 {@link #requireInsertable} 的既有次序检查。
 */
final class PostgresqlToolInvocationValidation {

  private final PostgresqlHarnessTransaction tx;

  PostgresqlToolInvocationValidation(PostgresqlHarnessTransaction tx) {
    this.tx = tx;
  }

  /** {@code assistant_entry_id + call_index} 已有调用时拒绝，避免同一 tool call 被插入两次。 */
  void requireUniqueToolCallIndex(ToolInvocation invocation) {
    Boolean exists =
        tx.queryForObject(
            """
            select exists (
                select 1
                from harness_tool_invocation
                where assistant_entry_id = ? and call_index = ?
            )
            """,
            Boolean.class,
            invocation.assistantEntryId(),
            invocation.callIndex());
    if (Boolean.TRUE.equals(exists)) {
      throw new IllegalArgumentException(
          "tool invocation callIndex "
              + invocation.callIndex()
              + " already used on assistant entry "
              + invocation.assistantEntryId());
    }
  }

  /** 新调用的完整引用校验：Thread、Turn 归属、assistant 消息身份与同一 callIndex 的 tool call 必须逐项一致。 */
  void requireValidToolReferences(ToolInvocation invocation) {
    ThreadState thread =
        tx.threads()
            .find(invocation.threadId())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "thread " + invocation.threadId() + " does not exist"));
    if (invocation.binding() == null && invocation.status() != ToolInvocationStatus.FAILED) {
      throw new IllegalArgumentException("unbound tool invocations must be terminal FAILED slots");
    }
    Entry turnStart = tx.requireExistingEntry(invocation.turnStartEntryId());
    if (turnStart.payload().type() != EntryType.TURN_START
        || !(turnStart.payload() instanceof TurnStartPayload start)) {
      throw new IllegalArgumentException("turnStartEntryId must reference a TURN_START entry");
    }
    if (!start.ownerThreadId().equals(thread.id())
        || !turnStart.sessionId().equals(thread.sessionId())) {
      throw new IllegalArgumentException(
          "turn start must belong to the tool invocation thread and session");
    }
    Entry assistant = tx.requireExistingEntry(invocation.assistantEntryId());
    if (!isAssistantEntry(assistant)) {
      throw new IllegalArgumentException(
          "assistantEntryId must reference an assistant MESSAGE entry");
    }
    if (!assistant.sessionId().equals(thread.sessionId())) {
      throw new IllegalArgumentException(
          "assistantEntryId must belong to the tool invocation thread session");
    }
    EntryPath assistantPath = tx.entries().loadPath(assistant.id());
    if (assistantPath.openTurnStart().map(Entry::id).orElse(null) == null
        || !assistantPath.openTurnStart().orElseThrow().id().equals(turnStart.id())) {
      throw new IllegalArgumentException(
          "assistantEntryId must be on the branch opened by turnStartEntryId");
    }
    requireMatchingAssistantToolCall(invocation, assistant);
  }

  private static void requireMatchingAssistantToolCall(ToolInvocation invocation, Entry assistant) {
    MessagePayload message = (MessagePayload) assistant.payload();
    List<ToolCallMessageContent> calls = new ArrayList<>();
    for (AgentMessageContent content : message.message().contents()) {
      if (content instanceof ToolCallMessageContent call) {
        calls.add(call);
      }
    }
    if (invocation.callIndex() >= calls.size()) {
      throw new IllegalArgumentException(
          "tool callIndex " + invocation.callIndex() + " exceeds the assistant tool calls");
    }
    ToolCallMessageContent call = calls.get(invocation.callIndex());
    ToolCall requestCall = invocation.call();
    String expectedRendererKey =
        invocation.binding() == null
            ? HistoryPayloadMapper.UNBOUND_RENDERER_KEY
            : invocation.binding().descriptor().rendererKey();
    if (!call.toolCallId().equals(requestCall.id())
        || !call.toolName().equals(requestCall.toolName())
        || !call.rendererKey().equals(expectedRendererKey)
        || !call.argumentsJson().equals(requestCall.argumentsJson())) {
      throw new IllegalArgumentException(
          "tool call/binding must exactly match the assistant tool call at the same callIndex");
    }
  }

  private static boolean isAssistantEntry(Entry entry) {
    return entry.payload().type() == EntryType.MESSAGE
        && entry.payload() instanceof MessagePayload message
        && message.message().role() == AgentMessageRole.ASSISTANT;
  }

  /** 可空 JSONB 列的统一编码：{@code null} 表示该事实不存在，绝不写空对象。 */
  static String encodeApproval(ToolInvocation invocation) {
    return invocation.approval() == null
        ? null
        : PostgresqlHarnessRows.TOOL_APPROVALS.encode(invocation.approval());
  }

  static String encodeBinding(ToolInvocation invocation) {
    return invocation.binding() == null
        ? null
        : PostgresqlHarnessRows.TOOL_BINDINGS.encode(invocation.binding());
  }

  static String encodeResult(ToolInvocation invocation) {
    return invocation.result() == null ? null : ToolResultJsonCodec.encode(invocation.result());
  }

  static String encodeInputReceipt(ToolInvocation invocation) {
    return invocation.inputReceipt() == null
        ? null
        : PostgresqlHarnessRows.JSON.write(invocation.inputReceipt(), "toolInputReceipt");
  }

  static String encodeError(ToolInvocation invocation) {
    return invocation.error() == null
        ? null
        : PostgresqlHarnessRows.JSON.write(invocation.error(), "toolInvocationError");
  }
}
