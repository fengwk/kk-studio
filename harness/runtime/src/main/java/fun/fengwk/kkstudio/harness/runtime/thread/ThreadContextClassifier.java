package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.driver.DriverActivity;
import fun.fengwk.kkstudio.harness.runtime.driver.LockedDriverState;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 纯 Thread live/historical 适用性分类器：不接触 Store、不产生锁、无副作用。
 *
 * <p>输入契约：{@code driverState} 只能是 Driver 按 {@code (threadId, 当前 open turn 的 TURN_START entry id)}
 * 读取或锁定的中立状态视图（无状态时为 {@link DriverActivity#NONE} 实现）；{@code toolSiblings} 只在当前 head 为 ASSISTANT
 * Message 时加载。违反输入契约（不兼容形状）与任何持久化不变量破坏一样抛 {@link IllegalStateException}，绝不把破坏状态降级为业务 kind。
 */
public final class ThreadContextClassifier {

  public ThreadContext classify(
      ThreadState thread,
      EntryPath path,
      LockedDriverState driverState,
      List<ToolInvocation> toolSiblings) {
    Objects.requireNonNull(thread, "thread");
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(driverState, "driverState");
    Objects.requireNonNull(toolSiblings, "toolSiblings");
    Optional<Entry> openTurn = path.openTurnStart();
    if (openTurn.isEmpty()) {
      requireNoDriverOrSiblings(driverState, toolSiblings, "without an open turn");
      Entry head = path.headIgnoringTrailingControlEntries();
      if (head.payload() instanceof TurnEndPayload end && end.continueModel()) {
        TurnStartPayload start = referencedTurnStart(path, end);
        if (thread.id().equals(start.ownerThreadId())) {
          return new ThreadContext.ContinuationDue(head);
        }
        return new ThreadContext.IdleOrHistorical();
      }
      return new ThreadContext.IdleOrHistorical();
    }
    Entry turn = openTurn.get();
    Entry head = path.head();
    if (driverState.activity() == DriverActivity.ACTIVE
        || driverState.activity() == DriverActivity.RESPONSE_PENDING) {
      if (!driverState.atBasis(head.id())) {
        requireEmptySiblings(toolSiblings, "while driver state is not at its request basis");
        return new ThreadContext.IdleOrHistorical();
      }
      requireEmptySiblings(toolSiblings, "at the driver basis");
      return driverState.activity() == DriverActivity.ACTIVE
          ? new ThreadContext.ModelActive(driverState)
          : new ThreadContext.ModelTerminalPending(driverState);
    }
    if (driverState.resultEntryId() != null && !driverState.resultAt(head.id())) {
      requireEmptySiblings(toolSiblings, "without the driver result at the head");
      return new ThreadContext.IdleOrHistorical();
    }
    if (toolSiblings.isEmpty()) {
      requireNoToolCallsWithoutSiblings(thread, turn, head);
      return new ThreadContext.IdleOrHistorical();
    }
    if (!(head.payload() instanceof MessagePayload message)
        || message.message().role() != AgentMessageRole.ASSISTANT) {
      throw new IllegalStateException(
          "tool siblings require an assistant message head for turn " + turn.id());
    }
    return toolContext(thread, turn, head, message, toolSiblings);
  }

  private ThreadContext toolContext(
      ThreadState thread,
      Entry turn,
      Entry assistant,
      MessagePayload message,
      List<ToolInvocation> siblings) {
    List<ToolCallMessageContent> calls = toolCalls(message);
    if (calls.size() != siblings.size()) {
      throw new IllegalStateException(
          "tool sibling count "
              + siblings.size()
              + " must match the assistant tool calls "
              + calls.size()
              + " of entry "
              + assistant.id());
    }
    for (int i = 0; i < siblings.size(); i++) {
      ToolInvocation sibling = siblings.get(i);
      if (sibling.callIndex() != i) {
        throw new IllegalStateException(
            "tool siblings must be a contiguous callIndex prefix of entry " + assistant.id());
      }
      if (!sibling.threadId().equals(thread.id())
          || !sibling.turnStartEntryId().equals(turn.id())
          || !sibling.assistantEntryId().equals(assistant.id())) {
        throw new IllegalStateException(
            "tool sibling "
                + sibling.id()
                + " must belong to thread "
                + thread.id()
                + " turn "
                + turn.id()
                + " and assistant "
                + assistant.id());
      }
    }
    boolean allTerminal = true;
    for (ToolInvocation sibling : siblings) {
      if (!sibling.status().isTerminal()) {
        allTerminal = false;
        break;
      }
    }
    if (!allTerminal) {
      return new ThreadContext.ToolActive(assistant, calls, siblings);
    }
    return new ThreadContext.ToolTerminalPending(assistant, calls, siblings);
  }

  private static void requireNoDriverOrSiblings(
      LockedDriverState driverState, List<ToolInvocation> toolSiblings, String context) {
    if (driverState.activity() != DriverActivity.NONE || driverState.requestHeadEntryId() != null) {
      throw new IllegalStateException("a driver state is incompatible " + context);
    }
    requireEmptySiblings(toolSiblings, context);
  }

  private static TurnStartPayload referencedTurnStart(EntryPath path, TurnEndPayload end) {
    for (Entry entry : path.entries()) {
      if (entry.id().equals(end.turnStartEntryId())
          && entry.payload() instanceof TurnStartPayload start) {
        return start;
      }
    }
    throw new IllegalStateException(
        "TURN_END with turnStartEntryId "
            + end.turnStartEntryId()
            + " references a TURN_START not on its own path");
  }

  private static void requireEmptySiblings(List<ToolInvocation> toolSiblings, String context) {
    if (!toolSiblings.isEmpty()) {
      throw new IllegalStateException("tool siblings are incompatible " + context);
    }
  }

  private static void requireNoToolCallsWithoutSiblings(
      ThreadState thread, Entry turn, Entry head) {
    // 共享历史中的 open TURN_START 可能属于另一个 Thread；该 Thread 没有本 turn 的 siblings 是正常历史投影。
    if (turn.payload() instanceof TurnStartPayload start
        && thread.id().equals(start.ownerThreadId())
        && head.payload() instanceof MessagePayload message
        && message.message().role() == AgentMessageRole.ASSISTANT
        && !toolCalls(message).isEmpty()) {
      throw new IllegalStateException(
          "assistant tool calls require matching tool siblings at entry " + head.id());
    }
  }

  private static List<ToolCallMessageContent> toolCalls(MessagePayload message) {
    List<ToolCallMessageContent> calls = new ArrayList<>();
    for (var content : message.message().contents()) {
      if (content instanceof ToolCallMessageContent call) {
        calls.add(call);
      }
    }
    return calls;
  }
}
