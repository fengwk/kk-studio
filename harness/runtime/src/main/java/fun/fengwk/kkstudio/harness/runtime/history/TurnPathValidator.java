package fun.fengwk.kkstudio.harness.runtime.history;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;

import java.util.ArrayList;
import java.util.List;

/**
 * package-private EntryPath turn-sequence validation state（KISS：非通用 workflow/state-machine）。
 *
 * <p>校验 open Turn 内的 entry 顺序与 TURN_END outcome 前置条件：ROOT 后所有非 ROOT entry 必须在 open TURN_START
 * 内（CUSTOM 透明除外，见 {@link #visit}）；input 阶段只允许 USER/CUSTOM MESSAGE；INPUT turn 在 Assistant
 * 结果前必须已有至少一条 USER/CUSTOM；CONTINUATION turn 偿还上一 TURN_END 的 continueModel obligation，不消费
 * USER/CUSTOM（配置 Commands 只体现在 TURN_START.settings， 不形成 Message），可零 input 直接产生 Assistant
 * 结果；Assistant 结果（ASSISTANT MESSAGE / ASSISTANT_ERROR / ASSISTANT_ABORTED）只能出现一次且之后 不得再出现
 * USER/CUSTOM/第二个 Assistant；TOOL MESSAGE 只能跟随带 ToolCall 的 ASSISTANT MESSAGE，且必须是 ordinal 0
 * 开始的严格前缀（ordinal 连续、toolCallId/toolName 匹配、assistantEntryId 等于该 Assistant Entry id）；TURN_END
 * 只能关闭当前 open TURN_START 且 ID 匹配，并按 outcome 校验前置条件（COMPLETED 必须已有 ASSISTANT MESSAGE 且 ToolResult
 * 完整；FAILED 必须已有 ASSISTANT_ERROR；STOPPED 必须已有 stop barrier/Assistant 且 ToolResult 完整；CANCELLED 可在任意
 * open phase 关闭）。路径可以在任意 prefix 截断。
 */
final class TurnPathValidator {

  private Entry openTurnStart;
  private TurnStartReason openReason;
  private boolean inputSeen;
  private boolean assistantSeen;
  private Entry assistantResultEntry;
  private List<ToolCallMessageContent> toolCalls;
  private int expectedOrdinal;

  /** 按 root-to-head 顺序访问一个非 ROOT Entry。 */
  void visit(Entry entry) {
    EntryPayload payload = entry.payload();
    if (payload instanceof CustomEntryPayload) {
      // CUSTOM 是透明 branch state：允许 ROOT 后任意位置（含 open/closed turn），不参与 input/assistant/ordinal
      // 判定，也不打开/关闭 turn。
      return;
    }
    if (payload instanceof TurnStartPayload start) {
      if (openTurnStart != null) {
        throw new IllegalArgumentException(
            "TURN_START must not open while a previous turn is open");
      }
      openTurnStart = entry;
      openReason = start.reason();
      inputSeen = false;
      assistantSeen = false;
      assistantResultEntry = null;
      toolCalls = null;
      expectedOrdinal = 0;
      return;
    }
    if (openTurnStart == null) {
      throw new IllegalArgumentException(
          entry.payload().type() + " entry must be inside an open TURN_START");
    }
    if (payload instanceof TurnEndPayload end) {
      if (end.turnStartEntryId() != openTurnStart.id()) {
        throw new IllegalArgumentException("TURN_END must reference its open TURN_START entry id");
      }
      switch (end.outcome()) {
        case COMPLETED -> {
          requireAssistantMessage("completed");
          requireInput("a completed turn");
          requireCompleteToolResults("completed");
        }
        case FAILED -> {
          requireInput("a failed turn");
          if (!(assistantResultEntry != null
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
      openTurnStart = null;
      return;
    }
    if (payload instanceof MessagePayload message) {
      AgentMessageRole role = message.message().role();
      if (!assistantSeen) {
        if (role == AgentMessageRole.USER) {
          if (openReason == TurnStartReason.CONTINUATION) {
            throw new IllegalArgumentException("continuation turns must not consume USER messages");
          }
          inputSeen = true;
          return;
        }
        if (role == AgentMessageRole.TOOL) {
          throw new IllegalArgumentException(
              "tool results require an assistant message with tool calls");
        }
        // ASSISTANT：本 turn 唯一的 assistant result。SYSTEM 在此处不可能出现。
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
    if (payload instanceof CustomMessagePayload) {
      if (assistantSeen) {
        throw new IllegalArgumentException("custom messages must not follow an assistant result");
      }
      if (openReason == TurnStartReason.CONTINUATION) {
        throw new IllegalArgumentException("continuation turns must not consume custom messages");
      }
      inputSeen = true;
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

  private void requireCompleteToolResults(String context) {
    if (toolCalls != null && expectedOrdinal < toolCalls.size()) {
      throw new IllegalArgumentException(context + " turns require all ordered tool results");
    }
  }

  private void validateToolResult(Entry entry, MessagePayload message) {
    if (toolCalls == null || toolCalls.isEmpty()) {
      throw new IllegalArgumentException(
          "tool results require an assistant message with tool calls");
    }
    ToolResultMetadata metadata = message.toolResultMetadata();
    if (metadata.ordinal() != expectedOrdinal) {
      throw new IllegalArgumentException(
          "tool result ordinal must be a strict prefix from 0, expected " + expectedOrdinal);
    }
    if (metadata.ordinal() >= toolCalls.size()) {
      throw new IllegalArgumentException("tool result ordinal exceeds the assistant tool calls");
    }
    ToolCallMessageContent call = toolCalls.get(metadata.ordinal());
    if (!metadata.toolCallId().equals(call.toolCallId())) {
      throw new IllegalArgumentException(
          "tool result toolCallId must match the assistant tool call");
    }
    if (metadata.assistantEntryId() != assistantResultEntry.id()) {
      throw new IllegalArgumentException(
          "tool result assistantEntryId must match its assistant entry");
    }
    ToolResultMessageContent result =
        (ToolResultMessageContent) message.message().contents().get(0);
    if (!result.toolName().equals(call.toolName())) {
      throw new IllegalArgumentException("tool result toolName must match the assistant tool call");
    }
    expectedOrdinal++;
  }
}
