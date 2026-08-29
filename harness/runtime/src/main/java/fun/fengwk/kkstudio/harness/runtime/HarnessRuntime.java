package fun.fengwk.kkstudio.harness.runtime;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.ModelAttemptMaterialization;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelAttemptFailure;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApprovalDecision;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.runtime.processor.ModelProcessor;
import fun.fengwk.kkstudio.harness.runtime.processor.ToolProcessor;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStoreTime;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetEnvironmentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetModelCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandType;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

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
 * <p>每个方法严格执行一次 {@link HarnessStore} transaction，使用规范锁序 Session -&gt; Thread -&gt; Commands -&gt;
 * ModelInvocation -&gt; ToolInvocation siblings -&gt; Work，从而保证命令接受、Stop 与 snapshot 永不观察到混合的
 * durable 状态。所有业务拒绝均为类型化 {@link HarnessRuntimeConflictException} / {@link
 * HarnessRuntimeNotFoundException}；被破坏 的持久化不变量（所有权错误、sibling 混合挂接、ordinal 不连续）仍为 {@link
 * IllegalStateException}。对已存在 Thread 的 mutation 在相关 durable 锁之后读取时间戳，因此 lock-wait 不会让过期的 pre-lock
 * instant 让 {@code updatedAt} 回退； Stop 与未决 Approval 还会把 mutation 时间钳制到最新的已锁定 durable
 * fact，以容忍本地时钟回滚与跨节点时钟偏差，而 Work request 始终使用未抬升的本地调度时钟。
 *
 * <p>本类实现 {@link #acceptCommands}（NEW_SESSION / ENTRY / THREAD 单原语）、{@link #stop}、{@link
 * #decideToolApproval}、{@link #setThreadYolo}、{@link #getThreadSnapshot}、{@link
 * #listThreadsBySession} 与 {@link #getSessionEntries}。
 */
@Slf4j
public final class HarnessRuntime {

  private static final Consumer<UUID> NO_OP_CANCELLER = ignored -> {};

  /** SET_* prefix 的固定顺序：SET_ENVIRONMENT -&gt; SET_AGENT -&gt; SET_MODEL，每类至多一次、全部在消息之前。 */
  private static final List<ThreadCommandType> SET_PREFIX_ORDER =
      List.of(
          ThreadCommandType.SET_ENVIRONMENT,
          ThreadCommandType.SET_AGENT,
          ThreadCommandType.SET_MODEL);

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
   * 单个写原语原子接受一批 Commands 及其 Work：sealed {@link AcceptCommandsTarget} 定位 + ordered Commands。
   *
   * <p><b>NEW_SESSION</b>：调用方预分配 {@code sessionId}/{@code threadId}，在同一事务内插入 Session + ROOT +
   * Thread（version 0 / nextCommandSequence 1）+ preflight + Commands（sequence 从 1 起）+ THREAD Work，最后
   * version/next sequence 原子推进；任一步失败整个回滚。以 client threadId 做 materialization replay：命中现有 Thread 后先按
   * immutable sessionId KEY SHARE Session、再锁 Thread 复核（禁止混合 unlocked snapshot）；同 hash + 同 Session
   * 精确重放（返回现有接受事实，不写任何行），不同 hash 冲突为 {@link
   * HarnessRuntimeConflictException.Reason#MATERIALIZATION_ID_REUSED}。
   *
   * <p><b>ENTRY</b>：<b>KEY SHARE</b> 锁既有 Session（不串行化同 Session 的 sibling materialization）、验证 start
   * Entry 属于该 Session、预分配 {@code threadId}，插入 Thread + preflight + Commands + Work；不复制 Entry（新
   * Thread head 直接指向 start Entry）。materialization replay 语义与 NEW_SESSION 相同。
   *
   * <p><b>THREAD</b>：先读 immutable {@code thread.sessionId} 并 KEY SHARE Session，再锁 Thread 复核；exact
   * ordered replay 查找必须先于任何 cursor/preflight admission。全新 batch 要求精确的 expected head + next sequence
   * cursor（否则 STALE_COMMAND_CURSOR），同事务调用 {@code preflight}（仅新 batch），预留连续 sequence 并请求 THREAD
   * Work。
   *
   * <p>初始（NEW_SESSION/ENTRY）batch 必须以恰一条 user-like message 结尾，允许固定顺序 SET_* 前缀，且只有初始 target 可在前缀携带
   * SYSTEM CUSTOM_MESSAGE；THREAD 允许产品用户 batch（禁止 SYSTEM CUSTOM_MESSAGE，<b>恰一条</b>末尾 user-like）或恰一条
   * SYSTEM CUSTOM_MESSAGE steering。非法 batch 是请求校验错误，抛 {@link IllegalArgumentException}。
   */
  public AcceptedCommands acceptCommands(
      AcceptCommandsCommand command, AcceptancePreflight preflight) {
    Objects.requireNonNull(command, "command");
    Objects.requireNonNull(preflight, "preflight");
    List<NewThreadCommand> commands = command.commands();
    return switch (command.target()) {
      case AcceptCommandsTarget.NewSession target -> acceptNewSession(target, commands, preflight);
      case AcceptCommandsTarget.Entry target -> acceptEntry(target, commands, preflight);
      case AcceptCommandsTarget.Thread target -> acceptOnThread(target, commands, preflight);
    };
  }

  private AcceptedCommands acceptNewSession(
      AcceptCommandsTarget.NewSession target,
      List<NewThreadCommand> commands,
      AcceptancePreflight preflight) {
    validateBatchShape(target, commands);
    return store.transaction(
        tx -> {
          String materializationHash =
              MaterializationHash.forNewSession(
                  target.sessionId(),
                  target.threadId(),
                  target.rootSettings(),
                  target.subagentContext(),
                  target.yoloEnabled(),
                  commands);
          ThreadState existing = tx.findThread(target.threadId()).orElse(null);
          if (existing != null) {
            return replayInitial(
                tx, target.sessionId(), target.threadId(), existing, commands, materializationHash);
          }
          Instant now = clock.instant();
          tx.insertSession(new Session(target.sessionId(), now));
          UUID rootEntryId = tx.nextId();
          tx.insertEntry(
              new Entry(
                  rootEntryId,
                  target.sessionId(),
                  null,
                  new RootPayload(target.rootSettings(), target.subagentContext()),
                  now));
          ThreadState thread =
              new ThreadState(
                  target.threadId(),
                  target.sessionId(),
                  rootEntryId,
                  materializationHash,
                  target.yoloEnabled(),
                  1,
                  0,
                  now,
                  now);
          tx.insertThread(thread);
          return acceptNewCommandsOnThread(tx, thread, commands, preflight, now);
        });
  }

  private AcceptedCommands acceptEntry(
      AcceptCommandsTarget.Entry target,
      List<NewThreadCommand> commands,
      AcceptancePreflight preflight) {
    validateBatchShape(target, commands);
    return store.transaction(
        tx -> {
          ThreadState existing = tx.findThread(target.threadId()).orElse(null);
          if (existing != null) {
            String materializationHash =
                MaterializationHash.forEntry(
                    target.sessionId(),
                    target.startEntryId(),
                    target.threadId(),
                    target.yoloEnabled(),
                    commands);
            return replayInitial(
                tx, target.sessionId(), target.threadId(), existing, commands, materializationHash);
          }
          // ENTRY 新建路径用 KEY SHARE：不串行化同 Session 的 sibling materialization。
          tx.lockSessionForKeyShare(target.sessionId())
              .orElseThrow(
                  () ->
                      new HarnessRuntimeNotFoundException(
                          "session " + target.sessionId() + " does not exist"));
          Entry startEntry =
              tx.findEntry(target.startEntryId())
                  .orElseThrow(
                      () ->
                          new HarnessRuntimeNotFoundException(
                              "entry " + target.startEntryId() + " does not exist"));
          if (!tx.loadEntryPath(startEntry.id()).root().sessionId().equals(target.sessionId())) {
            throw new IllegalArgumentException(
                "start entry "
                    + target.startEntryId()
                    + " is not in session "
                    + target.sessionId());
          }
          String materializationHash =
              MaterializationHash.forEntry(
                  target.sessionId(),
                  target.startEntryId(),
                  target.threadId(),
                  target.yoloEnabled(),
                  commands);
          Instant now = clock.instant();
          ThreadState thread =
              new ThreadState(
                  target.threadId(),
                  target.sessionId(),
                  target.startEntryId(),
                  materializationHash,
                  target.yoloEnabled(),
                  1,
                  0,
                  now,
                  now);
          tx.insertThread(thread);
          return acceptNewCommandsOnThread(tx, thread, commands, preflight, now);
        });
  }

  private AcceptedCommands acceptOnThread(
      AcceptCommandsTarget.Thread target,
      List<NewThreadCommand> commands,
      AcceptancePreflight preflight) {
    return store.transaction(
        tx -> {
          ThreadState immutable =
              tx.findThread(target.threadId())
                  .orElseThrow(
                      () ->
                          new HarnessRuntimeNotFoundException(
                              "thread " + target.threadId() + " does not exist"));
          UUID sessionId = immutable.sessionId();
          tx.lockSessionForKeyShare(sessionId)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "session " + sessionId + " disappeared while thread existed"));
          ThreadState thread =
              tx.lockThread(target.threadId())
                  .orElseThrow(
                      () ->
                          new HarnessRuntimeNotFoundException(
                              "thread " + target.threadId() + " does not exist"));
          // exact replay 查找必须先于 cursor/preflight admission。
          List<Optional<ThreadCommand>> existing = new ArrayList<>(commands.size());
          for (NewThreadCommand request : commands) {
            existing.add(tx.findCommandByClientId(target.threadId(), request.clientCommandId()));
          }
          long present = existing.stream().filter(Optional::isPresent).count();
          if (present > 0 && present < existing.size()) {
            throw conflict(
                HarnessRuntimeConflictException.Reason.PARTIAL_COMMAND_REPLAY,
                "batch on thread "
                    + target.threadId()
                    + " replays only "
                    + present
                    + " of "
                    + existing.size()
                    + " commands");
          }
          if (present == existing.size()) {
            return replayThreadBatch(tx, target, commands, thread, existing);
          }
          validateThreadBatchAdmission(target, thread);
          validateBatchShape(target, commands);
          Instant now = clock.instant();
          return acceptNewCommandsOnThread(tx, thread, commands, preflight, now);
        });
  }

  /** 全新 batch 的共性写入：preflight、插入 Commands、推进 version/next sequence、请求 THREAD Work。 */
  private static AcceptedCommands acceptNewCommandsOnThread(
      HarnessStore.Transaction tx,
      ThreadState thread,
      List<NewThreadCommand> requests,
      AcceptancePreflight preflight,
      Instant now) {
    Session session =
        tx.findSession(thread.sessionId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "session " + thread.sessionId() + " disappeared while thread existed"));
    List<NewThreadCommand> prepared = preflight.prepare(tx, session, requests);
    requirePreflightShape(requests, prepared);
    List<ThreadCommand> inserted = new ArrayList<>(prepared.size());
    long nextSequence = thread.nextCommandSequence();
    for (int i = 0; i < prepared.size(); i++) {
      NewThreadCommand request = prepared.get(i);
      inserted.add(
          new ThreadCommand(
              thread.id(),
              nextSequence + i,
              request.payload(),
              request.clientCommandId(),
              request.requestHash(),
              null,
              null,
              null,
              now));
    }
    tx.insertCommands(inserted);
    ThreadState advanced = thread.reserveCommandSequences(prepared.size(), now);
    tx.updateThread(advanced);
    tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()), now);
    return new AcceptedCommands(
        session,
        tx.loadEntryPath(advanced.headEntryId()).root(),
        advanced,
        List.copyOf(inserted),
        false);
  }

  /**
   * NEW_SESSION / ENTRY：同 hash + 同 Session 的 client threadId 精确 replay。
   *
   * <p>{@code immutable} 快照只用于在上锁前定位 Session；随后按规范锁序 KEY SHARE Session -&gt; FOR UPDATE Thread
   * 复核后返回当前 projection（禁止混合 unlocked snapshot）。只按本请求 clientCommandId 顺序重放原始初始命令，验证 requestHash 相等且
   * sequence 从 1 连续，绝不返回该 Thread 后续批次的历史命令。
   */
  private static AcceptedCommands replayInitial(
      HarnessStore.Transaction tx,
      UUID sessionId,
      UUID threadId,
      ThreadState immutable,
      List<NewThreadCommand> requests,
      String materializationHash) {
    if (!immutable.sessionId().equals(sessionId)
        || !immutable.materializationHash().equals(materializationHash)) {
      throw conflict(
          HarnessRuntimeConflictException.Reason.MATERIALIZATION_ID_REUSED,
          "thread "
              + threadId
              + " is already materialized with a different session/hash (session "
              + immutable.sessionId()
              + ")");
    }
    tx.lockSessionForKeyShare(immutable.sessionId())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "session " + immutable.sessionId() + " disappeared while thread existed"));
    ThreadState thread =
        tx.lockThread(threadId)
            .orElseThrow(
                () ->
                    new HarnessRuntimeNotFoundException("thread " + threadId + " does not exist"));
    if (!thread.sessionId().equals(sessionId)
        || !thread.materializationHash().equals(materializationHash)) {
      throw conflict(
          HarnessRuntimeConflictException.Reason.MATERIALIZATION_ID_REUSED,
          "thread "
              + threadId
              + " is already materialized with a different session/hash (session "
              + thread.sessionId()
              + ")");
    }
    List<ThreadCommand> ordered = new ArrayList<>(requests.size());
    for (NewThreadCommand request : requests) {
      ThreadCommand existing =
          tx.findCommandByClientId(threadId, request.clientCommandId())
              .orElseThrow(
                  () ->
                      conflict(
                          HarnessRuntimeConflictException.Reason.PARTIAL_COMMAND_REPLAY,
                          "initial batch on thread "
                              + threadId
                              + " is missing clientCommandId "
                              + request.clientCommandId()));
      if (!existing.requestHash().equals(request.requestHash())) {
        throw conflict(
            HarnessRuntimeConflictException.Reason.COMMAND_ID_REUSED,
            "clientCommandId "
                + request.clientCommandId()
                + " is reused with a different request hash on thread "
                + threadId);
      }
      ordered.add(existing);
    }
    for (int i = 0; i < ordered.size(); i++) {
      if (ordered.get(i).sequence() != i + 1L) {
        throw conflict(
            HarnessRuntimeConflictException.Reason.COMMAND_REPLAY_ORDER_MISMATCH,
            "initial commands on thread "
                + threadId
                + " must start at sequence 1 and be contiguous in the request order");
      }
    }
    Session session =
        tx.findSession(sessionId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "session " + sessionId + " disappeared while thread existed"));
    return new AcceptedCommands(
        session, tx.loadEntryPath(thread.headEntryId()).root(), thread, List.copyOf(ordered), true);
  }

  /**
   * Orderly command-set replay：先比较 requestHash（独立于 durable payload 形态），再按请求顺序校验 sequence 连续且首
   * sequence 等于请求的 expected next sequence；重放返回当前 Thread projection，不写任何行。
   */
  private static AcceptedCommands replayThreadBatch(
      HarnessStore.Transaction tx,
      AcceptCommandsTarget.Thread target,
      List<NewThreadCommand> commands,
      ThreadState thread,
      List<Optional<ThreadCommand>> found) {
    List<ThreadCommand> ordered = new ArrayList<>(found.size());
    for (int i = 0; i < found.size(); i++) {
      ThreadCommand existing = found.get(i).orElseThrow();
      NewThreadCommand request = commands.get(i);
      if (!existing.requestHash().equals(request.requestHash())) {
        throw conflict(
            HarnessRuntimeConflictException.Reason.COMMAND_ID_REUSED,
            "clientCommandId "
                + request.clientCommandId()
                + " is reused with a different request hash on thread "
                + target.threadId());
      }
      ordered.add(existing);
    }
    long firstSequence = ordered.get(0).sequence();
    if (firstSequence != target.expectedNextCommandSequence()) {
      throw conflict(
          HarnessRuntimeConflictException.Reason.COMMAND_REPLAY_ORDER_MISMATCH,
          "existing commands on thread "
              + target.threadId()
              + " start at sequence "
              + firstSequence
              + " while the request expected "
              + target.expectedNextCommandSequence());
    }
    for (int i = 0; i < ordered.size(); i++) {
      if (ordered.get(i).sequence() != Math.addExact(firstSequence, (long) i)) {
        throw conflict(
            HarnessRuntimeConflictException.Reason.COMMAND_REPLAY_ORDER_MISMATCH,
            "existing commands on thread "
                + target.threadId()
                + " are not contiguous in the request order");
      }
    }
    Session session =
        tx.findSession(thread.sessionId())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "session " + thread.sessionId() + " disappeared while thread existed"));
    return new AcceptedCommands(
        session, tx.loadEntryPath(thread.headEntryId()).root(), thread, List.copyOf(ordered), true);
  }

  private static void requirePreflightShape(
      List<NewThreadCommand> requests, List<NewThreadCommand> prepared) {
    if (prepared == null) {
      throw new IllegalStateException("command preflight returned null");
    }
    if (prepared.size() != requests.size()) {
      throw new IllegalStateException(
          "command preflight must return exactly "
              + requests.size()
              + " commands, got "
              + prepared.size());
    }
    for (int i = 0; i < prepared.size(); i++) {
      NewThreadCommand request = requests.get(i);
      NewThreadCommand result = prepared.get(i);
      if (result == null
          || !result.clientCommandId().equals(request.clientCommandId())
          || !result.requestHash().equals(request.requestHash())) {
        throw new IllegalStateException(
            "command preflight must preserve clientCommandId and requestHash at index " + i);
      }
    }
  }

  /**
   * THREAD 全新 batch 的 admission：stale cursor（head / next sequence 不匹配）必须确定性拒绝。SET_*（含
   * SET_ENVIRONMENT）只入队、由 Reducer 于下一个 INPUT 边界收割，不参与 admission——即使 Thread 处于 live Model / Tool 或 有
   * queued 消息 / THREAD Work 也照常接受。
   */
  private static void validateThreadBatchAdmission(
      AcceptCommandsTarget.Thread target, ThreadState thread) {
    if (!thread.headEntryId().equals(target.expectedHeadEntryId())
        || thread.nextCommandSequence() != target.expectedNextCommandSequence()) {
      throw conflict(
          HarnessRuntimeConflictException.Reason.STALE_COMMAND_CURSOR,
          "thread "
              + thread.id()
              + " head/next command sequence does not match the batch expectation");
    }
  }

  /**
   * 命令 batch 的 shape admission：SET_* 必须以固定顺序（SET_ENVIRONMENT -&gt; SET_AGENT -&gt;
   * SET_MODEL）、至多一次且全部出现在消息之前；初始 target 必须恰有一条 user-like message 结尾（SYSTEM CUSTOM_MESSAGE
   * 只允许在前缀）；THREAD 要么是恰一条 SYSTEM CUSTOM_MESSAGE steering，要么是不含 SYSTEM CUSTOM_MESSAGE、<b>恰有一条</b>末尾
   * user-like 的用户 batch。非法 batch 是请求校验错误，抛 {@link IllegalArgumentException} 而非业务冲突。
   */
  private static void validateBatchShape(
      AcceptCommandsTarget target, List<NewThreadCommand> commands) {
    int userLikeCount = 0;
    int systemCount = 0;
    int lastSetOrder = -1;
    boolean sawMessage = false;
    for (NewThreadCommand command : commands) {
      ThreadCommandPayload payload = command.payload();
      if (payload instanceof SetAgentCommandPayload
          || payload instanceof SetModelCommandPayload
          || payload instanceof SetEnvironmentCommandPayload) {
        if (sawMessage) {
          throw invalidBatch(target, "SET_* commands must precede all messages");
        }
        int order = SET_PREFIX_ORDER.indexOf(payload.type());
        if (order <= lastSetOrder) {
          throw invalidBatch(
              target, "SET_* prefix must use the fixed order and each type at most once");
        }
        lastSetOrder = order;
        continue;
      }
      sawMessage = true;
      if (isUserLike(command)) {
        userLikeCount++;
      } else {
        systemCount++;
      }
    }
    if (target instanceof AcceptCommandsTarget.NewSession
        || target instanceof AcceptCommandsTarget.Entry) {
      if (userLikeCount != 1 || !isUserLike(commands.get(commands.size() - 1))) {
        throw invalidBatch(target, "initial batches must end with exactly one user-like message");
      }
      return;
    }
    // THREAD
    if (systemCount > 0) {
      if (commands.size() != 1 || userLikeCount != 0) {
        throw invalidBatch(
            target, "thread steering must be exactly one SYSTEM CUSTOM_MESSAGE and nothing else");
      }
      return;
    }
    // THREAD user batch：恰一条末尾 user-like（不是至少一条）。
    if (userLikeCount != 1 || !isUserLike(commands.get(commands.size() - 1))) {
      throw invalidBatch(
          target, "thread user batches must contain exactly one trailing user-like message");
    }
  }

  private static boolean isUserLike(NewThreadCommand command) {
    if (command.payload() instanceof UserMessageCommandPayload) {
      return true;
    }
    return command.payload() instanceof CustomMessageCommandPayload custom
        && custom.message().role() == AgentMessageRole.USER;
  }

  private static IllegalArgumentException invalidBatch(
      AcceptCommandsTarget target, String message) {
    return new IllegalArgumentException(target.getClass().getSimpleName() + " batch: " + message);
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
   * context 暴露 Model 与失败 attempts，Tool context 暴露 Model 与全部 siblings。不持久化也不返回任何派生 状态。
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
              invocation.basisHeadEntryId(),
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
    tx.updateThread(thread.touchVersion(mutationNow));
    if (command.decision() == ToolApprovalDecision.ALLOWED) {
      EnvironmentName environmentName =
          tool.binding() == null || tool.binding().environment() == null
              ? null
              : tool.binding().environment().environmentName();
      tx.requestWork(new WorkTarget(WorkTargetType.TOOL, tool.id()), workNow, environmentName);
    } else {
      tx.requestWork(new WorkTarget(WorkTargetType.THREAD, thread.id()), workNow);
    }
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

  private static HarnessRuntimeConflictException conflict(
      HarnessRuntimeConflictException.Reason reason, String message) {
    return new HarnessRuntimeConflictException(reason, message);
  }

  private static HarnessRuntimeConflictException approvalNotApplicable(String message) {
    return new HarnessRuntimeConflictException(
        HarnessRuntimeConflictException.Reason.APPROVAL_NOT_APPLICABLE, message);
  }
}
