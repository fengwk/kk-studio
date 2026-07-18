package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentSnapshot;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageDraft;
import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** 跨 Thread/Input/Entry/Tool/Usage 的原子提交边界；所有 mutation 校验 processor token。 */
public interface ThreadTransactions {

  /** 创建根 Thread：Session + 初始 agent snapshot entry + Thread。 */
  AgentThread createRootThread(
      long agentDefinitionId,
      String title,
      AgentSnapshot snapshot,
      String runtimeConfigJson,
      boolean yoloEnabled,
      Instant now);

  /**
   * 在同一 Session tree 上新建 Thread cursor（不克隆 Entry）。{@code fromEntryId} 为 head；为空则使用 session 最新根路径
   * tip 不成立——必须显式给出 fromEntryId 或仅从已有 head 分叉。
   */
  AgentThread createThreadFromEntry(
      long sessionId,
      long fromEntryId,
      Long agentDefinitionId,
      String runtimeConfigJson,
      boolean yoloEnabled,
      Instant now);

  /** 排队用户消息；clientMessageId 幂等。 */
  ThreadInput submitUserMessage(
      long threadId, AgentMessage userMessage, String clientMessageId, Instant now);

  ThreadInput submitSetYolo(long threadId, boolean yoloEnabled, Instant now);

  ThreadInput submitSetAgent(
      long threadId,
      long agentDefinitionId,
      AgentSnapshot snapshot,
      String runtimeConfigJson,
      Instant now);

  /** 在 turn 边界 exactly-once 应用下一条 pending input 并推进 head。 */
  ApplyInputResult applyNextInput(long threadId, String processorToken, Instant now);

  /** 提交 final assistant（无 tool call），推进 head。 */
  boolean commitFinalAssistant(
      long threadId,
      String processorToken,
      long plannedAssistantEntryId,
      MessageEntryPayload assistant,
      ModelUsageDraft usageDraft,
      List<ThreadEventDraft> events,
      Instant now);

  /** 提交 assistant + tool invocations；不推进到 tool results，head 停在 assistant。 */
  boolean prepareTools(
      long threadId,
      String processorToken,
      long plannedAssistantEntryId,
      MessageEntryPayload assistant,
      ModelUsageDraft usageDraft,
      List<ToolCall> toolCalls,
      List<ToolBinding> bindings,
      Path workdir,
      Path environmentRoot,
      List<ThreadEventDraft> events,
      Instant now);

  /** 全部 tool 终态后，按 ordinal 追加 tool result entries 并推进 head。 */
  boolean applyTerminalToolResults(long threadId, String processorToken, Instant now);

  boolean compact(
      long threadId,
      String processorToken,
      CompactionEntryPayload compaction,
      List<ThreadEventDraft> events,
      Instant now);

  boolean appendEvents(
      long threadId, String processorToken, List<ThreadEventDraft> events, Instant now);

  boolean fail(long threadId, String processorToken, List<ThreadEventDraft> events, Instant now);

  /**
   * Atomically admit a model Turn and append {@code TURN_STARTED}, or reject with policy failure
   * events.
   *
   * <p>One transaction: lock/verify owning Thread, evaluate admission, then either append {@code
   * TURN_STARTED} ({@link BeginTurnStatus#ADMITTED}) or append ASSISTANT_FAILED + THREAD_FAILED
   * ({@link BeginTurnStatus#REJECTED}). Caller must not invoke Provider unless status is ADMITTED.
   * Token mismatch yields {@link BeginTurnStatus#LOST_OWNERSHIP}.
   */
  BeginTurnResult beginTurn(long threadId, String processorToken, Instant now);

  /**
   * 在持有 processor token 时尝试空闲释放。
   *
   * <p>事务内锁定 thread 行并校验 token 后，检查是否存在仍需本节点立即推进的 durable work（未应用 input、未终态 tool、当前 head 下已终态但尚未
   * apply 的 tool results）。若无则清除 token 并返回 {@code true}；若 token 已丢失也返回 {@code true}。若仍有 work 则保留
   * token 并返回 {@code false}， 调用方必须继续处理循环。
   */
  boolean releaseIfIdle(long threadId, String processorToken, Instant now);

  /**
   * WAITING_EXTERNAL 路径的原子释放。
   *
   * <p>锁定 Thread 行后仅在 durable 状态仍表明存在外部非终态 tool/permission 工作时释放 token；若 tool 终态结果已可 apply
   * 或不再需要外部等待，则保留 token 并返回 {@code false} 让 processor 继续。
   */
  boolean releaseForExternalWait(long threadId, String processorToken, Instant now);

  /** applyNextInput 的结果。 */
  record ApplyInputResult(boolean applied, ThreadInput input, Long newHeadEntryId) {
    public static ApplyInputResult none() {
      return new ApplyInputResult(false, null, null);
    }
  }

  /** beginTurn 结果：仅 ADMITTED 后可调用 Provider。 */
  enum BeginTurnStatus {
    ADMITTED,
    REJECTED,
    LOST_OWNERSHIP
  }

  /** beginTurn 的原子结果。 */
  record BeginTurnResult(BeginTurnStatus status, String rejectionReason) {
    public static BeginTurnResult admitted() {
      return new BeginTurnResult(BeginTurnStatus.ADMITTED, null);
    }

    public static BeginTurnResult rejected(String reason) {
      return new BeginTurnResult(
          BeginTurnStatus.REJECTED, Objects.requireNonNull(reason, "reason"));
    }

    public static BeginTurnResult lostOwnership() {
      return new BeginTurnResult(BeginTurnStatus.LOST_OWNERSHIP, null);
    }

    public boolean isAdmitted() {
      return status == BeginTurnStatus.ADMITTED;
    }

    public boolean isRejected() {
      return status == BeginTurnStatus.REJECTED;
    }

    public boolean isLostOwnership() {
      return status == BeginTurnStatus.LOST_OWNERSHIP;
    }
  }
}
