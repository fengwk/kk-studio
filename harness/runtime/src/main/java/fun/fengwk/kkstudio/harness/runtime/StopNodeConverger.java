package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.api.StoppedThreadReceipt;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryCurrentState;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.MessagePayload;
import fun.fengwk.kkstudio.harness.api.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.driver.DriverRegistry;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContext;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadContextClassifier;
import fun.fengwk.kkstudio.harness.spi.driver.AgentDriver;
import fun.fengwk.kkstudio.harness.spi.driver.DriverApplied;
import fun.fengwk.kkstudio.harness.spi.driver.DriverStopResult;
import fun.fengwk.kkstudio.harness.spi.driver.LockedDriverState;
import fun.fengwk.kkstudio.harness.spi.driver.ToolOutcomeAppender;
import fun.fengwk.kkstudio.harness.spi.store.HarnessStore;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Stop 的单节点收敛：在已取得全部行锁的事务内，把一个待停止节点物化为闭合 Turn 并给出其回执事实。
 *
 * <p>顺序固定为：重新读取 Thread 并分类 → 必要时物化已提交的 terminal generation / Tool batch → 取消非通知 Command → 收敛 Tool
 * 结果 → Driver 写私有边界 → 写 STOPPED 边界与 version → 物化排队通知 → Driver completeStop。此顺序保证 Driver 删除私有 parent
 * 之前 Tool 结果与子行已收敛，且 Thread head 在 Driver 校验时保持原样。
 *
 * <p>本类不新增任何行锁，也不参与锁序与 Join 结算编排：那些由 {@link StopControl} 承担。
 */
final class StopNodeConverger {

  private static final ThreadContextClassifier CLASSIFIER = new ThreadContextClassifier();

  /** 已取得全部行锁与只读事实的单个待停止节点。 */
  record ThreadStopContext(
      ThreadState thread,
      UUID stopRequestId,
      EntryPath path,
      List<ThreadCommand> queued,
      LockedDriverState driverState,
      List<ToolInvocation> toolSiblings,
      ThreadContext context) {}

  /** terminal Driver generation 物化后的事实：推进后的 Thread 与可结算的终止边界。 */
  record Materialized(ThreadState thread, UUID terminalEntryId, UUID finalAnswerEntryId) {}

  /** 收敛起点的事实与本次 Stop 全程沿用的 Driver（物化前按原 path 解析）。 */
  private record MaterializedNode(NodeFacts facts, AgentDriver driver) {}

  /** 单个节点收敛结果：STOPPED Thread、其回执，以及提交后需取消的 process-local execution。 */
  record Converged(
      ThreadState thread,
      StoppedThreadReceipt receipt,
      List<UUID> driverExecutionIds,
      List<UUID> toolExecutionIds) {}

  /** 单节点收敛期间的当前事实：Thread、路径、已锁 Driver 状态与其 sibling，以及据此得出的上下文。 */
  private record NodeFacts(
      ThreadState thread,
      EntryPath path,
      LockedDriverState driverState,
      List<ToolInvocation> siblings,
      ThreadContext context) {}

  private final DriverRegistry driverRegistry;

  StopNodeConverger(DriverRegistry driverRegistry) {
    this.driverRegistry = Objects.requireNonNull(driverRegistry, "driverRegistry");
  }

  Converged converge(
      HarnessStore.Transaction tx,
      ThreadStopContext original,
      List<Materialized> committedTerminals,
      Instant now) {
    MaterializedNode materialized = materializedFacts(tx, original, committedTerminals);
    NodeFacts facts = materialized.facts();
    List<ThreadCommand> cancelledCommands =
        cancelQueuedCommands(tx, original.queued(), original.stopRequestId(), now);
    List<ThreadCommand> queuedNotifications =
        original.queued().stream().filter(command -> command.type().isNotification()).toList();
    List<UUID> toolExecutionIds = nonTerminalSiblingIds(facts.context());

    // Tool 结果与子行必须先收敛，Driver 才能删除其私有 parent；Thread head 仍保持原样供 Driver 校验。
    UUID toolOutcomeHead =
        facts.context() instanceof ThreadContext.ToolActive active
            ? StopTurnWrites.settleTools(tx, facts.path(), active, now)
            : null;
    // 沿用物化前解析出的 Driver，避免同一 Stop 内两次解析出不同实现。
    AgentDriver driver = materialized.driver();
    // generation/Tool 上下文由 Driver 写私有边界，idle/continuation 只收敛私有状态。
    // 返回的 executionIds 在本事务提交后交给组合根提供的 Driver 执行取消函数。
    DriverStopResult driverStop =
        driver.stop(
            tx,
            facts.thread(),
            facts.path(),
            facts.driverState(),
            DriverCommits.of(tx),
            original.stopRequestId());

    StopTurnWrites.NodeStop node =
        writeNodeStop(
            tx,
            facts,
            queuedNotifications,
            driverStop,
            toolOutcomeHead,
            original.stopRequestId(),
            now);
    ThreadState stoppedThread =
        StopTurnWrites.isForeignOpenTurn(facts.path(), facts.thread().id())
            ? node.thread()
            : StopTurnWrites.materializeQueuedNotifications(
                tx, node.thread(), queuedNotifications, now);
    driver.completeStop(tx, stoppedThread, DriverCommits.of(tx));
    return new Converged(
        stoppedThread,
        StopReceipts.nodeReceipt(
            stoppedThread,
            original.stopRequestId(),
            node.stoppedTurnEndEntryId(),
            cancelledCommands),
        driverStop.executionIds(),
        toolExecutionIds);
  }

  /**
   * 重新读取 Thread 并分类；terminal 上下文先由 Driver 的公共物化把已提交的结果写入历史（绝不 409），再按新事实重新分类。
   *
   * <p>不在此阶段新增 Commands/Models/Tools 行锁（Stop 已按规范顺序取得，WORK 之后禁止再取），直接复用已锁定的 model / siblings；Thread
   * 状态重新读取以保证基于 tx 内最新事实 mutation。
   */
  private MaterializedNode materializedFacts(
      HarnessStore.Transaction tx,
      ThreadStopContext original,
      List<Materialized> committedTerminals) {
    NodeFacts facts = currentFacts(tx, original);
    AgentDriver driver = driverRegistry.of(facts.path().baseSettings());
    if (facts.context() instanceof ThreadContext.GenerationTerminalPending pending) {
      // 提交先于 Stop：用 Driver 的公共物化把已提交的 terminal 结果写入历史（绝不 409），再正常 Stop。
      Materialized materialized =
          materializeTerminalGeneration(tx, facts, pending.driverState(), driver);
      if (materialized.terminalEntryId() != null) {
        committedTerminals.add(materialized);
      }
      facts = relockedFacts(tx, materialized.thread(), true, driver);
    } else if (facts.context() instanceof ThreadContext.ToolTerminalPending pending) {
      ThreadState materialized =
          materializeTerminalToolBatch(tx, facts, pending.siblings(), driver);
      facts = relockedFacts(tx, materialized, false, driver);
    }
    if (facts.context() instanceof ThreadContext.GenerationTerminalPending
        || facts.context() instanceof ThreadContext.ToolTerminalPending) {
      throw new IllegalStateException(
          "terminal invocation still pending after materialization for thread "
              + facts.thread().id());
    }
    return new MaterializedNode(facts, driver);
  }

  /** 收敛起点的分类：复用加锁阶段取得的 Driver 状态与 siblings，此时它们之间没有写入。 */
  private static NodeFacts currentFacts(HarnessStore.Transaction tx, ThreadStopContext original) {
    ThreadState thread = tx.threads().find(original.thread().id()).orElseThrow();
    EntryPath path = tx.entries().loadPath(thread.headEntryId());
    EntryCurrentState state = EntryCurrentState.from(path);
    return new NodeFacts(
        thread,
        path,
        original.driverState(),
        original.toolSiblings(),
        CLASSIFIER.classify(thread, state, original.driverState(), original.toolSiblings()));
  }

  /**
   * 物化写入后按新 head 重新加锁 Driver 状态并重读 sibling，再分类。
   *
   * <p>generation 物化后仍需锁定新 head 上的 assistant sibling；Tool batch 物化已删除全部 sibling，直接以空集合分类。
   */
  private NodeFacts relockedFacts(
      HarnessStore.Transaction tx, ThreadState thread, boolean loadSiblings, AgentDriver driver) {
    EntryPath path = tx.entries().loadPath(thread.headEntryId());
    EntryCurrentState state = EntryCurrentState.from(path);
    LockedDriverState driverState = driver.lockState(tx, thread, state);
    List<ToolInvocation> siblings =
        loadSiblings ? loadAssistantSiblings(tx, thread, path, driverState) : List.of();
    return new NodeFacts(
        thread,
        path,
        driverState,
        siblings,
        CLASSIFIER.classify(thread, state, driverState, siblings));
  }

  /** 只取消人类/可信任务输入与设置；系统 NOTIFICATION 不取消，稍后物化进历史。 */
  private static List<ThreadCommand> cancelQueuedCommands(
      HarnessStore.Transaction tx, List<ThreadCommand> queued, UUID stopRequestId, Instant now) {
    List<ThreadCommand> cancelledCommands =
        queued.stream()
            .filter(commandToCancel -> !commandToCancel.type().isNotification())
            .map(commandToCancel -> commandToCancel.cancel(stopRequestId, now))
            .toList();
    if (!cancelledCommands.isEmpty()) {
      tx.commands().update(cancelledCommands);
    }
    return cancelledCommands;
  }

  /** 仍需在事务提交后取消的 process-local Tool execution；terminal sibling 已无执行。 */
  private static List<UUID> nonTerminalSiblingIds(ThreadContext context) {
    if (!(context instanceof ThreadContext.ToolActive active)) {
      return List.of();
    }
    return active.siblings().stream()
        .filter(sibling -> !sibling.status().isTerminal())
        .map(ToolInvocation::id)
        .toList();
  }

  private static StopTurnWrites.NodeStop writeNodeStop(
      HarnessStore.Transaction tx,
      NodeFacts facts,
      List<ThreadCommand> queuedNotifications,
      DriverStopResult driverStop,
      UUID toolOutcomeHead,
      UUID stopRequestId,
      Instant now) {
    ThreadState thread = facts.thread();
    EntryPath path = facts.path();
    return switch (facts.context()) {
      case ThreadContext.IdleOrHistorical ignored -> StopTurnWrites.stopIdle(
          tx, thread, path, queuedNotifications, stopRequestId, now);
      case ThreadContext.ContinuationDue ignored -> StopTurnWrites.stopContinuation(
          tx, thread, path, stopRequestId, now);
      case ThreadContext.GenerationActive ignored -> StopTurnWrites.stopGeneration(
          tx, thread, path, driverStop, stopRequestId, now);
      case ThreadContext.ToolActive ignored -> StopTurnWrites.stopTools(
          tx, thread, path, toolOutcomeHead, stopRequestId, now);
      case ThreadContext.GenerationTerminalPending ignored -> throw new IllegalStateException(
          "terminal generation context escaped the Stop guard");
      case ThreadContext.ToolTerminalPending ignored -> throw new IllegalStateException(
          "terminal Tool context escaped the Stop guard");
    };
  }

  /**
   * 提交先于 Stop：让 Driver 把已提交的 terminal generation 物化进历史（ASSISTANT 结果 + TURN_END，或 tool batch 的
   * siblings），关闭 turn 时由 Driver 删除私有状态；Stop 随后自行收敛该节点。
   */
  private Materialized materializeTerminalGeneration(
      HarnessStore.Transaction tx,
      NodeFacts facts,
      LockedDriverState generationState,
      AgentDriver driver) {
    Instant mutationNow = tx.now();
    DriverApplied applied =
        driver.applyResponse(
            tx, facts.thread(), facts.path(), generationState, DriverCommits.of(tx));
    ThreadState current = tx.threads().find(facts.thread().id()).orElseThrow();
    ThreadState advanced;
    if (current.headEntryId().equals(applied.headEntryId())) {
      advanced = current;
    } else {
      if (!current.equals(facts.thread())) {
        throw new IllegalStateException(
            "driver changed the thread without applying its declared head");
      }
      advanced = facts.thread().advanceHead(applied.headEntryId(), mutationNow);
      tx.threads().update(advanced);
    }
    return new Materialized(
        advanced, applied.terminal() ? applied.headEntryId() : null, applied.finalAnswerEntryId());
  }

  /** 全部 terminal 的 Tool sibling batch 物化：公共结果追加后由 Driver 完成附加校验与私有状态清理。 */
  private ThreadState materializeTerminalToolBatch(
      HarnessStore.Transaction tx,
      NodeFacts facts,
      List<ToolInvocation> siblings,
      AgentDriver driver) {
    Instant mutationNow = tx.now();
    UUID turnStartEntryId =
        facts
            .path()
            .openTurnStart()
            .orElseThrow(() -> new IllegalStateException("tool batch requires an open TURN_START"))
            .id();
    UUID parentId = facts.path().head().id();
    for (ToolInvocation sibling : siblings) {
      parentId =
          ToolOutcomeAppender.append(
                  tx, facts.path().root().sessionId(), parentId, sibling, mutationNow)
              .headEntryId();
    }
    UUID turnEndId = tx.nextId();
    tx.entries()
        .insert(
            new Entry(
                turnEndId,
                facts.path().root().sessionId(),
                parentId,
                new TurnEndPayload(turnStartEntryId, TurnEndOutcome.COMPLETED, true, null, null),
                mutationNow));
    tx.tools().deleteByIds(siblings.stream().map(ToolInvocation::id).toList());
    ThreadState advanced = facts.thread().advanceHead(turnEndId, mutationNow);
    tx.threads().update(advanced);
    driver.completeToolBatch(
        tx, facts.thread(), facts.path(), facts.driverState(), siblings, DriverCommits.of(tx));
    return advanced;
  }

  /** Assistant head 上属于本 Thread 的 Tool siblings；共享的 foreign turn 不得锁定原 owner 的工具。 */
  static List<ToolInvocation> loadAssistantSiblings(
      HarnessStore.Transaction tx,
      ThreadState thread,
      EntryPath path,
      LockedDriverState driverState) {
    Entry semanticHead = path.headIgnoringTrailingControlEntries();
    if (StopTurnWrites.isForeignOpenTurn(path, thread.id())
        || (driverState.requestHeadEntryId() != null
            && !driverState.resultAt(thread.headEntryId())
            && !driverState.cancelsToolBatch())
        || !(semanticHead.payload() instanceof MessagePayload message)
        || message.message().role() != AgentMessageRole.ASSISTANT) {
      return List.of();
    }
    return tx.tools().lockByAssistantEntry(semanticHead.id());
  }
}
