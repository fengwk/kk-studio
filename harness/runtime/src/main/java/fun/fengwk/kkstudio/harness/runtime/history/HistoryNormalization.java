package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * open Turn 的历史 normalization 唯一实现，并回答该 open Turn 能否按 STOPPED 关闭（{@link #stopClose}）：不能就必须按 history
 * cut 语义收尾，绝不伪造模型完成。
 */
public final class HistoryNormalization {

  private static final HistoryPayloadMapper PAYLOAD_MAPPER = new HistoryPayloadMapper();

  private HistoryNormalization() {}

  /**
   * history cut suffix：补写缺失 callIndex 的 synthetic UNKNOWN/HISTORY_CUT ToolResult 后追加
   * CANCELLED/HISTORY_CUT 的 TURN_END；不读取任何 descendant Invocation 结果，ROOT 与已关闭 TURN_END 的 path
   * 返回空列表。
   *
   * @param idAllocator 与调用方事务一致的 Entry ID 分配器
   */
  public static List<Entry> suffix(EntryPath sourcePath, Supplier<UUID> idAllocator, Instant now) {
    Objects.requireNonNull(sourcePath, "sourcePath");
    Objects.requireNonNull(idAllocator, "idAllocator");
    Objects.requireNonNull(now, "now");
    if (sourcePath.openTurnStart().isEmpty()) {
      return List.of();
    }
    Entry turn = sourcePath.openTurnStart().orElseThrow();
    UUID sessionId = sourcePath.root().sessionId();
    List<Entry> suffix = new ArrayList<>();
    UUID parentId = sourcePath.head().id();
    Entry assistant = assistantResultInTurn(sourcePath, turn);
    if (assistant != null && assistant.payload() instanceof MessagePayload message) {
      List<ToolCallMessageContent> calls = toolCalls(message);
      int present = countToolResultsAfter(sourcePath, assistant.id());
      for (int callIndex = present; callIndex < calls.size(); callIndex++) {
        UUID entryId = idAllocator.get();
        suffix.add(
            new Entry(
                entryId,
                sessionId,
                parentId,
                PAYLOAD_MAPPER.syntheticHistoryCutToolResult(
                    assistant.id(), callIndex, calls.get(callIndex)),
                now));
        parentId = entryId;
      }
    }
    UUID turnEndId = idAllocator.get();
    suffix.add(
        new Entry(
            turnEndId,
            sessionId,
            parentId,
            new TurnEndPayload(
                turn.id(), TurnEndOutcome.CANCELLED, false, TurnEndReason.HISTORY_CUT, null),
            now));
    return suffix;
  }

  /** 显式 Stop 关闭一个既不属于 live Model/Tool、也不可能再被模型推进的 open Turn 的可行形状。 */
  public enum StopClose {
    /** 复用 Turn 内已有的完整 assistant 结果，只补写 STOPPED TURN_END。 */
    REUSE_ASSISTANT_RESULT,
    /** 尚无 assistant 结果且允许直接产生取消屏障：追加 ASSISTANT_ERROR(CANCELLED) 后再补写 STOPPED TURN_END。 */
    APPEND_CANCEL_BARRIER,
    /** 前缀不足以按 STOPPED 合法关闭（尚无输入的空 INPUT Turn，或工具结果缺失的 assistant 结果），只能按 history cut 收尾。 */
    HISTORY_CUT
  }

  /**
   * 判定 open Turn 能否按 STOPPED 关闭：已有 assistant 结果时必须工具结果完整；尚无 assistant 结果时 INPUT Turn 必须先有
   * USER/CUSTOM 输入，其它 reason 允许直接产生取消屏障。
   */
  public static StopClose stopClose(EntryPath path, Entry openTurn) {
    Objects.requireNonNull(path, "path");
    Objects.requireNonNull(openTurn, "openTurn");
    Entry assistant = assistantResultInTurn(path, openTurn);
    if (assistant == null) {
      if (openTurn.payload() instanceof TurnStartPayload start
          && start.reason() == TurnStartReason.INPUT
          && !inputSeen(path, openTurn)) {
        return StopClose.HISTORY_CUT;
      }
      return StopClose.APPEND_CANCEL_BARRIER;
    }
    if (assistant.payload() instanceof MessagePayload message) {
      // 只有 ASSISTANT MESSAGE 可能带 tool call：它的 tool 结果必须已成完整前缀。
      return countToolResultsAfter(path, assistant.id()) == toolCalls(message).size()
          ? StopClose.REUSE_ASSISTANT_RESULT
          : StopClose.HISTORY_CUT;
    }
    // ASSISTANT_ERROR / ASSISTANT_ABORTED / COMPACTION 本身就是该 Turn 唯一且完整的 assistant 结果。
    return StopClose.REUSE_ASSISTANT_RESULT;
  }

  /**
   * open Turn 内唯一的 assistant 结果 Entry（ASSISTANT MESSAGE / ASSISTANT_ERROR / ASSISTANT_ABORTED /
   * COMPACTION）。
   */
  private static Entry assistantResultInTurn(EntryPath path, Entry turn) {
    boolean inTurn = false;
    for (Entry entry : path.entries()) {
      if (entry.id().equals(turn.id())) {
        inTurn = true;
        continue;
      }
      if (!inTurn) {
        continue;
      }
      EntryPayload payload = entry.payload();
      if (payload instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.ASSISTANT) {
        return entry;
      }
      if (payload instanceof AssistantErrorPayload
          || payload instanceof AssistantAbortedPayload
          || payload instanceof CompactionPayload) {
        return entry;
      }
      if (payload instanceof TurnEndPayload) {
        return null;
      }
    }
    return null;
  }

  /** open Turn 内是否已有 USER 或 CUSTOM MESSAGE（INPUT Turn 在产生 assistant 结果前必须已有输入）。 */
  private static boolean inputSeen(EntryPath path, Entry turn) {
    boolean inTurn = false;
    for (Entry entry : path.entries()) {
      if (entry.id().equals(turn.id())) {
        inTurn = true;
        continue;
      }
      if (!inTurn) {
        continue;
      }
      if (entry.payload() instanceof TurnEndPayload) {
        return false;
      }
      if (entry.payload() instanceof CustomMessagePayload) {
        return true;
      }
      if (entry.payload() instanceof MessagePayload message
          && (message.message().role() == AgentMessageRole.USER
              || message.message().role() == AgentMessageRole.TOOL)) {
        return message.message().role() == AgentMessageRole.USER;
      }
    }
    return false;
  }

  private static int countToolResultsAfter(EntryPath path, UUID assistantEntryId) {
    int count = 0;
    boolean after = false;
    for (Entry entry : path.entries()) {
      if (entry.id().equals(assistantEntryId)) {
        after = true;
        continue;
      }
      if (after
          && entry.payload() instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.TOOL) {
        count++;
      }
    }
    return count;
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
