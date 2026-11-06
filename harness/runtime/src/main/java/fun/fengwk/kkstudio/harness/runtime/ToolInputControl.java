package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.api.HarnessConflictReason;
import fun.fengwk.kkstudio.harness.api.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.api.HarnessRuntimeConflictException.Reason;
import fun.fengwk.kkstudio.harness.api.ToolApprovalCommand;
import fun.fengwk.kkstudio.harness.api.ToolInputAcceptance;
import fun.fengwk.kkstudio.harness.api.ToolInputSubmissionCommand;
import fun.fengwk.kkstudio.harness.api.ToolOutcomeView;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.session.JsonMessageContent;
import fun.fengwk.kkstudio.harness.api.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.api.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.api.tool.ToolInputReceipt;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.input.HumanInputAnswers;
import fun.fengwk.kkstudio.harness.runtime.input.HumanInputJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.input.HumanInputQuestionnaire;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;
import fun.fengwk.kkstudio.harness.spi.work.WorkTarget;
import fun.fengwk.kkstudio.harness.spi.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 工具人工控制：接受 {@code ask_user} 回答，并决策一次必需的 Tool approval。
 *
 * <p>回答提交把一次 {@code ask_user} 回答原子地物化为 ToolResult 与 durable 回执。只锁本 Thread 与本 Thread 的 Model/Tool：
 * Thread -&gt; Model -&gt; Tool siblings -&gt; Work，不取执行树锁；被回答的调用与写回事实都属于本 Thread，不涉及祖先或后代。
 * 未加锁的读仅用于发现不可变的 id/ownership 并选择稳定分支。提交先锁定 Thread，再按 Invocation ID 锁定当前真实调用，校验它仍在当前 TOOL_ACTIVE
 * 上下文且状态为 WAITING_INPUT，再按冻结问卷 （Assistant ToolCall 的 arguments）校验并规范化答案；非法提交是 {@link
 * Reason#INPUT_SUBMISSION_INVALID}，不写入任何事实。
 *
 * <p>接受事务依序写入 SUCCEEDED + ToolResult、inputReceipt（submissionId / actor / acceptedAt，与结果同事务）、Thread
 * version +1 与 THREAD Work：结果由既有 terminal Tool 物化路径进入 Entry 并删除调用行，因此崩溃只可能发生在回执落盘之前或之后——之后的重试按
 * 回执与已存答案精确 replay，绝不追加第二条 ToolResult。审批与问答互不代替：回答不会授权工具，审批也不会代替回答。
 *
 * <p>已接受但尚未物化的调用（SUCCEEDED + inputReceipt）只接受相同提交身份与相同规范化答案的精确 replay；另一个提交身份、不同操作者或 不同答案都是 {@link
 * Reason#INPUT_SUBMISSION_MISMATCH}。当调用行已被结果物化删除时，本控制按 Entry 的 runtime 元数据（{@code
 * toolResultMetadata.invocationId}）在原 Thread/父链上反查已接受事实并做同样的精确 replay，因此「清理后重试」与「清理前重试」语义一致。 Stop
 * 已收敛为 CANCELLED 的目标是 {@link Reason#INPUT_SUBMISSION_NOT_APPLICABLE}，迟到回答不会恢复执行。
 *
 * <p>审批决策只接受已锁定当前 TOOL_ACTIVE context 内处于 WAITING_APPROVAL 的调用：ALLOWED 转 READY 并安排 TOOL Work，
 * DENIED 转 FAILED 并唤醒 THREAD；已决策 approval 以新的 {@code now} 做精确 replay，不递增 version、不请求 Work；不匹配为
 * {@link Reason#APPROVAL_DECISION_MISMATCH}。
 */
final class ToolInputControl {

  private static final HumanInputJsonCodec INPUT_JSON = new HumanInputJsonCodec();

  private final HarnessStore store;

  ToolInputControl(HarnessStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  /** 在单个短事务内接受或精确 replay 一次人工输入提交，返回 durable 接受事实。 */
  ToolInputAcceptance submit(ToolInputSubmissionCommand command) {
    Objects.requireNonNull(command, "command");
    return store.transaction(
        tx -> {
          ThreadState thread = tx.threads().lock(command.threadId()).orElse(null);
          if (thread == null) {
            throw notApplicable("thread " + command.threadId() + " does not exist");
          }
          ToolInvocation probe = tx.tools().find(command.toolInvocationId()).orElse(null);
          if (probe == null) {
            return replayMaterialized(tx, thread, command);
          }
          if (!probe.threadId().equals(command.threadId())) {
            throw notApplicable(
                "tool invocation "
                    + probe.id()
                    + " does not belong to thread "
                    + command.threadId());
          }
          if (probe.status() == ToolInvocationStatus.SUCCEEDED) {
            return replayAccepted(probe, command);
          }
          LockedThreadContext locked = ThreadContextLock.loadTools(tx, thread);
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
          return acceptInput(
              tx, thread, tx.entries().loadPath(thread.headEntryId()), active, tool, command);
        });
  }

  /**
   * 在单个短 transaction 内决策一次必需的 Tool approval。
   *
   * <p>只锁本 Thread 与本 Thread 的 Model/Tool：Thread -&gt; Model -&gt; Tool siblings -&gt;
   * Work，不取执行树锁；目标 approval 及其 owning Model 都属本 Thread，写回不涉及祖先或后代。未加锁的读仅用于发现不可变的 id/ownership 并选择
   * 稳定分支——approval 决策由 Thread 锁串行化，决策后即不可变。已决策 approval 在按规范顺序锁定 owning Model 与 target Tool 之后，通过
   * {@link ToolApproval#decide} 以新的 {@code now} 验证其作为精确 replay，并返回已锁定的当前 ToolInvocation（保留原始 {@code
   * decidedAt}，不要求当前 branch/status，不递增 version，不请求 Work）；任何不匹配均为 APPROVAL_DECISION_MISMATCH。
   */
  ToolInvocation decideApproval(ToolApprovalCommand command) {
    Objects.requireNonNull(command, "command");
    return store.transaction(
        tx -> {
          ThreadState thread = tx.threads().lock(command.threadId()).orElse(null);
          if (thread == null) {
            throw approvalNotApplicable("thread " + command.threadId() + " does not exist");
          }
          ToolInvocation probe = tx.tools().find(command.toolInvocationId()).orElse(null);
          if (probe == null) {
            throw approvalNotApplicable(
                "tool invocation " + command.toolInvocationId() + " does not exist");
          }
          if (!probe.threadId().equals(command.threadId())) {
            throw approvalNotApplicable(
                "tool invocation "
                    + probe.id()
                    + " does not belong to thread "
                    + command.threadId());
          }
          ToolApproval probeApproval = probe.approval();
          if (probeApproval == null || !probeApproval.required()) {
            throw approvalNotApplicable(
                "tool invocation " + probe.id() + " has no required approval");
          }
          if (probeApproval.decision() != null) {
            return replayDecidedApproval(tx, probe, command);
          }
          return decideUndecidedApproval(tx, thread, probe, command);
        });
  }

  /**
   * 调用行已被结果物化删除后的精确 replay：复用公共 {@link ToolOutcomeQuery} 按原调用 ID 从当前 Session 的 ToolResult Entry
   * 读取稳定结果，并核验它属于原 Thread；再按回执与规范化答案做与「未物化」路径一致的精确 replay。
   */
  private ToolInputAcceptance replayMaterialized(
      HarnessStore.Transaction tx, ThreadState thread, ToolInputSubmissionCommand command) {
    ToolOutcomeView outcome =
        ToolOutcomeQuery.find(tx, thread, command.toolInvocationId())
            .orElseThrow(
                () ->
                    notApplicable(
                        "tool invocation " + command.toolInvocationId() + " does not exist"));
    ToolInputReceipt receipt = outcome.inputReceipt();
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
    String canonical =
        canonicalAnswerJson(
            materializedQuestionnaire(tx, outcome.assistantEntryId(), outcome.toolCallId()),
            command);
    if (!canonical.equals(materializedAnswers(outcome))) {
      throw mismatch(
          "submission "
              + command.submissionId()
              + " does not replay the accepted answers of tool invocation "
              + command.toolInvocationId());
    }
    return new ToolInputAcceptance(thread.id(), command.toolInvocationId(), receipt, true);
  }

  /** 人工输入结果的唯一 durable 答案载荷是 TOOL 消息中的 JSON 内容。 */
  private static String materializedAnswers(ToolOutcomeView outcome) {
    if (outcome.contents().size() != 1
        || !(outcome.contents().getFirst() instanceof JsonMessageContent json)) {
      throw new IllegalStateException("answered tool result lost its JSON answer content");
    }
    return json.json();
  }

  /**
   * 已物化后冻结问卷仍只在 Assistant ToolCall 里：按 Assistant Entry 与 toolCallId 取回原始 arguments 并解码；缺失或不可解码是
   * 持久化不变量被破坏，绝不猜测问卷。
   */
  private static HumanInputQuestionnaire materializedQuestionnaire(
      HarnessStore.Transaction tx, UUID assistantEntryId, String toolCallId) {
    Entry assistant =
        tx.entries()
            .find(assistantEntryId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "answered tool result lost its assistant entry " + assistantEntryId));
    if (!(assistant.payload() instanceof MessagePayload message)
        || message.message().role() != AgentMessageRole.ASSISTANT) {
      throw new IllegalStateException(
          "answered tool result assistant entry is not an assistant message");
    }
    for (AgentMessageContent content : message.message().contents()) {
      if (content instanceof ToolCallMessageContent call && call.toolCallId().equals(toolCallId)) {
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
    Instant now = tx.now();
    String canonical = canonicalAnswerJson(questionnaire(tool), command);
    ToolResult result =
        new ToolResult(
            tool.call().id(), List.of(new JsonResultContent(canonical)), false, canonical);
    ToolInputReceipt receipt = new ToolInputReceipt(command.submissionId(), command.actor(), now);
    ToolInvocation accepted = tool.acceptInput(result, receipt, now);
    tx.tools().update(List.of(accepted));
    tx.threads().update(thread.touchVersion(now));
    tx.work().request(new WorkTarget(WorkTargetType.THREAD, thread.id()));
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

  /**
   * 已决策 approval：按公共锁序 Thread -&gt; Tool 锁定 target，并对照已锁定的当前 approval 验证精确 replay，返回已锁定的当前
   * ToolInvocation。工具归属固定在 Thread/TurnStart/Assistant，不读取 Model。
   */
  private ToolInvocation replayDecidedApproval(
      HarnessStore.Transaction tx, ToolInvocation probe, ToolApprovalCommand command) {
    ToolInvocation tool =
        tx.tools()
            .lock(probe.id())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "tool invocation "
                            + probe.id()
                            + " could not be locked for approval replay"));
    if (!tool.threadId().equals(command.threadId())
        || !tool.turnStartEntryId().equals(probe.turnStartEntryId())
        || !tool.assistantEntryId().equals(probe.assistantEntryId())) {
      throw new IllegalStateException(
          "tool invocation " + tool.id() + " changed its ownership during approval replay");
    }
    ToolApproval approval = tool.approval();
    if (approval == null || !approval.required() || approval.decision() == null) {
      throw new IllegalStateException(
          "tool invocation " + tool.id() + " lost its required decided approval during replay");
    }
    try {
      Instant now = tx.now();
      ToolApproval replayed =
          approval.decide(
              command.decision(), command.decisionId(), command.actor(), command.reason(), now);
      if (replayed != approval) {
        throw new IllegalStateException("a decided approval must replay exactly");
      }
    } catch (IllegalArgumentException error) {
      throw conflict(
          HarnessConflictReason.APPROVAL_DECISION_MISMATCH,
          "approval of tool invocation " + tool.id() + " does not replay the stored decision");
    }
    return tool;
  }

  /**
   * 未决策 approval：target 必须是已锁定当前 TOOL_ACTIVE context 内、状态为 WAITING_APPROVAL 的 invocation；transition
   * basis 为已锁定 siblings 中的 ToolInvocation。Thread version 恰好被触碰一次，并请求匹配的 Work target。
   */
  private ToolInvocation decideUndecidedApproval(
      HarnessStore.Transaction tx,
      ThreadState thread,
      ToolInvocation probe,
      ToolApprovalCommand command) {
    LockedThreadContext locked = ThreadContextLock.loadTools(tx, thread);
    if (!(locked.context() instanceof ThreadContext.ToolActive active)) {
      throw approvalNotApplicable(
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
      throw approvalNotApplicable(
          "tool invocation " + probe.id() + " is not in the current tool context");
    }
    if (tool.status() != ToolInvocationStatus.WAITING_APPROVAL) {
      throw approvalNotApplicable("tool invocation " + tool.id() + " is not waiting for approval");
    }
    ToolApproval approval = tool.approval();
    if (approval == null || !approval.required() || approval.decision() != null) {
      throw new IllegalStateException(
          "tool invocation " + tool.id() + " changed its approval state before being decided");
    }
    Instant mutationNow = tx.now();
    ToolInvocation updated =
        tool.decideApproval(
            command.decision(),
            command.decisionId(),
            command.actor(),
            command.reason(),
            mutationNow,
            mutationNow);
    tx.tools().update(List.of(updated));
    tx.threads().update(thread.touchVersion(mutationNow));
    if (command.decision() == ToolApprovalDecision.ALLOWED) {
      // 批准后重建 Work 时同样只要求环境专用工具：逐调用路由的工具不得因为 vfs: 路径等待环境。
      EnvironmentId environmentId =
          tool.binding() != null
                  && tool.binding().environmentSupport() == EnvironmentSupport.REQUIRED
              ? tool.binding().environmentId()
              : null;
      tx.work().request(new WorkTarget(WorkTargetType.TOOL, tool.id()), environmentId);
    } else {
      tx.work().request(new WorkTarget(WorkTargetType.THREAD, thread.id()));
    }
    return updated;
  }

  private static DomainException conflict(HarnessConflictReason reason, String message) {
    return DomainException.conflict(reason.name(), message);
  }

  private static DomainException approvalNotApplicable(String message) {
    return DomainException.conflict(HarnessConflictReason.APPROVAL_NOT_APPLICABLE.name(), message);
  }

  private static HarnessRuntimeConflictException notApplicable(String message) {
    return new HarnessRuntimeConflictException(Reason.INPUT_SUBMISSION_NOT_APPLICABLE, message);
  }

  private static HarnessRuntimeConflictException mismatch(String message) {
    return new HarnessRuntimeConflictException(Reason.INPUT_SUBMISSION_MISMATCH, message);
  }
}
