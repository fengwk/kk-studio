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

  /** 原子创建 Session + 初始配置 Entries + Main Thread。 */
  SessionCreateResult createSession(
      long agentDefinitionId,
      String title,
      AgentSnapshot snapshot,
      boolean yoloEnabled,
      Instant now);

  /** 在同一 Session tree 上从任意 durable Entry 新建 Thread cursor（不克隆 Entry）。 */
  AgentThread createThreadFromEntry(long sessionId, long fromEntryId, Instant now);

  /** 排队用户消息；clientMessageId 幂等。 */
  ThreadInput submitUserMessage(
      long threadId, AgentMessage userMessage, String clientMessageId, Instant now);

  ThreadInput submitCustomMessage(
      long threadId, AgentMessage customMessage, String clientMessageId, Instant now);

  ThreadInput submitSetYolo(
      long threadId, boolean yoloEnabled, String clientMessageId, Instant now);

  ThreadInput submitSetAgent(
      long threadId,
      long agentDefinitionId,
      AgentSnapshot snapshot,
      String clientMessageId,
      Instant now);

  ThreadInput submitSetModel(
      long threadId, String modelId, String variant, String clientMessageId, Instant now);

  ThreadInput submitSetToolset(
      long threadId, List<String> tools, String clientMessageId, Instant now);

  /**
   * Steer-all Harvest：在安全边界原子应用 cutoff 内全部 QUEUED inputs。
   *
   * @return 本批是否包含 USER/CUSTOM 消息（配置-only 批为 false）
   */
  HarvestResult harvestQueuedInputs(long threadId, String processorToken, Instant now);

  /** 提交 final assistant（无 tool call），推进 head。 */
  boolean commitFinalAssistant(
      long threadId,
      String processorToken,
      long plannedAssistantEntryId,
      MessageEntryPayload assistant,
      ModelUsageDraft usageDraft,
      List<ThreadEventDraft> events,
      Instant now);

  /** 提交 assistant + tool invocations；head 停在 assistant。 */
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
      boolean yoloEnabled,
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

  /** Provider/setup/tool prepare 失败：写 events，status=FAILED，释放 token。 */
  boolean fail(long threadId, String processorToken, List<ThreadEventDraft> events, Instant now);

  /** FAILED -> RETRYING，写 THREAD_RETRYING。 */
  AgentThread retry(long threadId, Instant now);

  /**
   * 幂等 Stop：取消全部 QUEUED、撤销 processor、status=IDLE、request-cancel open tools。
   *
   * @return 含被取消消息文本的回执；重复 clientRequestId 返回同一批
   */
  StopResult stop(long threadId, String clientRequestId, Instant now);

  /**
   * Atomically admit a model Turn and append {@code TURN_STARTED}, or reject with policy failure
   * events.
   */
  BeginTurnResult beginTurn(long threadId, String processorToken, Instant now);

  /** 仅在已成功偿还失败 Turn 且仍为 RETRYING 时切换为 RUNNING。 */
  boolean completeRetriedTurn(long threadId, String processorToken, Instant now);

  /** 原子写入 WAITING 事件、状态并释放 token；返回 false 表示失去 fencing。 */
  boolean waitForExternal(long threadId, String processorToken, String reason, Instant now);

  /**
   * 原子确认 Thread 没有可推进 durable work 后写入 IDLE 事件、状态并释放 token。
   *
   * <p>{@link QuiescenceResult#WORK_REMAINS} 表示调用方必须继续持有 token 推进， {@link
   * QuiescenceResult#LOST_OWNERSHIP} 表示任何终态写入均不可提交。
   */
  QuiescenceResult quiesce(long threadId, String processorToken, Instant now);

  /** Session 创建结果。 */
  record SessionCreateResult(long sessionId, AgentThread mainThread) {
    public SessionCreateResult {
      if (sessionId <= 0) {
        throw new IllegalArgumentException("sessionId must be positive");
      }
      mainThread = Objects.requireNonNull(mainThread, "mainThread");
    }
  }

  /** Harvest 结果。 */
  record HarvestResult(
      boolean harvested, boolean hasMessage, List<ThreadInput> applied, Long newHeadEntryId) {
    public static HarvestResult none() {
      return new HarvestResult(false, false, List.of(), null);
    }

    public HarvestResult {
      applied = List.copyOf(Objects.requireNonNull(applied, "applied"));
    }
  }

  /** Stop 结果。 */
  record StopResult(
      ThreadStop stop, List<ThreadInput> cancelledInputs, List<String> restoredMessages) {
    public StopResult {
      stop = Objects.requireNonNull(stop, "stop");
      cancelledInputs = List.copyOf(Objects.requireNonNull(cancelledInputs, "cancelledInputs"));
      restoredMessages = List.copyOf(Objects.requireNonNull(restoredMessages, "restoredMessages"));
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

  /** 原子 quiescence 结果。 */
  enum QuiescenceResult {
    IDLE,
    WORK_REMAINS,
    LOST_OWNERSHIP
  }
}
