package fun.fengwk.kkstudio.harness.runtime.join;

import fun.fengwk.kkstudio.harness.api.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.api.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.EntryPayload;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoin;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoinOutcome;
import fun.fengwk.kkstudio.harness.api.join.ThreadJoinReceipt;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 无状态的只读 ThreadJoin 结果凭据投影器。
 *
 * <p>未匹配的 join 返回 {@link Optional#empty()}； 已匹配的 join 以其冻结的 {@code terminalEntryId} 为界，通过 store 加载
 * root-to-head 历史路径， 定位源命令的 {@code appliedEntryId}，并仅对该切片范围内的条目推导终态与报告：
 *
 * <ul>
 *   <li>源命令在执行前被取消（{@code cancelledAt != null} 且 {@code appliedEntryId == null}）投影为 {@link
 *       ThreadJoinOutcome#CANCELLED}，且不包含旧的助手文本；
 *   <li>源命令未被执行（无 {@code appliedEntryId} 且未被取消）或其应用的 turnStart 不在 head 路径上属于不变量破损，抛出 {@link
 *       IllegalStateException}；
 *   <li>正常完成投影为 {@link ThreadJoinOutcome#COMPLETED} 与冻结的 {@code finalAnswerEntryId} 对应的 assistant
 *       文本（最终答复为空即回退占位，绝不借用更早的助手文本）；
 *   <li>失败投影为 {@link ThreadJoinOutcome#ERROR}，分离 error 与 partialResult；
 *   <li>用户停止或取消投影为 {@link ThreadJoinOutcome#CANCELLED}，保留 partialResult 与取消说明。
 * </ul>
 */
public final class ThreadJoinProjector {

  public static final ThreadJoinProjector INSTANCE = new ThreadJoinProjector();

  /**
   * 将一个已匹配的 ThreadJoin 投影为只读的 {@link ThreadJoinReceipt}。
   *
   * @param tx 当前持久化事务句柄，不能为 null
   * @param join 待投影的 join aggregate，不能为 null
   * @return 若 join 尚未匹配返回 empty，否则返回计算出的固定结果凭据
   */
  public Optional<ThreadJoinReceipt> project(HarnessStore.Transaction tx, ThreadJoin join) {
    Objects.requireNonNull(tx, "tx");
    Objects.requireNonNull(join, "join");
    if (!join.matched()) {
      return Optional.empty();
    }

    ThreadCommand sourceCommand =
        tx.commands()
            .find(join.childThreadId(), join.sourceCommandSequence())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "source command sequence "
                            + join.sourceCommandSequence()
                            + " not found for child thread "
                            + join.childThreadId()));

    String prompt = extractPrompt(sourceCommand);
    UUID appliedStart = sourceCommand.appliedEntryId();
    if (appliedStart == null) {
      if (sourceCommand.cancelledAt() != null) {
        return Optional.of(
            new ThreadJoinReceipt(
                join.invocationId(),
                join.childThreadId(),
                join.agent(),
                ThreadJoinOutcome.CANCELLED,
                prompt,
                null,
                null,
                "Cancelled before execution."));
      }
      throw new IllegalStateException(
          "matched join source command sequence "
              + join.sourceCommandSequence()
              + " on child thread "
              + join.childThreadId()
              + " has no appliedEntryId and is not cancelled");
    }

    EntryPath path = tx.entries().loadPath(join.terminalEntryId());
    int startIndex = indexOf(path, appliedStart);
    if (startIndex < 0) {
      throw new IllegalStateException(
          "applied turn start entry "
              + appliedStart
              + " not found on terminal path "
              + join.terminalEntryId()
              + " for child thread "
              + join.childThreadId());
    }

    if (!(path.head().payload() instanceof TurnEndPayload end)) {
      throw new IllegalStateException(
          "result head entry " + join.terminalEntryId() + " is not a TurnEndPayload");
    }

    String lastAssistantText = extractLastAssistantText(path, startIndex);
    String lastErrorText = extractLastErrorText(path, startIndex);

    ThreadJoinOutcome outcome;
    String reportField = null;
    String partialResultField = null;
    String errorField = null;

    switch (end.outcome()) {
      case COMPLETED -> {
        // 正常完成只取冻结的最终回答条目：绝不扫描之前 tool-call 附带的旧文本；最终答复为空即明确占位。
        outcome = ThreadJoinOutcome.COMPLETED;
        reportField = extractFinalAnswerText(path, join.finalAnswerEntryId());
      }
      case FAILED -> {
        outcome = ThreadJoinOutcome.ERROR;
        partialResultField = lastAssistantText;
        errorField =
            lastErrorText != null ? lastErrorText : "subagent turn failed: " + end.reason();
      }
      case STOPPED -> {
        outcome = ThreadJoinOutcome.CANCELLED;
        partialResultField = lastAssistantText;
        errorField = lastErrorText != null ? lastErrorText : "Cancelled by user.";
      }
      case CANCELLED -> {
        outcome = ThreadJoinOutcome.CANCELLED;
        partialResultField = lastAssistantText;
        errorField = lastErrorText != null ? lastErrorText : "Cancelled before completion.";
      }
      default -> throw new IllegalStateException("unsupported turn end outcome: " + end.outcome());
    }

    return Optional.of(
        new ThreadJoinReceipt(
            join.invocationId(),
            join.childThreadId(),
            join.agent(),
            outcome,
            prompt,
            reportField,
            partialResultField,
            errorField));
  }

  /**
   * 正常完成只读取 join 冻结的 {@code finalAnswerEntryId}：没有最终回答则返回 null；存在时必须是切片内的 ASSISTANT
   * MESSAGE，文本为空时保留空串，绝不借用更早的助手文本。
   */
  private static String extractFinalAnswerText(EntryPath path, UUID finalAnswerEntryId) {
    if (finalAnswerEntryId == null) {
      return null;
    }
    for (Entry entry : path.entries()) {
      if (entry.id().equals(finalAnswerEntryId)) {
        if (!(entry.payload() instanceof MessagePayload message)
            || message.message().role() != AgentMessageRole.ASSISTANT) {
          throw new IllegalStateException(
              "frozen final answer entry " + finalAnswerEntryId + " is not an ASSISTANT MESSAGE");
        }
        return extractMessageText(message.message());
      }
    }
    throw new IllegalStateException(
        "frozen final answer entry "
            + finalAnswerEntryId
            + " not found on terminal path "
            + path.head().id());
  }

  private static int indexOf(EntryPath path, UUID entryId) {
    List<Entry> entries = path.entries();
    for (int i = 0; i < entries.size(); i++) {
      if (entries.get(i).id().equals(entryId)) {
        return i;
      }
    }
    return -1;
  }

  private static String extractPrompt(ThreadCommand command) {
    if (command == null) {
      return "";
    }
    if (command.payload() instanceof UserMessageCommandPayload user) {
      return extractMessageText(user.message());
    }
    if (command.payload() instanceof CustomMessageCommandPayload custom) {
      return extractMessageText(custom.message());
    }
    return "";
  }

  private static String extractLastAssistantText(EntryPath path, int startIndex) {
    List<Entry> entries = path.entries();
    String report = null;
    for (int i = startIndex; i < entries.size(); i++) {
      EntryPayload payload = entries.get(i).payload();
      if (payload instanceof MessagePayload message
          && message.message().role() == AgentMessageRole.ASSISTANT) {
        String text = extractMessageText(message.message());
        if (!text.isBlank()) {
          report = text;
        }
      } else if (payload instanceof AssistantAbortedPayload aborted) {
        String text = extractMessageText(aborted.message());
        if (!text.isBlank()) {
          report = text;
        }
      }
    }
    return report;
  }

  private static String extractLastErrorText(EntryPath path, int startIndex) {
    List<Entry> entries = path.entries();
    String error = null;
    for (int i = startIndex; i < entries.size(); i++) {
      EntryPayload payload = entries.get(i).payload();
      if (payload instanceof AssistantErrorPayload errorPayload) {
        String text = errorPayload.error().message();
        if (text != null && !text.isBlank()) {
          error = text;
        }
      }
    }
    return error;
  }

  private static String extractMessageText(AgentMessage message) {
    if (message == null || message.contents() == null) {
      return "";
    }
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
}
