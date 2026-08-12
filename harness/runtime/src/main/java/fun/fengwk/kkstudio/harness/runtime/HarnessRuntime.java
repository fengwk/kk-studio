package fun.fengwk.kkstudio.harness.runtime;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandBatch;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Root Harness command/control/query 平面：durable Agent Runtime 的同步公共入口。
 *
 * <p>每个方法严格执行一次 {@link HarnessStore} transaction，使用规范锁序 Thread -&gt; Commands -&gt; ModelInvocation
 * -&gt; ToolInvocation siblings -&gt; Work，从而保证 command enqueue、head relocation、Tool approval 与
 * snapshot 永不观察到混合的 durable 状态。所有业务拒绝均为类型化 {@link HarnessRuntimeConflictException} / {@link
 * HarnessRuntimeNotFoundException}；被破坏的持久化 不变量（所有权错误、sibling 混合挂接、ordinal 不连续）仍为 {@link
 * IllegalStateException}。对已存在 Thread 的 mutation 在相关 durable 锁之后读取时间戳，因此 lock-wait 不会让过期的 pre-lock
 * instant 让 {@code updatedAt} 回退；Stop 与未决 Approval 还会把 mutation 时间钳制到最新的已锁定 durable fact，以容忍本地时钟回滚与
 * 跨节点时钟偏差，而 Work request 始终使用未抬升的本地调度时钟。
 *
 * <p>本切片实现 {@link #createThread}、{@link #enqueueCommands}、{@link #moveHead}、{@link #stop}、 {@link
 * #decideToolApproval} 与 {@link #getThreadSnapshot}。
 */
@Slf4j
public final class HarnessRuntime {

  private static final Consumer<UUID> NO_OP_CANCELLER = ignored -> {};

  private final HarnessStore store;
  private final Clock clock;
  private final StopControl stopControl;
  private final Consumer<UUID> modelExecutionCanceller;
  private final Consumer<UUID> toolExecutionCanceller;

  /** 创建完整 Runtime facade，包含 durable Stop commit 之后的 process-local execution 取消能力。 */
  public HarnessRuntime(
      HarnessStore store, Clock clock, ModelProcessor modelProcessor, ToolProcessor toolProcessor) {
    this(
        store,
        clock,
        null,
        modelExecutionCanceller(modelProcessor),
        toolExecutionCanceller(toolProcessor));
  }

  /**
   * 创建仅含 control 面的 Runtime，不承载本地 Model/Tool execution。
   *
   * <p>Stop 通过 durable terminal state 与 Work fencing 仍然保持正确；缺失的仅是可选的同进程 best-effort 取消。
   */
  public HarnessRuntime(HarnessStore store, Clock clock) {
    this(store, clock, null, NO_OP_CANCELLER, NO_OP_CANCELLER);
  }

  /**
   * 创建完整 Runtime facade，并注入 Tool outcome 的 durable history 物化端口（可为 null：ToolResult 含 Resource 引用时
   * fail-closed）。
   */
  public HarnessRuntime(
      HarnessStore store,
      Clock clock,
      ToolResultHistoryMaterializer toolResultHistoryMaterializer,
      ModelProcessor modelProcessor,
      ToolProcessor toolProcessor) {
    this(
        store,
        clock,
        toolResultHistoryMaterializer,
        modelExecutionCanceller(modelProcessor),
        toolExecutionCanceller(toolProcessor));
  }

  /**
   * 创建完整 Runtime facade，并注入 process-local 的 Model / Tool execution 取消器（无物化端口，Resource 引用
   * fail-closed）。
   */
  public HarnessRuntime(
      HarnessStore store,
      Clock clock,
      Consumer<UUID> modelExecutionCanceller,
      Consumer<UUID> toolExecutionCanceller) {
    this(store, clock, null, modelExecutionCanceller, toolExecutionCanceller);
  }

  /** 由具体 Processor wiring 与 local execution adapter 共用的内部构造方法。 */
  HarnessRuntime(
      HarnessStore store,
      Clock clock,
      ToolResultHistoryMaterializer toolResultHistoryMaterializer,
      Consumer<UUID> modelExecutionCanceller,
      Consumer<UUID> toolExecutionCanceller) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = HarnessStoreTime.millisecondClock(clock);
    this.stopControl = new StopControl(store, this.clock, toolResultHistoryMaterializer);
    this.modelExecutionCanceller =
        Objects.requireNonNull(modelExecutionCanceller, "modelExecutionCanceller");
    this.toolExecutionCanceller =
        Objects.requireNonNull(toolExecutionCanceller, "toolExecutionCanceller");
  }

  /**
   * 在单个 transaction 中原子创建 Session + ROOT（初始完整 {@code BranchSettings}）+ Thread（head 为 ROOT、传入的 YOLO
   * policy、{@code nextCommandSequence=1}、{@code revision=0}、一个共享时间戳）， 通过 {@code nextId()} 分配全局唯一
   * id。
   *
   * <p>不创建 Work 行。当前的 7 表模型没有 {@code createRequestId}，因此本 API 被刻意设计为非幂等： 每次调用都会创建一组全新的
   * Session/Thread 对。
   */
  public CreatedThread createThread(CreateThreadCommand command) {
    Objects.requireNonNull(command, "command");
    return store.transaction(
        tx -> {
          Instant now = clock.instant();
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          Session session = new Session(sessionId, now);
          Entry rootEntry =
              new Entry(
                  rootEntryId,
                  sessionId,
                  null,
                  new RootPayload(command.branchSettings(), command.subagentContext()),
                  now);
          tx.insertSession(session);
          tx.insertEntry(rootEntry);
          ThreadState thread =
              new ThreadState(threadId, rootEntryId, command.yoloEnabled(), 1, 0, now, now);
          tx.insertThread(thread);
          return new CreatedThread(session, rootEntry, thread);
        });
  }

  /** 原子入队一组有序 command。 */
  public List<ThreadCommand> enqueueCommands(ThreadCommandBatch batch) {
    return enqueueCommands(batch, NewCommandPreflight.IDENTITY);
  }

  /**
   * 原子入队一组有序 command（应用 use-case 形态）。
   *
   * <p>对每个 {@code clientCommandId} 的幂等性查找发生在任何 head/sequence/live 检查与 upload 消费之前。当每个 id 都已存在 时，属于
   * <em>ordered command-set replay</em>（并非精确的 HTTP batch replay——刻意没有 batch identity）：当且仅当每个已存储
   * {@code requestHash} 与请求 hash 相等、且已存储 sequence 在请求顺序中连续时，batch 才会被接受；忽略期望的 head/next sequence 与
   * QUEUED/APPLIED/CANCELLED 生命周期，原样返回已存在的行。仅部分 id 存在则冲突为 PARTIAL_COMMAND_REPLAY，复用 id 但 hash
   * 不同则冲突为 COMMAND_ID_REUSED，id 完全匹配但顺序不连续则冲突为 COMMAND_REPLAY_ORDER_MISMATCH；缺失的 command
   * 永远不会被补齐。hash 重放独立于 durable payload 形态，因此 replay 在一次性 upload 行被删除后仍然成立——preflight 绝不在重放路径上被调用。
   *
   * <p>对于全新 batch，要求精确的 expected head + next command sequence 游标（否则 STALE_COMMAND_CURSOR），随后
   * 在同一事务内调用 {@code preflight}（仅新 batch；可把瞬时 ATTACHMENT 内容物化为 durable RESOURCE 并消费 upload / session
   * blob ref，任何失败整体回滚），一次性原子预留 N 个连续 sequence（revision +1），所有 command 插入为 QUEUED，请求 THREAD
   * Work，并原子提交。包含 SET_ENVIRONMENT 的新 batch 还额外要求真正 quiescent 的 pre-state（无已入队 USER/CUSTOM
   * message，共享分类器结果为 IDLE_OR_HISTORICAL，且完全不存在 THREAD Work 行——存在行（无论是否 leased）都会 fence 掉
   * speculative Resolver/runnable mailbox）。精确 replay 跳过该 admission 检查。
   */
  public List<ThreadCommand> enqueueCommands(
      ThreadCommandBatch batch, NewCommandPreflight preflight) {
    Objects.requireNonNull(batch, "batch");
    Objects.requireNonNull(preflight, "preflight");
    return store.transaction(
        tx -> {
          ThreadState thread =
              tx.lockThread(batch.threadId())
                  .orElseThrow(
                      () ->
                          new HarnessRuntimeNotFoundException(
                              "thread " + batch.threadId() + " does not exist"));
          List<Optional<ThreadCommand>> existing = new ArrayList<>(batch.commands().size());
          for (NewThreadCommand request : batch.commands()) {
            existing.add(tx.findCommandByClientId(batch.threadId(), request.clientCommandId()));
          }
          long present = existing.stream().filter(Optional::isPresent).count();
          if (present > 0 && present < existing.size()) {
            throw conflict(
                HarnessRuntimeConflictException.Reason.PARTIAL_COMMAND_REPLAY,
                "batch on thread "
                    + thread.id()
                    + " replays only "
                    + present
                    + " of "
                    + existing.size()
                    + " commands");
          }
          if (present == existing.size()) {
            return replayExistingBatch(batch, existing);
          }
          // 全新 batch 的 admission 在 preflight（upload 消费）之前执行：stale cursor / 非静止
          // 请求确定性拒绝，绝不触碰存储；幂等重放语义不受影响（replay 在上述分支已返回）。
          validateNewBatchAdmission(batch, tx, thread);
          List<NewThreadCommand> prepared =
              preflight.prepare(
                  tx, tx.loadEntryPath(thread.headEntryId()).root().sessionId(), batch.commands());
          if (prepared == null) {
            throw new IllegalStateException("command preflight returned null");
          }
          if (prepared.size() != batch.commands().size()) {
            throw new IllegalStateException(
                "command preflight must return exactly "
                    + batch.commands().size()
                    + " commands, got "
                    + prepared.size());
          }
          for (int i = 0; i < prepared.size(); i++) {
            NewThreadCommand request = batch.commands().get(i);
            NewThreadCommand result = prepared.get(i);
            if (result == null
                || !result.clientCommandId().equals(request.clientCommandId())
                || !result.requestHash().equals(request.requestHash())) {
              throw new IllegalStateException(
                  "command preflight must preserve clientCommandId and requestHash at index " + i);
            }
          }
          return enqueueNewBatch(tx, batch, prepared, thread, clock.instant());
        });
  }

  /** 按 Thread + clientCommandId 读取 durable command，用于应用层精确重放校验。 */
  public Optional<ThreadCommand> findThreadCommand(UUID threadId, UUID clientCommandId) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(clientCommandId, "clientCommandId");
    return store.transaction(
        tx -> {
          if (tx.findThread(threadId).isEmpty()) {
            throw new HarnessRuntimeNotFoundException("thread " + threadId + " does not exist");
          }
          return tx.findCommandByClientId(threadId, clientCommandId);
        });
  }

  /**
   * 同步重定位 Thread head cursor。
   *
   * <p>当当前 head 已等于 target 时，在任何 revision/target 校验之前按原样返回当前 Thread（PUT 风格 no-op replay），且不会递增
   * revision。否则必须匹配 {@code expectedRevision}（否则 STALE_REVISION）， target 必须存在并位于当前 head session（否则
   * MOVE_TARGET_CROSS_SESSION），且不能存在已入队 command 与 live/pending Model/Tool context（否则
   * THREAD_NOT_QUIESCENT / TERMINAL_APPLY_PENDING； 允许移离当前的 CONTINUATION_DUE obligation），同时 target
   * 本身不能是 {@code continueModel=true} 的 TURN_END（否则 MOVE_TARGET_HAS_CONTINUATION_OBLIGATION）。head
   * 精确推进一次，保留 YOLO 与最新 next sequence（revision +1），最后强制删除 THREAD Work 行以 fence 掉 speculative
   * Resolver； 不请求 Work。生效 Environment 由 target branch 派生，绝不复制。
   */
  public ThreadState moveHead(MoveHeadCommand command) {
    Objects.requireNonNull(command, "command");
    return store.transaction(
        tx -> {
          ThreadState thread =
              tx.lockThread(command.threadId())
                  .orElseThrow(
                      () ->
                          new HarnessRuntimeNotFoundException(
                              "thread " + command.threadId() + " does not exist"));
          if (thread.headEntryId().equals(command.targetEntryId())) {
            return thread;
          }
          if (thread.revision() != command.expectedRevision()) {
            throw conflict(
                HarnessRuntimeConflictException.Reason.STALE_REVISION,
                "thread "
                    + thread.id()
                    + " revision "
                    + thread.revision()
                    + " does not match expected "
                    + command.expectedRevision());
          }
          EntryPath headPath = tx.loadEntryPath(thread.headEntryId());
          Entry target =
              tx.findEntry(command.targetEntryId())
                  .orElseThrow(
                      () ->
                          new HarnessRuntimeNotFoundException(
                              "entry " + command.targetEntryId() + " does not exist"));
          EntryPath targetPath = tx.loadEntryPath(command.targetEntryId());
          if (!targetPath.root().sessionId().equals(headPath.root().sessionId())) {
            throw conflict(
                HarnessRuntimeConflictException.Reason.MOVE_TARGET_CROSS_SESSION,
                "target entry "
                    + command.targetEntryId()
                    + " is in session "
                    + targetPath.root().sessionId()
                    + " while thread "
                    + thread.id()
                    + " is in session "
                    + headPath.root().sessionId());
          }
          if (!tx.loadQueuedCommands(thread.id()).isEmpty()) {
            throw conflict(
                HarnessRuntimeConflictException.Reason.THREAD_NOT_QUIESCENT,
                "thread " + thread.id() + " still has queued commands");
          }
          LockedThreadContext locked = ThreadContextLock.load(tx, thread, headPath);
          if (locked.context() instanceof ThreadContext.ModelActive
              || locked.context() instanceof ThreadContext.ToolActive) {
            throw conflict(
                HarnessRuntimeConflictException.Reason.THREAD_NOT_QUIESCENT,
                "thread "
                    + thread.id()
                    + " has a live model/tool context: "
                    + contextName(locked.context()));
          }
          if (locked.context() instanceof ThreadContext.ModelTerminalPending
              || locked.context() instanceof ThreadContext.ToolTerminalPending) {
            throw conflict(
                HarnessRuntimeConflictException.Reason.TERMINAL_APPLY_PENDING,
                "thread "
                    + thread.id()
                    + " has an unattached terminal result: "
                    + contextName(locked.context()));
          }
          if (target.payload() instanceof TurnEndPayload end && end.continueModel()) {
            throw conflict(
                HarnessRuntimeConflictException.Reason.MOVE_TARGET_HAS_CONTINUATION_OBLIGATION,
                "target entry " + target.id() + " is a continueModel=true TURN_END");
          }
          ThreadState moved =
              thread.advanceHead(command.targetEntryId(), thread.yoloEnabled(), clock.instant());
          tx.updateThread(moved);
          tx.deleteWork(new WorkTarget(WorkTargetType.THREAD, thread.id()));
          return moved;
        });
  }

  /**
   * 在一个短 transaction 内决策一次必需的 Tool approval。
   *
   * <p>锁序：Thread -&gt; Model -&gt; Tool siblings -&gt; Work。未加锁的读仅用于发现不可变的 id/ownership
   * 并选择稳定分支——approval 决策由 Thread 锁串行化，决策后即不可变。已决策 approval 在 按规范顺序锁定 owning Model 与 target Tool
   * 之后，通过 {@link ToolApproval#decide} 以新的 {@code now} 验证其作为精确 replay，并返回已锁定的当前 ToolInvocation（保留原始
   * {@code decidedAt}， 不要求当前 branch/status，不递增 revision，不请求 Work）；任何不匹配均为
   * APPROVAL_DECISION_MISMATCH，实体消失或身份变更视为不变量 ISE，已锁定但属于其他 Thread 的行仍为 APPROVAL_NOT_APPLICABLE。未决策
   * approval 必须是已锁定当前 TOOL_ACTIVE context 内、状态为 WAITING_APPROVAL 的 invocation；transition basis
   * 为已锁定的 sibling，而非 pre-lock snapshot，且 target 不会在 siblings 之前单独锁定。ALLOWED 将其恢复为 READY 并请求 TOOL
   * Work，DENIED 将其终结为 FAILED 并请求 THREAD Work，无论哪种情况 Thread revision 都恰好被触碰一次。
   */
  public ToolInvocation decideToolApproval(ToolApprovalCommand command) {
    Objects.requireNonNull(command, "command");
    return store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(command.threadId()).orElse(null);
          if (thread == null) {
            throw approvalNotApplicable("thread " + command.threadId() + " does not exist");
          }
          ToolInvocation probe = tx.findToolInvocation(command.toolInvocationId()).orElse(null);
          if (probe == null) {
            throw approvalNotApplicable(
                "tool invocation " + command.toolInvocationId() + " does not exist");
          }
          ModelInvocation probeModel =
              tx.findModelInvocation(probe.modelInvocationId()).orElse(null);
          if (probeModel == null || !probeModel.threadId().equals(command.threadId())) {
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
   * 原子停止当前 live Turn、取消已入队 Commands，或重放一次先前 thread-owned Stop。
   *
   * <p>durable transaction 由 {@link StopControl} 持有。只有在其 commit 之后，本方法才会 best-effort 取消匹配的
   * process-local Model/Tool execution；本地取消失败仅记录日志，无法改变已 commit 的结果。
   */
  public StopResult stop(StopCommand command) {
    StopControl.Commit commit = stopControl.stop(command);
    if (commit.modelExecutionId() != null) {
      cancelLocalExecution(modelExecutionCanceller, "Model", commit.modelExecutionId());
    }
    for (UUID toolExecutionId : commit.toolExecutionIds()) {
      cancelLocalExecution(toolExecutionCanceller, "Tool", toolExecutionId);
    }
    return commit.result();
  }

  /**
   * 在单个短 transaction 内读取一次一致的 Thread snapshot（Thread -&gt; queued Commands -&gt; applicable Model
   * -&gt; Tool siblings），跨 transaction 永不混合状态。
   *
   * <p>返回 ThreadState、当前 root-to-head {@link EntryPath}、不可变的已入队 Commands，以及仅与分类器 匹配的
   * ModelInvocation / Tool siblings：IDLE_OR_HISTORICAL 与 CONTINUATION_DUE 不暴露任何东西， Model context
   * 仅暴露 Model，Tool context 暴露 Model 与全部 siblings。不持久化也不返回任何派生 状态。
   */
  public ThreadSnapshot getThreadSnapshot(UUID threadId) {
    Objects.requireNonNull(threadId, "threadId");
    return store.transaction(
        tx -> {
          ThreadState thread =
              tx.lockThread(threadId)
                  .orElseThrow(
                      () ->
                          new HarnessRuntimeNotFoundException(
                              "thread " + threadId + " does not exist"));
          List<ThreadCommand> queued = tx.loadQueuedCommands(threadId);
          LockedThreadContext locked = ThreadContextLock.load(tx, thread);
          return switch (locked.context()) {
            case ThreadContext.IdleOrHistorical ignored -> new ThreadSnapshot(
                thread, locked.path(), queued, null, List.of());
            case ThreadContext.ContinuationDue ignored -> new ThreadSnapshot(
                thread, locked.path(), queued, null, List.of());
            case ThreadContext.ModelActive active -> new ThreadSnapshot(
                thread, locked.path(), queued, active.model(), List.of());
            case ThreadContext.ModelTerminalPending pending -> new ThreadSnapshot(
                thread, locked.path(), queued, pending.model(), List.of());
            case ThreadContext.ToolActive active -> new ThreadSnapshot(
                thread, locked.path(), queued, active.model(), active.siblings());
            case ThreadContext.ToolTerminalPending pending -> new ThreadSnapshot(
                thread, locked.path(), queued, pending.model(), pending.siblings());
          };
        });
  }

  /** Ordered command-set replay：先比较 requestHash（独立于 durable payload 形态），再按请求顺序校验 sequence 连续性。 */
  private static List<ThreadCommand> replayExistingBatch(
      ThreadCommandBatch batch, List<Optional<ThreadCommand>> found) {
    List<ThreadCommand> ordered = new ArrayList<>(found.size());
    for (int i = 0; i < found.size(); i++) {
      ThreadCommand existing = found.get(i).orElseThrow();
      NewThreadCommand request = batch.commands().get(i);
      if (!existing.requestHash().equals(request.requestHash())) {
        throw conflict(
            HarnessRuntimeConflictException.Reason.COMMAND_ID_REUSED,
            "clientCommandId "
                + request.clientCommandId()
                + " is reused with a different request hash on thread "
                + batch.threadId());
      }
      ordered.add(existing);
    }
    long firstSequence = ordered.get(0).sequence();
    for (int i = 0; i < ordered.size(); i++) {
      if (ordered.get(i).sequence() != Math.addExact(firstSequence, (long) i)) {
        throw conflict(
            HarnessRuntimeConflictException.Reason.COMMAND_REPLAY_ORDER_MISMATCH,
            "existing commands on thread "
                + batch.threadId()
                + " are not contiguous in the request order");
      }
    }
    return List.copyOf(ordered);
  }

  /** 新 batch 入队：CAS 游标、可选 SET_ENVIRONMENT admission、insert + reserve + Work。 */
  private static List<ThreadCommand> enqueueNewBatch(
      HarnessStore.Transaction tx,
      ThreadCommandBatch batch,
      List<NewThreadCommand> commands,
      ThreadState thread,
      Instant now) {
    validateNewBatchAdmission(batch, tx, thread);
    List<ThreadCommand> inserted = new ArrayList<>(commands.size());
    long nextSequence = thread.nextCommandSequence();
    for (int i = 0; i < commands.size(); i++) {
      NewThreadCommand request = commands.get(i);
      inserted.add(
          new ThreadCommand(
              thread.id(),
              nextSequence + i,
              request.payload(),
              request.clientCommandId(),
              request.requestHash(),
              null,
              null,
              now));
    }
    tx.insertCommands(inserted);
    tx.updateThread(thread.reserveCommandSequences(inserted.size(), now));
    tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()), now);
    return List.copyOf(inserted);
  }

  private static boolean containsSetEnvironment(ThreadCommandBatch batch) {
    for (NewThreadCommand command : batch.commands()) {
      if (command.payload().type() == ThreadCommandType.SET_ENVIRONMENT) {
        return true;
      }
    }
    return false;
  }

  /**
   * 全新 batch 的 admission：stale cursor（head / next sequence 不匹配）与 SET_ENVIRONMENT 非静止 都必须在任何
   * preflight / upload 消费之前确定性拒绝。
   */
  private static void validateNewBatchAdmission(
      ThreadCommandBatch batch, HarnessStore.Transaction tx, ThreadState thread) {
    if (!thread.headEntryId().equals(batch.expectedHeadEntryId())
        || thread.nextCommandSequence() != batch.expectedNextCommandSequence()) {
      throw conflict(
          HarnessRuntimeConflictException.Reason.STALE_COMMAND_CURSOR,
          "thread "
              + thread.id()
              + " head/next command sequence does not match the batch expectation");
    }
    if (containsSetEnvironment(batch)) {
      requireQuiescentForSetEnvironment(tx, thread);
    }
  }

  /**
   * SET_ENVIRONMENT 在 pre-state 上的 admission：无已入队 USER/CUSTOM command，共享的已锁定分类器 结果必须为
   * IDLE_OR_HISTORICAL，且 THREAD Work 行必须完全不存在（不仅仅是 unleased），从而 fence 掉 speculative
   * Resolver/runnable mailbox。锁序：Thread -&gt; existing Commands -&gt; applicable Model -&gt; Tool
   * siblings -&gt; Work。
   */
  private static void requireQuiescentForSetEnvironment(
      HarnessStore.Transaction tx, ThreadState thread) {
    for (ThreadCommand queued : tx.loadQueuedCommands(thread.id())) {
      if (queued.type().isMessage()) {
        throw conflict(
            HarnessRuntimeConflictException.Reason.THREAD_NOT_QUIESCENT,
            "SET_ENVIRONMENT on thread " + thread.id() + " requires no queued USER/CUSTOM message");
      }
    }
    LockedThreadContext locked = ThreadContextLock.load(tx, thread);
    if (!(locked.context() instanceof ThreadContext.IdleOrHistorical)) {
      throw conflict(
          HarnessRuntimeConflictException.Reason.THREAD_NOT_QUIESCENT,
          "SET_ENVIRONMENT on thread "
              + thread.id()
              + " requires an idle thread, got "
              + contextName(locked.context()));
    }
    if (tx.findWork(new WorkTarget(WorkTargetType.THREAD, thread.id())).isPresent()) {
      throw conflict(
          HarnessRuntimeConflictException.Reason.THREAD_NOT_QUIESCENT,
          "SET_ENVIRONMENT on thread " + thread.id() + " requires no THREAD work row");
    }
  }

  /**
   * 已决策 approval：按规范 Thread -&gt; Model -&gt; Tool 顺序锁定 owning Model 与 target Tool， 并对照已锁定的当前
   * approval 验证精确 replay，返回已锁定的当前 ToolInvocation（在 ToolProcessor 状态变化之后 replay
   * 时返回当前行）。原先发现的实体消失或身份变更视为不变量 ISE；已锁定但属于其他 Thread 的行仍为 APPROVAL_NOT_APPLICABLE。
   */
  private ToolInvocation replayDecidedApproval(
      HarnessStore.Transaction tx, ToolInvocation probe, ToolApprovalCommand command) {
    ModelInvocation model =
        tx.lockModelInvocation(probe.modelInvocationId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "model "
                            + probe.modelInvocationId()
                            + " could not be locked for approval replay of tool "
                            + probe.id()));
    if (!model.threadId().equals(command.threadId())) {
      throw approvalNotApplicable(
          "tool invocation " + probe.id() + " does not belong to thread " + command.threadId());
    }
    ToolInvocation tool =
        tx.lockToolInvocation(probe.id())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "tool invocation "
                            + probe.id()
                            + " could not be locked for approval replay"));
    if (!tool.modelInvocationId().equals(probe.modelInvocationId())) {
      throw new IllegalStateException(
          "tool invocation " + tool.id() + " changed its model attachment during approval replay");
    }
    ToolApproval approval = tool.approval();
    if (approval == null || !approval.required() || approval.decision() == null) {
      throw new IllegalStateException(
          "tool invocation " + tool.id() + " lost its required decided approval during replay");
    }
    try {
      Instant now = clock.instant();
      ToolApproval replayed =
          approval.decide(
              command.decision(), command.decisionId(), command.actor(), command.reason(), now);
      if (replayed != approval) {
        throw new IllegalStateException("a decided approval must replay exactly");
      }
    } catch (IllegalArgumentException error) {
      throw conflict(
          HarnessRuntimeConflictException.Reason.APPROVAL_DECISION_MISMATCH,
          "approval of tool invocation " + tool.id() + " does not replay the stored decision");
    }
    return tool;
  }

  /**
   * 未决策 approval：target 必须是已锁定当前 TOOL_ACTIVE context 内、状态为 WAITING_APPROVAL 的 invocation；transition
   * basis 为已锁定 siblings 中的 ToolInvocation，而非 pre-lock snapshot，且 target 永远不会被在 siblings 之前单独锁定（保留
   * ordinal sibling 顺序）。Thread revision 恰好被 触碰一次，并请求匹配的 Work target。
   */
  private ToolInvocation decideUndecidedApproval(
      HarnessStore.Transaction tx,
      ThreadState thread,
      ToolInvocation probe,
      ToolApprovalCommand command) {
    LockedThreadContext locked = ThreadContextLock.load(tx, thread);
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
    if (tool.status() != ToolInvocationStatus.WAITING_APPROVAL || tool.resultEntryId() != null) {
      throw approvalNotApplicable("tool invocation " + tool.id() + " is not waiting for approval");
    }
    ToolApproval approval = tool.approval();
    if (approval == null || !approval.required() || approval.decision() != null) {
      throw new IllegalStateException(
          "tool invocation " + tool.id() + " changed its approval state before being decided");
    }
    Instant workNow = clock.instant();
    Instant mutationNow = max(workNow, thread.updatedAt());
    mutationNow = max(mutationNow, locked.path().head().createdAt());
    mutationNow = max(mutationNow, active.model().updatedAt());
    mutationNow = max(mutationNow, approval.requestedAt());
    for (ToolInvocation sibling : active.siblings()) {
      mutationNow = max(mutationNow, sibling.updatedAt());
    }
    ToolInvocation updated =
        tool.decideApproval(
            command.decision(),
            command.decisionId(),
            command.actor(),
            command.reason(),
            mutationNow,
            mutationNow);
    tx.updateToolInvocations(List.of(updated));
    tx.updateThread(thread.touchRevision(mutationNow));
    WorkTarget wake =
        command.decision() == ToolApprovalDecision.ALLOWED
            ? new WorkTarget(WorkTargetType.TOOL, tool.id())
            : new WorkTarget(WorkTargetType.THREAD, thread.id());
    tx.requestWork(wake, workNow);
    return updated;
  }

  private static Instant max(Instant left, Instant right) {
    return right.isAfter(left) ? right : left;
  }

  private static Consumer<UUID> modelExecutionCanceller(ModelProcessor processor) {
    Objects.requireNonNull(processor, "modelProcessor");
    return processor::cancel;
  }

  private static Consumer<UUID> toolExecutionCanceller(ToolProcessor processor) {
    Objects.requireNonNull(processor, "toolProcessor");
    return processor::cancel;
  }

  private static void cancelLocalExecution(
      Consumer<UUID> canceller, String executionType, UUID invocationId) {
    try {
      canceller.accept(invocationId);
    } catch (RuntimeException error) {
      log.warn(
          "failed to cancel local {} execution {} after durable stop",
          executionType,
          invocationId,
          error);
    }
  }

  private static String contextName(ThreadContext context) {
    return context.getClass().getSimpleName();
  }

  private static HarnessRuntimeConflictException conflict(
      HarnessRuntimeConflictException.Reason reason, String message) {
    return new HarnessRuntimeConflictException(reason, message);
  }

  private static HarnessRuntimeConflictException approvalNotApplicable(String message) {
    return new HarnessRuntimeConflictException(
        HarnessRuntimeConflictException.Reason.APPROVAL_NOT_APPLICABLE, message);
  }

  /**
   * 新 command batch 入队前的窄 preflight 端口：在 store 事务内、幂等重放检查之后、任何 durable command 写入之前被调用 （只对全新 batch
   * 调用），返回与入参一一对应、保持 {@code clientCommandId}/{@code requestHash} 的最终 command 列表。
   *
   * <p>应用 use-case 用它把瞬时 ATTACHMENT 内容物化为 durable RESOURCE（同一外事务内锁定 READY upload、写入 session blob
   * ref、retain blob、删除已消费 upload）；任何失败向上传播使整个入队事务回滚。实现不得自行开启新事务。
   */
  @FunctionalInterface
  public interface NewCommandPreflight {

    /** 恒等 preflight：原样返回入参（纯非附件命令路径）。 */
    NewCommandPreflight IDENTITY = (tx, sessionId, commands) -> commands;

    List<NewThreadCommand> prepare(
        HarnessStore.Transaction tx, UUID sessionId, List<NewThreadCommand> commands);
  }
}
