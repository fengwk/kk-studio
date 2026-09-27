package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException.Reason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.input.HumanInputAnswers;
import fun.fengwk.kkstudio.harness.runtime.input.HumanInputJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.input.HumanInputQuestionnaire;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInputReceipt;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 人工输入提交控制：把一次 {@code ask_user} 回答原子地物化为 ToolResult 与 durable 回执。
 *
 * <p>锁序 Session -&gt; Thread -&gt; Model -&gt; Tool siblings -&gt; Work：未加锁的读仅用于发现不可变的 id/ownership
 * 并选择稳定分支。提交按 Invocation ID 锁定当前真实调用，校验它仍在当前 TOOL_ACTIVE 上下文且状态为 WAITING_INPUT，再按冻结问卷 （Assistant
 * ToolCall 的 arguments）校验并规范化答案；非法提交是 {@link Reason#INPUT_SUBMISSION_INVALID}，不写入任何事实。
 *
 * <p>接受事务依序写入 SUCCEEDED + ToolResult、inputReceipt（submissionId / actor / acceptedAt，与结果同事务）、Thread
 * version +1 与 THREAD Work：结果由既有 terminal Tool 物化路径进入 Entry 并删除调用行，因此崩溃只可能发生在回执落盘之前或之后——之后的重试按
 * 回执与已存答案精确 replay，绝不追加第二条 ToolResult。审批与问答互不代替：本控制不接受 approval，问卷也不能授权工具。
 *
 * <p>已接受但尚未物化的调用（SUCCEEDED + inputReceipt）只接受相同提交身份与相同规范化答案的精确 replay；另一个提交身份、不同操作者或 不同答案都是 {@link
 * Reason#INPUT_SUBMISSION_MISMATCH}。当调用行已被结果物化删除时，本控制按 Entry 的 runtime 元数据（{@code
 * toolResultMetadata.invocationId}）在原 Thread/父链上反查已接受事实并做同样的精确 replay，因此「清理后重试」与「清理前重试」语义一致。 Stop
 * 已收敛为 CANCELLED 的目标是 {@link Reason#INPUT_SUBMISSION_NOT_APPLICABLE}，迟到回答不会恢复执行。
 */
final class ToolInputControl {

  private static final HumanInputJsonCodec INPUT_JSON = new HumanInputJsonCodec();

  private final HarnessStore store;
  private final Clock clock;

  ToolInputControl(HarnessStore store, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** 在单个短事务内接受或精确 replay 一次人工输入提交，返回 durable 接受事实。 */
  ToolInputAcceptance submit(ToolInputSubmissionCommand command) {
    Objects.requireNonNull(command, "command");
    return store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(command.threadId()).orElse(null);
          if (thread == null) {
            throw notApplicable("thread " + command.threadId() + " does not exist");
          }
          ToolInvocation probe = tx.findToolInvocation(command.toolInvocationId()).orElse(null);
          if (probe == null) {
            return replayMaterialized(tx, thread, command);
          }
          ModelInvocation probeModel =
              tx.findModelInvocation(probe.modelInvocationId()).orElse(null);
          if (probeModel == null || !probeModel.threadId().equals(command.threadId())) {
            throw notApplicable(
                "tool invocation "
                    + probe.id()
                    + " does not belong to thread "
                    + command.threadId());
          }
          if (probe.status() == ToolInvocationStatus.SUCCEEDED) {
            return replayAccepted(probe, command);
          }
          LockedThreadContext locked = ThreadContextLock.load(tx, thread);
          if (!(locked.context() instanceof ThreadContext.ToolActive active)) {
            throw notApplicable(
                "tool invocation " + probe.id() + " is not in the current tool context");
          }
          ToolInvocation tool = null;
          for (ToolInvocation sibling : active.siblings()) {
            if (sibling.id().equals(probe.id())) {
              tool = sibling;
              break;
            }
          }
          if (tool == null) {
            throw notApplicable(
                "tool invocation " + probe.id() + " is not in the current tool context");
          }
          if (tool.status() != ToolInvocationStatus.WAITING_INPUT) {
            throw notApplicable("tool invocation " + tool.id() + " is not waiting for user input");
          }
          return acceptInput(tx, thread, locked.path(), active, tool, command);
        });
  }

  /**
   * 调用行已被结果物化删除后的精确 replay：按原调用 ID 在 owning Session 的 Entry 历史中定位已物化的 ToolResult， 核验它确实属于原 Thread
   * 的父链，再按回执与规范化答案做与「未物化」路径一致的精确 replay；缺失或非人工输入结果都是 不适用。
   */
  private ToolInputAcceptance replayMaterialized(
      HarnessStore.Transaction tx, ThreadState thread, ToolInputSubmissionCommand command) {
    Optional<Entry> materialized =
        tx.findToolResultEntryByInvocationId(thread.sessionId(), command.toolInvocationId());
    if (materialized.isEmpty()) {
      throw notApplicable("tool invocation " + command.toolInvocationId() + " does not exist");
    }
    Entry entry = materialized.get();
    if (!onOwnedThreadPath(tx, entry, thread)) {
      throw notApplicable(
          "tool invocation "
              + command.toolInvocationId()
              + " does not belong to thread "
              + thread.id());
    }
    if (!(entry.payload() instanceof MessagePayload message)
        || message.toolResultMetadata() == null) {
      throw notApplicable(
          "tool invocation " + command.toolInvocationId() + " is not a tool result");
    }
    ToolResultMetadata metadata = message.toolResultMetadata();
    ToolInputReceipt receipt = metadata.inputReceipt();
    if (receipt == null) {
      throw notApplicable(
          "tool invocation " + command.toolInvocationId() + " is not waiting for user input");
    }
    if (!receipt.replays(command.submissionId(), command.actor())) {
      throw mismatch(
          "tool invocation "
              + command.toolInvocationId()
              + " was already answered by another submission");
    }
    String canonical = canonicalAnswerJson(materializedQuestionnaire(tx, metadata), command);
    if (!canonical.equals(materializedDetails(message))) {
      throw mismatch(
          "submission "
              + command.submissionId()
              + " does not replay the accepted answers of tool invocation "
              + command.toolInvocationId());
    }
    return new ToolInputAcceptance(thread.id(), command.toolInvocationId(), receipt, true);
  }

  /** 已物化 ToolResult 必须落在原 Thread 发起的 turn 上（沿 Entry 父链找到属于该 Thread 的 TURN_START）。 */
  private static boolean onOwnedThreadPath(
      HarnessStore.Transaction tx, Entry entry, ThreadState thread) {
    EntryPath path;
    try {
      path = tx.loadEntryPath(entry.id());
    } catch (IllegalArgumentException broken) {
      throw new IllegalStateException(
          "materialized tool result " + entry.id() + " lost its entry path", broken);
    }
    for (Entry ancestor : path.entries()) {
      if (ancestor.payload() instanceof TurnStartPayload turnStart
          && turnStart.ownerThreadId().equals(thread.id())) {
        return true;
      }
    }
    return false;
  }

  /** 已物化答案的唯一事实源是 TOOL 消息的 result content detailsJson（与业务 details 同一份）。 */
  private static String materializedDetails(MessagePayload message) {
    List<AgentMessageContent> contents = message.message().contents();
    if (message.message().role() != AgentMessageRole.TOOL
        || contents.isEmpty()
        || !(contents.get(0) instanceof ToolResultMessageContent result)) {
      throw new IllegalStateException("materialized tool result lost its tool result content");
    }
    return result.detailsJson();
  }

  /**
   * 已物化后冻结问卷仍只在 Assistant ToolCall 里：按 metadata 指向的 Assistant Entry 与 toolCallId 取回原始 arguments
   * 并解码；缺失或不可解码是持久化不变量被破坏，绝不猜测问卷。
   */
  private static HumanInputQuestionnaire materializedQuestionnaire(
      HarnessStore.Transaction tx, ToolResultMetadata metadata) {
    Entry assistant =
        tx.findEntry(metadata.assistantEntryId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "answered tool result lost its assistant entry "
                            + metadata.assistantEntryId()));
    if (!(assistant.payload() instanceof MessagePayload message)
        || message.message().role() != AgentMessageRole.ASSISTANT) {
      throw new IllegalStateException(
          "answered tool result assistant entry is not an assistant message");
    }
    for (AgentMessageContent content : message.message().contents()) {
      if (content instanceof ToolCallMessageContent call
          && call.toolCallId().equals(metadata.toolCallId())) {
        return decodeQuestionnaire(call.argumentsJson());
      }
    }
    throw new IllegalStateException("answered tool result lost its frozen tool call");
  }

  /**
   * 已接受调用行的精确 replay：提交身份与操作者必须与回执一致，答案必须与已存结果按规范化结构相等，否则拒绝；replay 不触碰任何 durable 事实（不 bump
   * version、不请求 Work）。
   */
  private static ToolInputAcceptance replayAccepted(
      ToolInvocation tool, ToolInputSubmissionCommand command) {
    ToolInputReceipt receipt = tool.inputReceipt();
    if (receipt == null) {
      throw notApplicable("tool invocation " + tool.id() + " is not waiting for user input");
    }
    if (!receipt.replays(command.submissionId(), command.actor())) {
      throw mismatch(
          "tool invocation " + tool.id() + " was already answered by another submission");
    }
    String submitted = canonicalAnswerJson(questionnaire(tool), command);
    if (!submitted.equals(tool.result().detailsJson())) {
      throw mismatch(
          "submission "
              + command.submissionId()
              + " does not replay the accepted answers of tool invocation "
              + tool.id());
    }
    return new ToolInputAcceptance(command.threadId(), tool.id(), receipt, false);
  }

  /** 首次接受：冻结问卷校验通过后写入结果与回执，推进 Thread version 并登记结果物化 Work。 */
  private ToolInputAcceptance acceptInput(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      ThreadContext.ToolActive active,
      ToolInvocation tool,
      ToolInputSubmissionCommand command) {
    Instant workNow = clock.instant();
    Instant mutationNow =
        HarnessStoreTime.notBefore(
            workNow, thread.updatedAt(), path.head().createdAt(), active.model().updatedAt());
    for (ToolInvocation sibling : active.siblings()) {
      mutationNow = HarnessStoreTime.notBefore(mutationNow, sibling.updatedAt());
    }
    String canonical = canonicalAnswerJson(questionnaire(tool), command);
    ToolResult result =
        new ToolResult(
            tool.call().id(), List.of(new JsonResultContent(canonical)), false, canonical);
    ToolInputReceipt receipt =
        new ToolInputReceipt(command.submissionId(), command.actor(), mutationNow);
    ToolInvocation accepted = tool.acceptInput(result, receipt, mutationNow);
    tx.updateToolInvocations(List.of(accepted));
    tx.updateThread(thread.touchVersion(mutationNow));
    tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()), workNow);
    return new ToolInputAcceptance(thread.id(), accepted.id(), receipt, false);
  }

  /** 冻结问卷只来自 Assistant ToolCall：解析失败是持久化不变量被破坏，绝不猜测问卷。 */
  private static HumanInputQuestionnaire questionnaire(ToolInvocation tool) {
    return decodeQuestionnaire(tool.call().argumentsJson());
  }

  private static HumanInputQuestionnaire decodeQuestionnaire(String argumentsJson) {
    try {
      return INPUT_JSON.decodeQuestionnaire(argumentsJson);
    } catch (IllegalArgumentException broken) {
      throw new IllegalStateException("ask_user lost its frozen questionnaire", broken);
    }
  }

  /** 按冻结问卷校验并编码答案；不合法提交是类型化冲突，不写入任何 durable 事实。 */
  private static String canonicalAnswerJson(
      HumanInputQuestionnaire questionnaire, ToolInputSubmissionCommand command) {
    HumanInputAnswers answers;
    try {
      answers = HumanInputAnswers.accept(questionnaire, command.declined(), command.answers());
    } catch (IllegalArgumentException invalid) {
      throw new HarnessRuntimeConflictException(
          Reason.INPUT_SUBMISSION_INVALID, invalid.getMessage());
    }
    return INPUT_JSON.encodeAnswers(answers);
  }

  private static HarnessRuntimeConflictException notApplicable(String message) {
    return new HarnessRuntimeConflictException(Reason.INPUT_SUBMISSION_NOT_APPLICABLE, message);
  }

  private static HarnessRuntimeConflictException mismatch(String message) {
    return new HarnessRuntimeConflictException(Reason.INPUT_SUBMISSION_MISMATCH, message);
  }
}
