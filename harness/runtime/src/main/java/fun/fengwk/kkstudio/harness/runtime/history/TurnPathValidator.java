package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPhase;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionStart;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionTrigger;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * package-private EntryPath turn-sequence validation state（KISS：非通用 workflow/state-machine）。
 *
 * <p>校验 open Turn 内的 entry 顺序与 TURN_END outcome 前置条件：ROOT 后所有非 ROOT entry 必须在 open TURN_START
 * 内（CUSTOM 透明除外，见 {@link #visit}）；input 阶段只允许 USER/CUSTOM MESSAGE；MODEL_ATTEMPT_FAILURE 只能位于 open
 * 非压缩 Turn 的 Assistant 结果之前，attempt 从 1 连续递增；INPUT turn 在 Assistant 结果前必须已有至少一条
 * USER/CUSTOM（或紧邻其前的回合间系统通知，由本 INPUT 接纳；compaction 不消费该待接纳输入）；CONTINUATION turn 偿还上一 TURN_END 的
 * continueModel obligation，不消费 USER/CUSTOM（配置 Commands 只体现在 TURN_START.settings），可零 input 直接产生
 * Assistant 结果；COMPACTION turn 消费零 Command、绝不出现 USER/CUSTOM MESSAGE，成功结果只能是 COMPACTION payload （普通
 * turn 绝不包含它），失败/停止可复用 ASSISTANT_ERROR / ASSISTANT_ABORTED barrier 且无需 USER input；STOP turn
 * 是显式停止的持久屏障（不是模型工作轮），只允许唯一一条 ASSISTANT_ERROR(CANCELLED) 取消屏障及其后 {@code continueModel=false}、{@code
 * closeRequestId} 非 null 的 STOPPED TURN_END，绝不出现 USER/CUSTOM MESSAGE、真实 Assistant 结果、Tool
 * 结果、ModelAttemptFailure 或 COMPACTION 载荷； Assistant 结果（ASSISTANT MESSAGE / ASSISTANT_ERROR /
 * ASSISTANT_ABORTED / COMPACTION）只能出现一次且之后 不得再出现 USER/CUSTOM/第二个 Assistant；TOOL MESSAGE 只能跟随带
 * ToolCall 的 ASSISTANT MESSAGE，且必须是 callIndex 0 开始的严格前缀（callIndex 连续、toolCallId/toolName
 * 匹配、assistantEntryId 等于该 Assistant Entry id）；TURN_END 只能关闭当前 open TURN_START 且 ID 匹配，并按 outcome
 * 校验前置条件（COMPLETED 必须已有 ASSISTANT MESSAGE / COMPACTION 且 ToolResult 完整；HISTORY phase gap、complete
 * OVERFLOW recovery 与 active continuation 前的 complete THRESHOLD compaction 必须 {@code
 * continueModel=true}，其它 compaction 必须 false；FAILED 必须已有 ASSISTANT_ERROR；STOPPED 必须已有 stop
 * barrier/Assistant 且 ToolResult 完整；CANCELLED 可在任意 open phase关闭）。路径可以在任意 prefix 截断。
 */
final class TurnPathValidator {

  private Entry openTurnStart;
  private TurnStartReason openReason;
  private CompactionStart openCompactionStart;
  private UUID openOwnerThreadId;
  private UUID pendingContinuationOwnerThreadId;
  private boolean pendingNotificationInput;
  private boolean inputSeen;
  private boolean assistantSeen;
  private Entry assistantResultEntry;
  private List<ToolCallMessageContent> toolCalls;
  private int expectedCallIndex;
  private int expectedModelAttemptFailure;

  /** 按 root-to-head 顺序访问一个非 ROOT Entry。 */
  void visit(Entry entry) {
    EntryPayload payload = entry.payload();
    if (payload instanceof CustomEntryPayload) {
      // CUSTOM 是透明 branch state：允许 ROOT 后任意位置（含 open/closed turn），不参与 input/assistant/callIndex
      // 判定，也不打开/关闭 turn。
      return;
    }
    if (payload instanceof NotificationPayload) {
      visitNotification();
      return;
    }
    if (payload instanceof SettingsPayload) {
      // SETTINGS 是安全边界上 append 的 branch settings 快照：只允许位于回合之间，不参与 input/assistant/callIndex 判定，
      // 也不打开 / 关闭 turn。
      if (openTurnStart != null) {
        throw new IllegalArgumentException(
            "SETTINGS entry must not appear inside an open TURN_START");
      }
      return;
    }
    if (payload instanceof ForkPayload) {
      // FORK 是 fork 事实节点：只允许位于回合之间，不参与 input/assistant/callIndex 判定，也不打开 / 关闭 turn，
      // 更不作为需要模型回应的输入（不设置 pendingNotificationInput）。
      if (openTurnStart != null) {
        throw new IllegalArgumentException("FORK entry must not appear inside an open TURN_START");
      }
      return;
    }
    if (payload instanceof TurnStartPayload start) {
      if (openTurnStart != null) {
        throw new IllegalArgumentException(
            "TURN_START must not open while a previous turn is open");
      }
      openTurnStart = entry;
      openReason = start.reason();
      openCompactionStart = start.compaction();
      openOwnerThreadId = start.ownerThreadId();
      if (openReason != TurnStartReason.COMPACTION) {
        // INPUT 覆盖旧 obligation；CONTINUATION 在打开时消费前一个 continueModel=true。
        pendingContinuationOwnerThreadId = null;
      }
      inputSeen = openReason == TurnStartReason.INPUT && pendingNotificationInput;
      if (openReason == TurnStartReason.INPUT) {
        // 回合之间的通知由紧随其后的 INPUT 接纳；CONTINUATION / COMPACTION 都不消费它。
        pendingNotificationInput = false;
      }
      assistantSeen = false;
      assistantResultEntry = null;
      toolCalls = null;
      expectedCallIndex = 0;
      expectedModelAttemptFailure = 0;
      return;
    }
    if (openTurnStart == null) {
      throw new IllegalArgumentException(
          entry.payload().type() + " entry must be inside an open TURN_START");
    }
    if (openReason == TurnStartReason.STOP) {
      visitStopTurn(entry, payload);
      return;
    }
    if (payload instanceof ModelAttemptFailurePayload failure) {
      if (openReason == TurnStartReason.COMPACTION) {
        throw new IllegalArgumentException(
            "model attempt failures are not allowed inside compaction turns");
      }
      if (assistantSeen) {
        throw new IllegalArgumentException(
            "model attempt failures must precede the assistant result");
      }
      requireInput("a model attempt failure");
      int expectedAttempt = Math.addExact(expectedModelAttemptFailure, 1);
      if (failure.attempt().attempt() != expectedAttempt) {
        throw new IllegalArgumentException(
            "model attempt failures must be a strict prefix from 1, expected " + expectedAttempt);
      }
      expectedModelAttemptFailure = expectedAttempt;
      return;
    }
    if (payload instanceof TurnEndPayload end) {
      if (!end.turnStartEntryId().equals(openTurnStart.id())) {
        throw new IllegalArgumentException("TURN_END must reference its open TURN_START entry id");
      }
      switch (end.outcome()) {
        case COMPLETED -> {
          if (openReason == TurnStartReason.COMPACTION) {
            // COMPACTION turn 的成功结果必须是 COMPACTION payload（见 visit 的跨用途校验）。
            if (!(assistantResultEntry != null
                && assistantResultEntry.payload() instanceof CompactionPayload)) {
              throw new IllegalArgumentException(
                  "completed compaction turns require a COMPACTION result");
            }
            CompactionPhase phase = openCompactionStart.phase();
            boolean expectedContinueModel =
                phase == CompactionPhase.HISTORY
                    || openCompactionStart.trigger() == CompactionTrigger.OVERFLOW
                    || (openCompactionStart.trigger() == CompactionTrigger.THRESHOLD
                        && Objects.equals(pendingContinuationOwnerThreadId, openOwnerThreadId));
            if (end.continueModel() != expectedContinueModel) {
              throw new IllegalArgumentException(
                  "completed compaction continueModel must match HISTORY, OVERFLOW, or preserved "
                      + "continuation obligation");
            }
          } else {
            requireAssistantMessage("completed");
          }
          requireInput("a completed turn");
          requireCompleteToolResults("completed");
        }
        case FAILED -> {
          requireInput("a failed turn");
          if (end.reason() == TurnEndReason.OUTPUT_TRUNCATED
              || end.reason() == TurnEndReason.CONTENT_FILTERED) {
            // 截断/过滤的 failed turn 以 ASSISTANT MESSAGE 结果结束：保留 partial 内容与 generation metadata，
            // 且必须与 reason 匹配（LENGTH->OUTPUT_TRUNCATED、FILTERED->CONTENT_FILTERED）并零 tool call。
            if (!(assistantResultEntry != null
                && assistantResultEntry.payload() instanceof MessagePayload message)) {
              throw new IllegalArgumentException(
                  "truncated or filtered turns require an ASSISTANT MESSAGE result");
            }
            if (message.assistantMetadata() == null
                || message.assistantMetadata().stopReason() != expectedStopReason(end.reason())) {
              throw new IllegalArgumentException(
                  "truncated or filtered turns require a matching assistant stop reason");
            }
            for (AgentMessageContent content : message.message().contents()) {
              if (content instanceof ToolCallMessageContent) {
                throw new IllegalArgumentException(
                    "truncated or filtered turns must not contain tool calls");
              }
            }
          } else if (!(assistantResultEntry != null
              && assistantResultEntry.payload() instanceof AssistantErrorPayload)) {
            throw new IllegalArgumentException("failed turns require an ASSISTANT_ERROR result");
          }
        }
        case STOPPED -> {
          if (assistantResultEntry == null) {
            throw new IllegalArgumentException(
                "stopped turns require a stop barrier or assistant result");
          }
          requireCompleteToolResults("stopped");
        }
        case CANCELLED -> {
          // history cut 可在任意 open phase 关闭。
        }
      }
      if (openReason != TurnStartReason.COMPACTION) {
        pendingContinuationOwnerThreadId =
            end.outcome() == TurnEndOutcome.COMPLETED && end.continueModel()
                ? openOwnerThreadId
                : null;
      }
      openTurnStart = null;
      openOwnerThreadId = null;
      return;
    }
    if (payload instanceof MessagePayload message) {
      AgentMessageRole role = message.message().role();
      if (!assistantSeen) {
        if (role == AgentMessageRole.USER) {
          if (openReason == TurnStartReason.CONTINUATION) {
            throw new IllegalArgumentException("continuation turns must not consume USER messages");
          }
          if (openReason == TurnStartReason.COMPACTION) {
            throw new IllegalArgumentException("compaction turns must not consume USER messages");
          }
          inputSeen = true;
          return;
        }
        if (role == AgentMessageRole.TOOL) {
          throw new IllegalArgumentException(
              "tool results require an assistant message with tool calls");
        }
        // ASSISTANT：本 turn 唯一的 assistant result。
        if (openReason == TurnStartReason.COMPACTION) {
          throw new IllegalArgumentException(
              "compaction turns require a COMPACTION result, not an assistant message");
        }
        assistantSeen = true;
        assistantResultEntry = entry;
        requireInput("an assistant result");
        toolCalls = new ArrayList<>();
        for (AgentMessageContent content : message.message().contents()) {
          if (content instanceof ToolCallMessageContent call) {
            toolCalls.add(call);
          }
        }
        return;
      }
      if (role == AgentMessageRole.USER) {
        throw new IllegalArgumentException("user messages must not follow an assistant result");
      }
      if (role == AgentMessageRole.ASSISTANT) {
        throw new IllegalArgumentException("assistant result must not repeat");
      }
      validateToolResult(entry, message);
      return;
    }
    if (payload instanceof CustomMessagePayload custom) {
      if (assistantSeen) {
        throw new IllegalArgumentException("custom messages must not follow an assistant result");
      }
      if (openReason == TurnStartReason.CONTINUATION) {
        throw new IllegalArgumentException("continuation turns must not consume custom messages");
      }
      if (openReason == TurnStartReason.COMPACTION) {
        throw new IllegalArgumentException("compaction turns must not consume custom messages");
      }
      inputSeen = true;
      return;
    }
    if (payload instanceof CompactionPayload) {
      // COMPACTION payload：只能是 COMPACTION turn 的唯一 assistant result；普通 turn 绝不包含它。
      if (assistantSeen) {
        throw new IllegalArgumentException("assistant result must not repeat");
      }
      if (openReason != TurnStartReason.COMPACTION) {
        throw new IllegalArgumentException(
            "compaction entries are only allowed inside compaction turns");
      }
      assistantSeen = true;
      assistantResultEntry = entry;
      return;
    }
    // ASSISTANT_ERROR / ASSISTANT_ABORTED：本 turn 唯一的 assistant result。
    if (assistantSeen) {
      throw new IllegalArgumentException("assistant result must not repeat");
    }
    assistantSeen = true;
    assistantResultEntry = entry;
    requireInput("an assistant result");
  }

  /**
   * NOTIFICATION 的系统通知只能位于回合之间，或 INPUT 输入段（assistant 结果之前）；它计入 INPUT 输入，从而支持 notification-only 规划，
   * 但绝不插入未闭合的 tool-call/result 中间，也不出现在 COMPACTION / STOP / CONTINUATION turn。
   */
  private void visitNotification() {
    if (openTurnStart == null) {
      // 回合之间：独立系统通知历史节点，不打开 turn，也不偿还/清除 continuation obligation。它是待下一 INPUT 接纳的输入
      // （compaction 不消费它），因此紧随的 INPUT turn 无需再写消息即满足 input 前置。
      pendingNotificationInput = true;
      return;
    }
    if (openReason != TurnStartReason.INPUT) {
      throw new IllegalArgumentException(
          "notifications are only allowed between turns or in the input section of an INPUT turn");
    }
    if (assistantSeen) {
      throw new IllegalArgumentException("notifications must not follow an assistant result");
    }
    inputSeen = true;
  }

  /**
   * STOP turn 的严格形状：显式停止的持久屏障，不是模型工作轮。
   *
   * <p>只允许唯一一条 ASSISTANT_ERROR(CANCELLED) 取消屏障，及其后 {@code continueModel=false}、{@code
   * closeRequestId} 非 null 的 STOPPED TURN_END；USER/CUSTOM MESSAGE、真实 Assistant 结果、Tool 结果、
   * ModelAttemptFailure、ASSISTANT_ABORTED、COMPACTION 载荷与其它 outcome（含 COMPLETED/FAILED）一律拒绝。 既有的
   * INPUT/CONTINUATION/COMPACTION 约束不因 STOP 而放宽。
   */
  private void visitStopTurn(Entry entry, EntryPayload payload) {
    // 不校验 provider replay state：Entry 不变式只允许它出现在 ASSISTANT MESSAGE 上，而本 turn 拒绝一切
    // ASSISTANT MESSAGE，因此 STOP turn 在结构上不可能携带 replay state。
    if (payload instanceof TurnEndPayload end) {
      if (!end.turnStartEntryId().equals(openTurnStart.id())) {
        throw new IllegalArgumentException("TURN_END must reference its open TURN_START entry id");
      }
      if (end.outcome() != TurnEndOutcome.STOPPED
          || end.continueModel()
          || end.closeRequestId() == null) {
        throw new IllegalArgumentException(
            "STOP turns require a STOPPED outcome with continueModel=false and a closeRequestId");
      }
      if (!(assistantResultEntry != null
          && assistantResultEntry.payload() instanceof AssistantErrorPayload barrier)) {
        throw new IllegalArgumentException(
            "stopped turns require a stop barrier or assistant result");
      }
      requireCancelBarrier(barrier);
      requireCompleteToolResults("stopped");
      // 显式停止后没有待偿还的模型义务。
      pendingContinuationOwnerThreadId = null;
      openTurnStart = null;
      openOwnerThreadId = null;
      return;
    }
    if (payload instanceof AssistantErrorPayload barrier) {
      if (assistantSeen) {
        throw new IllegalArgumentException("assistant result must not repeat");
      }
      requireCancelBarrier(barrier);
      assistantSeen = true;
      assistantResultEntry = entry;
      return;
    }
    throw new IllegalArgumentException(
        "STOP turns may only contain one ASSISTANT_ERROR cancel barrier and their STOPPED TURN_END, not "
            + payload.type());
  }

  /** STOP barrier 必须是稳定 CANCELLED code；其它 error code 属于模型失败，不属于显式停止。 */
  private static void requireCancelBarrier(AssistantErrorPayload barrier) {
    if (!AssistantError.CANCELLED_CODE.equals(barrier.error().code())) {
      throw new IllegalArgumentException(
          "STOP barrier error code must be "
              + AssistantError.CANCELLED_CODE
              + " but was "
              + barrier.error().code());
    }
  }

  private void requireInput(String context) {
    if (openReason == TurnStartReason.INPUT && !inputSeen) {
      throw new IllegalArgumentException(
          "INPUT turns require a USER or CUSTOM message before " + context);
    }
  }

  private void requireAssistantMessage(String context) {
    if (!(assistantResultEntry != null
        && assistantResultEntry.payload() instanceof MessagePayload)) {
      throw new IllegalArgumentException(context + " turns require an ASSISTANT MESSAGE result");
    }
  }

  private static GenerationStopReason expectedStopReason(TurnEndReason reason) {
    return switch (reason) {
      case OUTPUT_TRUNCATED -> GenerationStopReason.LENGTH;
      case CONTENT_FILTERED -> GenerationStopReason.FILTERED;
      default -> throw new IllegalArgumentException("unexpected failed turn reason " + reason);
    };
  }

  private void requireCompleteToolResults(String context) {
    if (toolCalls != null && expectedCallIndex < toolCalls.size()) {
      throw new IllegalArgumentException(context + " turns require all ordered tool results");
    }
  }

  private void validateToolResult(Entry entry, MessagePayload message) {
    if (toolCalls == null || toolCalls.isEmpty()) {
      throw new IllegalArgumentException(
          "tool results require an assistant message with tool calls");
    }
    ToolResultMetadata metadata = message.toolResultMetadata();
    if (metadata.callIndex() != expectedCallIndex) {
      throw new IllegalArgumentException(
          "tool result callIndex must be a strict prefix from 0, expected " + expectedCallIndex);
    }
    if (metadata.callIndex() >= toolCalls.size()) {
      throw new IllegalArgumentException("tool result callIndex exceeds the assistant tool calls");
    }
    ToolCallMessageContent call = toolCalls.get(metadata.callIndex());
    if (!metadata.toolCallId().equals(call.toolCallId())) {
      throw new IllegalArgumentException(
          "tool result toolCallId must match the assistant tool call");
    }
    if (!metadata.assistantEntryId().equals(assistantResultEntry.id())) {
      throw new IllegalArgumentException(
          "tool result assistantEntryId must match its assistant entry");
    }
    ToolResultMessageContent result =
        (ToolResultMessageContent) message.message().contents().get(0);
    if (!result.toolName().equals(call.toolName())) {
      throw new IllegalArgumentException("tool result toolName must match the assistant tool call");
    }
    expectedCallIndex++;
  }
}
