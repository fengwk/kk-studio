package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException.Reason;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.input.HumanInputAnswers;
import fun.fengwk.kkstudio.harness.runtime.input.HumanInputJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.input.HumanInputQuestionnaire;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInputReceipt;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
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
 * Reason#INPUT_SUBMISSION_MISMATCH}。Stop 已收敛为 CANCELLED 的目标是 {@link
 * Reason#INPUT_SUBMISSION_NOT_APPLICABLE}，迟到回答不会恢复执行。
 */
final class ToolInputControl {

  private static final HumanInputJsonCodec INPUT_JSON = new HumanInputJsonCodec();

  private final HarnessStore store;
  private final Clock clock;

  ToolInputControl(HarnessStore store, Clock clock) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** 在单个短事务内接受或精确 replay 一次人工输入提交，返回已接受（或已物化前）的 invocation。 */
  ToolInvocation submit(ToolInputSubmissionCommand command) {
    Objects.requireNonNull(command, "command");
    return store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(command.threadId()).orElse(null);
          if (thread == null) {
            throw notApplicable("thread " + command.threadId() + " does not exist");
          }
          ToolInvocation probe = tx.findToolInvocation(command.toolInvocationId()).orElse(null);
          if (probe == null) {
            throw notApplicable(
                "tool invocation " + command.toolInvocationId() + " does not exist");
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
   * 已接受调用的精确 replay：提交身份与操作者必须与回执一致，答案必须与已存结果按规范化结构相等，否则拒绝；replay 不触碰任何 durable 事实（不 bump
   * version、不请求 Work）。
   */
  private static ToolInvocation replayAccepted(
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
    return tool;
  }

  /** 首次接受：冻结问卷校验通过后写入结果与回执，推进 Thread version 并登记结果物化 Work。 */
  private ToolInvocation acceptInput(
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
    return accepted;
  }

  /** 冻结问卷只来自 Assistant ToolCall：解析失败是持久化不变量被破坏，绝不猜测问卷。 */
  private static HumanInputQuestionnaire questionnaire(ToolInvocation tool) {
    try {
      return INPUT_JSON.decodeQuestionnaire(tool.call().argumentsJson());
    } catch (IllegalArgumentException broken) {
      throw new IllegalStateException(
          "tool invocation " + tool.id() + " lost its frozen questionnaire", broken);
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
