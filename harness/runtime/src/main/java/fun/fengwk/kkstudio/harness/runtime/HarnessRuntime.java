package fun.fengwk.kkstudio.harness.runtime;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.ModelAttemptMaterialization;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
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
 * <p>同步控制操作通过短事务与规范锁序 Session -&gt; Thread -&gt; Commands -&gt; ModelInvocation -&gt;
 * ToolInvocation siblings -&gt; Work 保证 durable 状态一致；需要外部解析的手动压缩采用短事务 plan、事务外 resolve、第二短事务 CAS
 * commit。所有业务拒绝均为类型化 {@link HarnessRuntimeConflictException} / {@link
 * HarnessRuntimeNotFoundException}；被破坏 的持久化不变量（所有权错误、sibling 混合挂接、callIndex 不连续）仍为 {@link
 * IllegalStateException}。对已存在 Thread 的 mutation 在相关 durable 锁之后读取时间戳，因此 lock-wait 不会让过期的 pre-lock
 * instant 让 {@code updatedAt} 回退； Stop 与未决 Approval 还会把 mutation 时间钳制到最新的已锁定 durable
 * fact，以容忍本地时钟回滚与跨节点时钟偏差，而 Work request 始终使用未抬升的本地调度时钟。
 *
 * <p>本类实现 {@link #acceptCommands}（NEW_SESSION / NEW_THREAD / THREAD 单原语）、{@link #stop}、{@link
 * #decideToolApproval}、{@link #setThreadYolo}、{@link #renameThread}、{@link #renameSession}、{@link
 * #getSession}、{@link #manualCompactionAvailability}、{@link #compactThread}、{@link
 * #getThreadSnapshot}、 {@link #listThreadsBySession} 与 {@link #getSessionEntries}。
 */
@Slf4j
public final class HarnessRuntime {

  private static final Consumer<UUID> NO_OP_CANCELLER = ignored -> {};

  private final HarnessStore store;
  private final Clock clock;
  private final AcceptCommandsControl acceptCommandsControl;
  private final StopControl stopControl;
  private final ManualCompactionControl manualCompactionControl;
  private final Consumer<UUID> modelExecutionCanceller;
  private final Consumer<UUID> toolExecutionCanceller;

  /** 创建仅含 control 面的 Runtime，不承载本地 Model/Tool execution。 */
  public HarnessRuntime(
      HarnessStore store,
      Clock clock,
      TurnResolver resolver,
      CompactionConfigProvider compactionConfigProvider) {
    this(store, clock, resolver, compactionConfigProvider, null, NO_OP_CANCELLER, NO_OP_CANCELLER);
  }

  /**
   * 创建完整 Runtime facade，并注入 Tool outcome 的 durable history 物化端口（可为 null：ToolResult 含 Resource 引用时
   * fail-closed）以及非空的本地 Model/Tool processors。
   */
  public HarnessRuntime(
      HarnessStore store,
      Clock clock,
      TurnResolver resolver,
      CompactionConfigProvider compactionConfigProvider,
      ToolResultHistoryMaterializer toolResultHistoryMaterializer,
      ModelProcessor modelProcessor,
      ToolProcessor toolProcessor) {
    this(
        store,
        clock,
        resolver,
        compactionConfigProvider,
        toolResultHistoryMaterializer,
        modelExecutionCanceller(modelProcessor),
        toolExecutionCanceller(toolProcessor));
  }

  /** 由具体 Processor wiring 与 local execution adapter 共用的内部构造方法。 */
  HarnessRuntime(
      HarnessStore store,
      Clock clock,
      TurnResolver resolver,
      CompactionConfigProvider compactionConfigProvider,
      ToolResultHistoryMaterializer toolResultHistoryMaterializer,
      Consumer<UUID> modelExecutionCanceller,
      Consumer<UUID> toolExecutionCanceller) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = HarnessStoreTime.millisecondClock(clock);
    this.acceptCommandsControl = new AcceptCommandsControl(store, this.clock);
    this.stopControl = new StopControl(store, this.clock, toolResultHistoryMaterializer);
    this.manualCompactionControl =
        new ManualCompactionControl(
            this.store,
            this.clock,
            Objects.requireNonNull(resolver, "resolver"),
            Objects.requireNonNull(compactionConfigProvider, "compactionConfigProvider"));
    this.modelExecutionCanceller =
        Objects.requireNonNull(modelExecutionCanceller, "modelExecutionCanceller");
    this.toolExecutionCanceller =
        Objects.requireNonNull(toolExecutionCanceller, "toolExecutionCanceller");
  }

  /**
   * 单个写原语原子接受一批 Commands 及其 Work：sealed {@link AcceptCommandsTarget} 定位 + ordered Commands。
   *
   * <p><b>NEW_SESSION</b>：调用方预分配 {@code sessionId}/{@code threadId}，在同一事务内插入 Session + ROOT +
   * Thread（version 0 / nextCommandSequence 1）+ preflight + Commands（sequence 从 1 起）+ THREAD Work，最后
   * version/next sequence 原子推进；任一步失败整个回滚。以 client threadId 做初始创建 replay：命中现有 Thread 后先按 immutable
   * sessionId KEY SHARE Session、再锁 Thread 复核（禁止混合 unlocked snapshot）；同 creation request hash + 同
   * Session 精确重放（返回现有接受事实，不写任何行），不同 hash 冲突为 {@link
   * HarnessRuntimeConflictException.Reason#THREAD_ID_REUSED}。
   *
   * <p><b>NEW_THREAD</b>：<b>KEY SHARE</b> 锁既有 Session（不串行化同 Session 的 sibling 初始创建）、验证 start Entry
   * 属于该 Session、预分配 {@code threadId}，插入 Thread + preflight + Commands + Work；不复制 Entry（新 Thread
   * head 直接指向 start Entry）。初始创建 replay 语义与 NEW_SESSION 相同。
   *
   * <p>初始创建的 Session / Thread 名称由服务端派生（不来自请求）：Session 名称取初始 user-like 消息的首个非空文本内容（前 40 个 Unicode
   * 码点），无文本时回退为 {@code session-} + Session UUID 前 8 位；ROOT Thread 恒为 {@code main}；从 Entry 分支的新
   * Thread 名称恒为 {@code branch-} + Thread UUID 前 8 位。名称不参与 creation request hash，后续 rename 不改变该
   * hash。
   *
   * <p><b>THREAD</b>：先读 immutable {@code thread.sessionId} 并 KEY SHARE Session，再锁 Thread 复核；exact
   * ordered replay 查找必须先于任何 cursor/preflight admission。全新 batch 要求精确的 expected head + next sequence
   * cursor（否则 STALE_COMMAND_CURSOR），同事务调用 {@code preflight}（仅新 batch），预留连续 sequence 并请求 THREAD
   * Work。
   *
   * <p>初始（NEW_SESSION/NEW_THREAD）batch 必须以恰一条 user-like message 结尾，允许固定顺序 SET_* 前缀，且只有初始 target
   * 可在前缀携带 SYSTEM CUSTOM_MESSAGE；THREAD 允许产品用户 batch（禁止 SYSTEM CUSTOM_MESSAGE，<b>恰一条</b>末尾
   * user-like）或恰一条 SYSTEM CUSTOM_MESSAGE steering。非法 batch 是请求校验错误，抛 {@link
   * IllegalArgumentException}。
   */
  public AcceptedCommands acceptCommands(
      AcceptCommandsCommand command, AcceptancePreflight preflight) {
    return acceptCommandsControl.acceptCommands(command, preflight);
  }

  /**
   * 读取 Session 当前投影；不存在抛 {@link HarnessRuntimeNotFoundException}。
   *
   * <p>只读快照，不产生锁；用于读侧展示（名称 / 归属校验），不与后续写操作跨事务组合。
   */
  public Session getSession(UUID sessionId) {
    Objects.requireNonNull(sessionId, "sessionId");
    return store.transaction(
        tx ->
            tx.findSession(sessionId)
                .orElseThrow(
                    () ->
                        new HarnessRuntimeNotFoundException(
                            "session " + sessionId + " does not exist")));
  }

  /** 按 Thread + idempotencyKey 读取 durable command，用于应用层精确重放校验。 */
  public Optional<ThreadCommand> findThreadCommand(UUID threadId, UUID idempotencyKey) {
    Objects.requireNonNull(threadId, "threadId");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    return store.transaction(
        tx -> {
          if (tx.findThread(threadId).isEmpty()) {
            throw new HarnessRuntimeNotFoundException("thread " + threadId + " does not exist");
          }
          return tx.findCommandByIdempotencyKey(threadId, idempotencyKey);
        });
  }

  /**
   * 在单个短 transaction 内重命名 Session：先 FOR UPDATE 锁 Session 行，name 规范化为非空单行后与当前值比较，同名即按原样返回 （网络重试
   * no-op）；否则仅替换显示名称，id / createdAt 不可变。本操作不触碰任何 Thread / Command / Entry / Work， 并发重命名为
   * last-commit-wins。
   */
  public Session renameSession(RenameSessionCommand command) {
    Objects.requireNonNull(command, "command");
    return store.transaction(
        tx -> {
          Session session =
              tx.lockSessionForUpdate(command.sessionId())
                  .orElseThrow(
                      () ->
                          new HarnessRuntimeNotFoundException(
                              "session " + command.sessionId() + " does not exist"));
          if (session.name().equals(command.name())) {
            return session;
          }
          Session updated = session.rename(command.name());
          tx.updateSession(updated);
          return updated;
        });
  }

  /**
   * 在一个短 transaction 内直接重命名 Thread（isolated metadata mutation）。
   *
   * <p>锁 Thread 后先比较当前 name：与规范化后的请求值相同即按原样返回当前 Thread（no-op，不触碰 version、不创建 Command/Entry/Work、不请求
   * Work、不唤醒 processors）；否则仅替换 name 且 version 精确 +1、updatedAt 推进，复用 {@link
   * HarnessStore.Transaction#updateThread} 的既有行迁移/校验路径。不接收 expectedVersion：并发重命名为 last-commit-wins。
   */
  public ThreadState renameThread(RenameThreadCommand command) {
    Objects.requireNonNull(command, "command");
    return store.transaction(
        tx -> {
          ThreadState thread =
              tx.lockThread(command.threadId())
                  .orElseThrow(
                      () ->
                          new HarnessRuntimeNotFoundException(
                              "thread " + command.threadId() + " does not exist"));
          if (thread.name().equals(command.name())) {
            return thread;
          }
          ThreadState updated = thread.renameThread(command.name(), clock.instant());
          tx.updateThread(updated);
          return updated;
        });
  }

  /**
   * 在一个短 transaction 内直接更新 Thread 的 YOLO runtime policy。
   *
   * <p>锁 Thread 后先比较当前值：与请求值相同即按原样返回当前 Thread（网络重试 no-op，不触碰 version、不创建 Command/Entry/Work、不请求
   * Work），否则必须匹配 {@code expectedVersion}（否则 STALE_VERSION），随后在一个原子步骤中更新 {@code yoloEnabled} 且
   * version 精确 +1。本操作绝不唤醒 processors。
   */
  public ThreadState setThreadYolo(SetThreadYoloCommand command) {
    Objects.requireNonNull(command, "command");
    return store.transaction(
        tx -> {
          ThreadState thread =
              tx.lockThread(command.threadId())
                  .orElseThrow(
                      () ->
                          new HarnessRuntimeNotFoundException(
                              "thread " + command.threadId() + " does not exist"));
          if (thread.yoloEnabled() == command.enabled()) {
            return thread;
          }
          if (thread.version() != command.expectedVersion()) {
            throw conflict(
                HarnessRuntimeConflictException.Reason.STALE_VERSION,
                "thread "
                    + thread.id()
                    + " version "
                    + thread.version()
                    + " does not match expected "
                    + command.expectedVersion());
          }
          ThreadState updated = thread.setYoloEnabled(command.enabled(), clock.instant());
          tx.updateThread(updated);
          return updated;
        });
  }

  /** 返回 Thread 当前的手动压缩可用性；结果是瞬时 projection，提交仍由 expectedVersion 做最终 CAS。 */
  public ManualCompactionAvailability manualCompactionAvailability(UUID threadId) {
    return manualCompactionControl.manualCompactionAvailability(threadId);
  }

  /**
   * 手动压缩控制：短事务 plan，事务外 resolve，再以 expected version/source head/command snapshot 原子提交 COMPACTION
   * Turn 与 MODEL Work。resolve 前崩溃零持久化；提交成功后不依赖进程内 intent。
   */
  public CompactThreadResult compactThread(CompactThreadCommand command) {
    return manualCompactionControl.compactThread(command);
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
   * 在单个短 transaction 内决策一次必需的 Tool approval。
   *
   * <p>锁序：Session -&gt; Thread -&gt; Model -&gt; Tool siblings -&gt; Work。未加锁的读仅用于发现不可变的
   * id/ownership 并选择 稳定分支——approval 决策由 Thread 锁串行化，决策后即不可变。已决策 approval 在按规范顺序锁定 owning Model 与
   * target Tool 之后，通过 {@link ToolApproval#decide} 以新的 {@code now} 验证其作为精确 replay，并返回已锁定的当前
   * ToolInvocation（保留原始 {@code decidedAt}，不要求当前 branch/status，不递增 version，不请求 Work）；任何不匹配均为
   * APPROVAL_DECISION_MISMATCH。
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
   * 在单个短 transaction 内读取一次一致的 Thread snapshot（Thread -&gt; queued Commands -&gt; applicable Model
   * -&gt; Tool siblings），跨 transaction 永不混合状态。
   *
   * <p>返回 ThreadState、当前 root-to-head {@link EntryPath}、不可变的已入队 Commands，以及仅与分类器匹配的 ModelInvocation
   * / Tool siblings / 尚未物化的失败 attempts：IDLE_OR_HISTORICAL 与 CONTINUATION_DUE 不暴露 Invocation，Model
   * context 暴露 Model 与失败 attempts，Tool context 暴露 Model 与全部 siblings。ModelInvocation checkpoint 可能在
   * Thread version 不变时推进；本方法仍返回事务内最新 checkpoint，调用方不得把 version 当作完整快照 ETag。不持久化也不返回任何派生状态。
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
                thread, locked.path(), queued, null, List.of(), List.of());
            case ThreadContext.ContinuationDue ignored -> new ThreadSnapshot(
                thread, locked.path(), queued, null, List.of(), List.of());
            case ThreadContext.ModelActive active -> new ThreadSnapshot(
                thread,
                locked.path(),
                queued,
                active.model(),
                List.of(),
                projectModelAttemptFailures(active.model(), locked.path()));
            case ThreadContext.ModelTerminalPending pending -> new ThreadSnapshot(
                thread,
                locked.path(),
                queued,
                pending.model(),
                List.of(),
                projectModelAttemptFailures(pending.model(), locked.path()));
            case ThreadContext.ToolActive active -> new ThreadSnapshot(
                thread, locked.path(), queued, active.model(), active.siblings(), List.of());
            case ThreadContext.ToolTerminalPending pending -> new ThreadSnapshot(
                thread, locked.path(), queued, pending.model(), pending.siblings(), List.of());
          };
        });
  }

  /** 读取 Session 的全部不可变 Entry（含非当前 head 路径上的历史分支）；Session 不存在抛 NotFound。 */
  public List<Entry> getSessionEntries(UUID sessionId) {
    Objects.requireNonNull(sessionId, "sessionId");
    return store.transaction(
        tx -> {
          if (tx.findSession(sessionId).isEmpty()) {
            throw new HarnessRuntimeNotFoundException("session " + sessionId + " does not exist");
          }
          return tx.loadEntriesBySessionId(sessionId);
        });
  }

  /** 读取 Session 的全部 Thread（当前 projection），按确定性 id 序；Session 不存在抛 NotFound。 */
  public List<ThreadState> listThreadsBySession(UUID sessionId) {
    Objects.requireNonNull(sessionId, "sessionId");
    return store.transaction(
        tx -> {
          if (tx.findSession(sessionId).isEmpty()) {
            throw new HarnessRuntimeNotFoundException("session " + sessionId + " does not exist");
          }
          return tx.listThreadsBySession(sessionId);
        });
  }

  private static List<ModelAttemptFailureProjection> projectModelAttemptFailures(
      ModelInvocation invocation, EntryPath path) {
    if (ModelAttemptMaterialization.isCompactionInvocation(invocation, path)
        || invocation.failedAttempts().isEmpty()) {
      return List.of();
    }
    List<ModelAttemptFailureProjection> projections = new ArrayList<>();
    for (ModelAttemptFailure failure : invocation.failedAttempts()) {
      projections.add(
          new ModelAttemptFailureProjection(
              invocation.id(),
              invocation.turnStartEntryId(),
              invocation.requestHeadEntryId(),
              failure.attempt(),
              failure.sequence(),
              failure.text(),
              failure.thinking(),
              failure.error(),
              failure.failedAt(),
              failure.retryAt()));
    }
    return List.copyOf(projections);
  }

  /**
   * 已决策 approval：按规范 Thread -&gt; Model -&gt; Tool 顺序锁定 owning Model 与 target Tool，并对照已锁定的当前
   * approval 验证精确 replay，返回已锁定的当前 ToolInvocation。
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
   * basis 为已锁定 siblings 中的 ToolInvocation。Thread version 恰好被触碰一次，并请求匹配的 Work target。
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
    if (tool.status() != ToolInvocationStatus.WAITING_APPROVAL) {
      throw approvalNotApplicable("tool invocation " + tool.id() + " is not waiting for approval");
    }
    ToolApproval approval = tool.approval();
    if (approval == null || !approval.required() || approval.decision() != null) {
      throw new IllegalStateException(
          "tool invocation " + tool.id() + " changed its approval state before being decided");
    }
    Instant workNow = clock.instant();
    Instant mutationNow =
        HarnessStoreTime.notBefore(
            workNow,
            thread.updatedAt(),
            locked.path().head().createdAt(),
            active.model().updatedAt(),
            approval.requestedAt());
    for (ToolInvocation sibling : active.siblings()) {
      mutationNow = HarnessStoreTime.notBefore(mutationNow, sibling.updatedAt());
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
    tx.updateThread(thread.touchVersion(mutationNow));
    if (command.decision() == ToolApprovalDecision.ALLOWED) {
      EnvironmentId environmentId =
          tool.binding() == null || tool.binding().environment() == null
              ? null
              : tool.binding().environment().environmentId();
      tx.requestWork(new WorkTarget(WorkTargetType.TOOL, tool.id()), workNow, environmentId);
    } else {
      tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()), workNow);
    }
    return updated;
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

  private static HarnessRuntimeConflictException conflict(
      HarnessRuntimeConflictException.Reason reason, String message) {
    return new HarnessRuntimeConflictException(reason, message);
  }

  private static HarnessRuntimeConflictException approvalNotApplicable(String message) {
    return new HarnessRuntimeConflictException(
        HarnessRuntimeConflictException.Reason.APPROVAL_NOT_APPLICABLE, message);
  }
}
