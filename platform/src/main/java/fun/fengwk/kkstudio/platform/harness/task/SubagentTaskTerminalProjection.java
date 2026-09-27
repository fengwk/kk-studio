package fun.fengwk.kkstudio.platform.harness.task;

import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskMessages.Outcome;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 从子 Thread 的 durable snapshot 推导本次执行的终态与报告，以及父 Thread 的停止门禁事实。
 *
 * <p>本次执行的边界由持久化的 {@code source_head_entry_id} 界定：结果只取该 Entry 之后的条目，因此继续同一子 Thread 的多次执行互不污染。
 * 这些判定都是纯函数，便于确定性测试。
 */
final class SubagentTaskTerminalProjection {

  /** 本次执行的终态：报告与失败细节分离，供完成消息按契约渲染。 */
  record Terminal(Outcome outcome, String report, String partialResult, String error) {}

  private SubagentTaskTerminalProjection() {}

  /**
   * 子 Thread 是否已经结清本次执行；未结清返回 {@code null}。
   *
   * <p>结清要求：没有排队命令、没有活跃 Model、没有 Tool siblings，head 是非 continuation 的 TURN_END，且本次执行边界之后确实产生了新的条目。
   */
  static Terminal terminal(ThreadSnapshot snapshot, UUID sourceHeadEntryId) {
    if (!snapshot.queuedCommands().isEmpty()
        || snapshot.model() != null
        || !snapshot.toolSiblings().isEmpty()) {
      return null;
    }
    int sourceIndex = indexOf(snapshot, sourceHeadEntryId);
    if (!(snapshot.entryPath().head().payload() instanceof TurnEndPayload end)
        || end.continueModel()
        || sourceIndex < 0
        || sourceIndex >= snapshot.entryPath().entries().size() - 1) {
      return null;
    }
    String report = lastReport(snapshot, sourceHeadEntryId);
    return switch (end.outcome()) {
      case COMPLETED -> new Terminal(Outcome.COMPLETED, report, null, null);
      case FAILED -> new Terminal(
          Outcome.ERROR, null, report, "subagent turn failed: " + end.reason());
      case STOPPED -> new Terminal(Outcome.CANCELLED, null, report, "Cancelled by user.");
      case CANCELLED -> new Terminal(
          Outcome.CANCELLED, null, report, "Cancelled before completion.");
    };
  }

  /** 父 Thread 是否已明确停止（非 continuation 的 STOPPED turn 边界）：停止后只持久保留结果，不自动唤醒。 */
  static boolean stopped(ThreadSnapshot parent) {
    return stoppedHead(parent.entryPath().head());
  }

  /** 事务内判定停止门禁：head Entry 是非 continuation 的 STOPPED turn 边界。 */
  static boolean stoppedHead(Entry headEntry) {
    return headEntry.payload() instanceof TurnEndPayload end
        && !end.continueModel()
        && end.outcome() == TurnEndOutcome.STOPPED;
  }

  /**
   * 本次执行是否已不可能再产生终态：子线程静止，且本次执行边界之后没有产生任何条目。
   *
   * <p>这是真实的持久状态而非猜测：委派 prompt 命令要么仍 QUEUED（静止判定不成立）、要么已被某个 Turn 消费（APPLIED 与 TURN_START 条目在同一事务
   * 提交，因此边界之后必然有条目）。取消 idle 子线程现在会写入 STOP barrier Turn（TURN_START(STOP) → ASSISTANT_ERROR →
   * TURN_END(STOPPED)），因此边界之后有条目、由 {@link #terminal} 正常投影为 CANCELLED；"静止 + 边界之后无条目" 只剩历史回退
   * 或本契约引入之前的旧数据，执行同样永远不会再推进。
   *
   * <p>不结清这种记录会让它永久停在 {@code OPEN}：父 Thread 永久 processing（假性永不静止）、并发额度永久占用、父永远收不到通知。
   */
  static boolean abortedBeforeStart(ThreadSnapshot snapshot, UUID sourceHeadEntryId) {
    if (!snapshot.queuedCommands().isEmpty()
        || snapshot.model() != null
        || !snapshot.toolSiblings().isEmpty()) {
      return false;
    }
    int sourceIndex = indexOf(snapshot, sourceHeadEntryId);
    return sourceIndex < 0 || sourceIndex == snapshot.entryPath().entries().size() - 1;
  }

  /**
   * 从本次执行边界之后统计模型工作 turn 数（不含 COMPACTION 与 STOP），用于 max_turns 软预算。
   *
   * <p>STOP turn 是显式停止的持久屏障而不承载任何模型工作，把它算成一轮会平白消耗子代理的预算。
   */
  static int countTurns(ThreadSnapshot snapshot, UUID sourceHeadEntryId) {
    int start = indexOf(snapshot, sourceHeadEntryId);
    int count = 0;
    for (int i = Math.max(0, start + 1); i < snapshot.entryPath().entries().size(); i++) {
      if (snapshot.entryPath().entries().get(i).payload() instanceof TurnStartPayload turn
          && turn.reason() != TurnStartReason.COMPACTION
          && turn.reason() != TurnStartReason.STOP) {
        count++;
      }
    }
    return count;
  }

  private static String lastReport(ThreadSnapshot snapshot, UUID sourceHeadEntryId) {
    int start = indexOf(snapshot, sourceHeadEntryId);
    String report = null;
    for (int i = Math.max(0, start + 1); i < snapshot.entryPath().entries().size(); i++) {
      var payload = snapshot.entryPath().entries().get(i).payload();
      if (payload instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.ASSISTANT) {
        String text = messageText(message.message());
        if (!text.isBlank()) {
          report = text;
        }
      } else if (payload instanceof AssistantErrorPayload error) {
        String text = error.error().message();
        if (text != null && !text.isBlank()) {
          report = text;
        }
      } else if (payload instanceof AssistantAbortedPayload aborted) {
        String text = messageText(aborted.message());
        if (!text.isBlank()) {
          report = text;
        }
      }
    }
    return report;
  }

  private static String messageText(AgentMessage message) {
    List<String> parts = new ArrayList<>();
    for (AgentMessageContent content : message.contents()) {
      if (content instanceof TextMessageContent text) {
        parts.add(text.text());
      } else if (content instanceof JsonMessageContent json) {
        parts.add(json.json());
      }
    }
    return String.join("", parts).trim();
  }

  private static int indexOf(ThreadSnapshot snapshot, UUID entryId) {
    List<Entry> entries = snapshot.entryPath().entries();
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).id().equals(entryId)) {
        return i;
      }
    }
    return -1;
  }
}
