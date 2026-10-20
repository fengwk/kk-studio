package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.join.JoinDelivery;
import fun.fengwk.kkstudio.harness.runtime.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@link AgentSpawner} 的统一请求：一次「创建（或续用）Agent Thread + 首批输入 + 票据」的全部事实。
 *
 * <p>身份由调用方预分配以保证幂等：新建 Thread 需要 {@code sessionId} 与 {@code threadId}；续用既有 Thread（task 的 {@code
 * thread_id} 续接、Issue 的稳定 Thread）时 {@code sessionId} 为 null，{@code threadId} 指向既有 Thread 并由 {@code
 * continuation} 给出精确 cursor，其创建来源与约束沿用创建时的冻结事实。{@code ticketId} 是票据（Join）的 invocation id，{@code
 * requestHash} 是票据的请求指纹。
 *
 * <p>{@code parentThreadId} 为空表示执行根（票据只冻结回执）；非空表示子 Thread，其有效约束是 {@code constraints} 与父约束的合并。{@code
 * delivery} 为 {@link JoinDelivery#NOTIFY_PARENT} 时必须给出父 Thread 当前的 head 作为准入 cursor。{@code
 * countsTowardQuota} 决定本票据是否计入子任务并发额度。
 *
 * @param settings 新 Thread 的 root settings；同时确定票据记录的 Agent 名
 * @param commands 首批命令（可含 SET_* 前缀，恰以一条 user-like 输入结尾）
 * @param preflight 同事务内的命令物化端口，仅对全新 batch 调用
 * @param yoloEnabled 新执行根的初始 YOLO 开关；子 Thread 与续用恒为 false
 */
public record SpawnRequest(
    UUID threadId,
    UUID sessionId,
    BranchSettings settings,
    List<NewThreadCommand> commands,
    AcceptancePreflight preflight,
    UUID parentThreadId,
    UUID expectedParentHeadEntryId,
    Continuation continuation,
    boolean yoloEnabled,
    ThreadConstraints constraints,
    SpawnTrigger trigger,
    JoinDelivery delivery,
    boolean countsTowardQuota,
    Budget budget,
    UUID ticketId,
    String requestHash) {

  private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");

  public SpawnRequest {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(settings, "settings");
    Objects.requireNonNull(preflight, "preflight");
    Objects.requireNonNull(constraints, "constraints");
    Objects.requireNonNull(trigger, "trigger");
    Objects.requireNonNull(delivery, "delivery");
    Objects.requireNonNull(budget, "budget");
    Objects.requireNonNull(ticketId, "ticketId");
    commands = requireCommandBatch(commands);
    if (trigger == SpawnTrigger.USER) {
      throw new IllegalArgumentException("user-created threads are accepted via acceptCommands");
    }
    if (requestHash == null || !HASH.matcher(requestHash).matches()) {
      throw new IllegalArgumentException("requestHash must be lowercase SHA-256 hex");
    }
    if ((continuation == null) == (sessionId == null)) {
      throw new IllegalArgumentException(
          "a new thread needs a sessionId and a continued thread must not carry one");
    }
    if ((delivery == JoinDelivery.RECEIPT_ONLY) != (parentThreadId == null)) {
      throw new IllegalArgumentException("only a parentless ticket is receipt-only");
    }
    if ((delivery == JoinDelivery.NOTIFY_PARENT) != (expectedParentHeadEntryId != null)) {
      throw new IllegalArgumentException("only a notify-parent ticket carries the parent head");
    }
    if (countsTowardQuota && parentThreadId == null) {
      throw new IllegalArgumentException("only a ticket with a parent counts toward the quota");
    }
    if (yoloEnabled && (parentThreadId != null || continuation != null)) {
      throw new IllegalArgumentException("only a new execution root takes the yolo switch");
    }
  }

  /** 续用既有 Thread 的精确 cursor（head 与下一条 command sequence）。 */
  public record Continuation(UUID expectedHeadEntryId, long expectedNextCommandSequence) {
    public Continuation {
      Objects.requireNonNull(expectedHeadEntryId, "expectedHeadEntryId");
      if (expectedNextCommandSequence <= 0) {
        throw new IllegalArgumentException("expectedNextCommandSequence must be positive");
      }
    }
  }

  /** 票据预算：{@code maxTurns} 是本次输入的软 turn 预算（可空）；其余是准入上限——执行树深度、同一父 Thread 的未完成计额票据数、全局未完成计额票据数。 */
  public record Budget(
      Integer maxTurns, int maxDepth, int maxConcurrentChildren, int maxConcurrentThreads) {

    public Budget {
      if (maxTurns != null && maxTurns <= 0) {
        throw new IllegalArgumentException("maxTurns must be positive");
      }
      if (maxDepth < 1 || maxConcurrentChildren < 1 || maxConcurrentThreads < 1) {
        throw new IllegalArgumentException("admission limits must be positive");
      }
    }

    /** 不设任何准入上限（不计额票据使用）。 */
    public static Budget unlimited() {
      return new Budget(null, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
    }
  }

  /** 票据记录的 Agent 名，取自 settings。 */
  public String agent() {
    return settings.agentName();
  }

  private static List<NewThreadCommand> requireCommandBatch(List<NewThreadCommand> commands) {
    Objects.requireNonNull(commands, "commands");
    if (commands.isEmpty()) {
      throw new IllegalArgumentException("commands must not be empty");
    }
    List<NewThreadCommand> copied = List.copyOf(commands);
    Set<UUID> keys = new HashSet<>();
    for (NewThreadCommand command : copied) {
      if (!keys.add(command.idempotencyKey())) {
        throw new IllegalArgumentException(
            "commands must not contain duplicate idempotencyKey: " + command.idempotencyKey());
      }
    }
    return copied;
  }
}
