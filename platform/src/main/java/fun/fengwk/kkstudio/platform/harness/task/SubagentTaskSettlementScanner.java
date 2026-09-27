package fun.fengwk.kkstudio.platform.harness.task;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentConfigProvider;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentPrompts;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskMessages;
import fun.fengwk.kkstudio.harness.builtin.subagent.SubagentTaskMessages.Outcome;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsCommand;
import fun.fengwk.kkstudio.harness.runtime.AcceptCommandsTarget;
import fun.fengwk.kkstudio.harness.runtime.AcceptancePreflight;
import fun.fengwk.kkstudio.harness.runtime.AcceptedCommands;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.SystemReminder;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.platform.harness.configuration.SubagentTaskProperties;
import fun.fengwk.kkstudio.platform.harness.task.SubagentTaskTerminalProjection.Terminal;
import fun.fengwk.kkstudio.platform.harness.task.repo.SubagentTaskRepository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 未交付 task 的后台结算扫描：把已终结的子执行结果作为父 Thread 的一条独立消息交付，并处理停止传播与软预算提醒。
 *
 * <p>执行模型是**周期扫描**（{@link SubagentTaskProperties#getSettlementInterval()} 是交付与停止传播的延迟上界），不是事件驱动的推送；
 * 每轮按稳定顺序轮转扫描，扫描相位排在最后（数据库与 Runtime 就绪后开始）。重启后状态完全来自持久事实（未交付记录 + 子 Thread snapshot），无需内存 registry。
 *
 * <p>设计要点：
 *
 * <ul>
 *   <li>两段状态机：子执行终结时先把终态与报告持久化为 {@code SETTLED}（CAS），再尝试交付；交付成功才把状态推进为 {@code DELIVERED}。因此
 *       「子执行终结」与「父通知完成」是两个清晰的持久事实，停止的父既不占并发额度也不丢结果。
 *   <li>交付同事务：结果消息入队与 {@code DELIVERED} 推进在同一 store 事务内提交（状态在 preflight 中 CAS），并且 preflight 在事务内复核
 *       「父仍未停止」与「交付内容仍是同一份持久结果」；任一不成立即抛错回滚，绝不出现"消息已入队但状态未推进"或反之。
 *   <li>不越过显式停止：父 Thread 处于停止态时结果与提醒都不注入（不唤醒父），停止有界地传播到未结清子执行；父恢复后由下一次扫描交付。
 *   <li>嵌套子执行：子 Thread 自身到达终态但其子树仍有会被交付并唤醒它的委派时，本次执行不算结束，必须等子树结清后的下一个终态，避免把中间态 报告当作最终结果。
 *   <li>公平扫描：按 {@code (created_at, invocation_id)} 稳定顺序 keyset 轮转，无法推进的记录不会永远挡住后面的记录。
 * </ul>
 */
@Slf4j
public final class SubagentTaskSettlementScanner implements SmartLifecycle {

  private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;
  private static final int DELIVERY_ATTEMPTS = 3;
  private static final int STOP_ATTEMPTS = 3;
  private static final int REMINDER_INTERVAL = 5;
  private static final String DELIVERY_KEY_NAMESPACE = "kk-studio/harness/subagent/delivery/";
  private static final String REMINDER_KEY_NAMESPACE = "kk-studio/harness/subagent/reminder/";
  private static final String STOP_KEY_NAMESPACE = "kk-studio/harness/subagent/stop/";
  private static final String CHILD_MISSING_ERROR =
      "subagent thread was deleted before it reported a result";
  private static final String PARENT_MISSING_ERROR =
      "parent thread was deleted before the subagent result could be delivered";
  private static final String ABORTED_BEFORE_START_ERROR =
      "subagent task was cancelled before it started";

  private final Supplier<HarnessRuntime> runtimeProvider;
  private final HarnessStore store;
  private final SubagentTaskRepository repository;
  private final SubagentTaskActivity activity;
  private final SubagentConfigProvider configProvider;
  private final SubagentTaskProperties properties;
  private final Object lifecycleLock = new Object();
  private final AtomicBoolean scanRunning = new AtomicBoolean();

  private ScheduledExecutorService executor;
  private ScheduledFuture<?> scheduled;
  private volatile boolean running;

  /** 公平扫描游标（内存态，仅影响调度顺序，不构成持久事实）。 */
  private volatile Instant cursorCreatedAt;

  private volatile UUID cursorInvocationId;

  public SubagentTaskSettlementScanner(
      Supplier<HarnessRuntime> runtimeProvider,
      HarnessStore store,
      SubagentTaskRepository repository,
      SubagentTaskActivity activity,
      SubagentConfigProvider configProvider,
      SubagentTaskProperties properties) {
    this.runtimeProvider = Objects.requireNonNull(runtimeProvider, "runtimeProvider");
    this.store = Objects.requireNonNull(store, "store");
    this.repository = Objects.requireNonNull(repository, "repository");
    this.activity = Objects.requireNonNull(activity, "activity");
    this.configProvider = Objects.requireNonNull(configProvider, "configProvider");
    this.properties = Objects.requireNonNull(properties, "properties");
  }

  @Override
  public void start() {
    synchronized (lifecycleLock) {
      if (running) {
        return;
      }
      executor =
          Executors.newSingleThreadScheduledExecutor(
              Thread.ofPlatform().daemon().name("subagent-task-settlement").factory());
      running = true;
      scheduled =
          executor.scheduleWithFixedDelay(
              this::scan, 0L, properties.getSettlementInterval().toMillis(), TimeUnit.MILLISECONDS);
    }
  }

  @Override
  public void stop() {
    synchronized (lifecycleLock) {
      if (!running) {
        return;
      }
      running = false;
      if (scheduled != null) {
        scheduled.cancel(false);
        scheduled = null;
      }
      if (executor != null) {
        executor.shutdown();
        try {
          if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            executor.shutdownNow();
          }
        } catch (InterruptedException error) {
          executor.shutdownNow();
          Thread.currentThread().interrupt();
        }
        executor = null;
      }
    }
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  /** 扫描相位排在最后：数据库与 Runtime 全部就绪后才第一次扫描。 */
  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }

  private void scan() {
    if (!scanRunning.compareAndSet(false, true)) {
      return;
    }
    try {
      settleOnce();
    } catch (RuntimeException error) {
      log.warn("Subagent task settlement scan failed", error);
    } finally {
      scanRunning.set(false);
    }
  }

  /**
   * 执行一轮结算，返回本轮推进状态的 task 数（结算或交付）。
   *
   * <p>每轮从上一轮游标之后取一批，取不满一批即回到起点：无法在本轮推进的记录不会永久占据批次前部而让其后的记录饿死。
   */
  public int settleOnce() {
    HarnessRuntime runtime = runtimeProvider.get();
    if (runtime == null) {
      return 0;
    }
    int limit = properties.getSettlementBatchSize();
    List<SubagentTask> page =
        repository.listUndeliveredAfter(cursorCreatedAt, cursorInvocationId, limit);
    boolean lastPage = page.size() < limit;
    int handled = 0;
    for (SubagentTask task : page) {
      advanceCursor(task);
      try {
        if (settle(runtime, task)) {
          handled++;
        }
      } catch (RuntimeException error) {
        log.warn("Subagent task settlement failed: {}", task.invocationId(), error);
      }
    }
    if (lastPage) {
      // 已到队尾：下一轮从头开始，使停止的父/无法推进的记录在父恢复后能被重新评估。
      cursorCreatedAt = null;
      cursorInvocationId = null;
    }
    return handled;
  }

  /** 推进公平扫描游标（仅内存态）。 */
  void advanceCursor(SubagentTask task) {
    cursorCreatedAt = task.createdAt();
    cursorInvocationId = task.invocationId();
  }

  /** 结算单个未交付 task；返回本轮是否推进了它的持久状态。 */
  boolean settle(HarnessRuntime runtime, SubagentTask task) {
    if (task.settled()) {
      return deliver(runtime, task);
    }
    ThreadSnapshot child = SubagentTaskActivity.readThread(runtime, task.childThreadId());
    if (child == null) {
      // 子 Thread 已被删除或不可读：这是明确的异常终态，必须持久化并通知父，绝不静默丢弃委派。
      // 线程行已不存在，没有可锁定的对象，也没有可漂移的执行事实，因此走无锁结算分支。
      return settleAndDeliver(
          runtime, task, new Terminal(Outcome.ERROR, null, null, CHILD_MISSING_ERROR), null);
    }
    Terminal terminal = SubagentTaskTerminalProjection.terminal(child, task.sourceHeadEntryId());
    if (terminal == null
        && SubagentTaskTerminalProjection.abortedBeforeStart(child, task.sourceHeadEntryId())) {
      // 委派在被消费前被取消（或历史被回退）：本次执行不可能再产生终态。必须结清为明确终态，否则记录永久停在
      // OPEN，父 Thread 永久 processing、额度永久占用、父永远收不到通知。
      terminal = new Terminal(Outcome.CANCELLED, null, null, ABORTED_BEFORE_START_ERROR);
    }
    if (terminal != null && !activity.hasPendingDelegatedWork(task.childThreadId())) {
      // 终态与活动判定都在锁外完成；结算前的锁定复核保证"投影所用的子执行事实"到 SETTLED 之间不会再被推进。
      return settleAndDeliver(runtime, task, terminal, child);
    }
    ThreadSnapshot parent = SubagentTaskActivity.readThread(runtime, task.parentThreadId());
    if (parent == null) {
      // 父 Thread 已不存在：记录随父级联删除，此刻没有交付对象；保守结清，避免永久占用额度。
      return settleExecution(
          task, new Terminal(Outcome.ERROR, null, null, PARENT_MISSING_ERROR), null);
    }
    if (SubagentTaskTerminalProjection.stopped(parent)) {
      // 父明确停止：把停止有界传播到未结清子执行；结果仍会持久保留，父恢复后再交付。
      return stopChild(runtime, task);
    }
    if (terminal == null) {
      remindIfDue(runtime, task, child);
    } else if (terminal.outcome() == Outcome.CANCELLED) {
      // 子执行自己是被停止/取消的，但它的子树里还有 OPEN 执行：停止必须先确认传播到后代，绝不能在子树未静止时
      // 把这次执行当成已结束。stopRequestId 由 invocation 稳定派生，重复传播是幂等重放。
      stopChild(runtime, task);
    }
    // 子树仍有会被交付并唤醒子线程的委派：本次执行尚未真正结束，等子树结清后的下一个终态。
    return false;
  }

  /** 持久化执行终态，成功后在同一轮继续尝试交付。 */
  private boolean settleAndDeliver(
      HarnessRuntime runtime, SubagentTask task, Terminal terminal, ThreadSnapshot projectedChild) {
    if (!settleExecution(task, terminal, projectedChild)) {
      // CAS 未命中或子执行已漂移：由后续扫描按最新持久事实收敛。
      return false;
    }
    return deliver(runtime, task.invocationId());
  }

  /**
   * OPEN → SETTLED 的 CAS，返回是否命中。
   *
   * <p>终态是从锁外的子 Thread 快照投影出来的，而快照与写入之间子执行仍可能推进（例如子级委派的结果刚被交付进该子 Thread、命令被消费产生新
   * turn、或历史被回退）。这些推进都会改变 {@code version} / {@code headEntryId} / {@code
   * nextCommandSequence}，此时旧的终态不再是这次执行的真正结局。因此当存在投影快照时先开一个 store 事务：
   *
   * <ol>
   *   <li>按规范锁序锁子 Session（KEY SHARE）再锁子 Thread（FOR UPDATE）；
   *   <li>复核锁定后的事实与投影快照完全一致（有漂移则不结算，等下一轮以最新事实重新投影）；
   *   <li>在同一事务内完成 CAS。把结果交付进该子 Thread 的事务锁的是同一个 Thread 行，因此"投影 → CAS"之间不可能插入 一次子结果交付；一旦 CAS 提交为
   *       SETTLED，本次执行的结局就被固定。
   * </ol>
   *
   * <p>事务内不重入 Runtime 读取：终态与活动判定都在锁外完成，事务里只做锁定复核与 CAS。
   *
   * @param projectedChild 投影终态所用的子 Thread 快照；{@code null} 表示没有可复核的执行（子 Thread 行已不存在）
   */
  private boolean settleExecution(
      SubagentTask task, Terminal terminal, ThreadSnapshot projectedChild) {
    if (projectedChild == null) {
      return repository.settleResult(
          task.invocationId(),
          terminal.outcome(),
          terminal.report(),
          terminal.partialResult(),
          terminal.error());
    }
    return store.transaction(
        tx -> {
          if (tx.lockSessionForKeyShare(task.childSessionId()).isEmpty()) {
            log.debug(
                "Subagent task {} settlement skipped: child session is gone", task.invocationId());
            return false;
          }
          Optional<ThreadState> locked = tx.lockThread(task.childThreadId());
          if (locked.isEmpty()) {
            log.debug(
                "Subagent task {} settlement skipped: child thread is gone", task.invocationId());
            return false;
          }
          if (!sameExecutionFacts(locked.get(), projectedChild.thread())) {
            log.debug(
                "Subagent task {} settlement skipped: child execution advanced after the projection",
                task.invocationId());
            return false;
          }
          return repository.settleResult(
              task.invocationId(),
              terminal.outcome(),
              terminal.report(),
              terminal.partialResult(),
              terminal.error());
        });
  }

  /** 投影快照与锁定后事实是否描述同一次执行：head / 命令序列 / version 任一前进都算漂移。 */
  private static boolean sameExecutionFacts(ThreadState locked, ThreadState projected) {
    return locked.version() == projected.version()
        && locked.headEntryId().equals(projected.headEntryId())
        && locked.nextCommandSequence() == projected.nextCommandSequence();
  }

  /**
   * 把已终结的结果作为父 Thread 的一条 CUSTOM_MESSAGE 入队，并在同一 store 事务内推进为 DELIVERED。
   *
   * <p>交付内容完全来自持久记录（{@code outcome/report/partial_result/error}）：子历史此后被继续或删除都不会改变已终结的结果。幂等键由
   * invocation 与终态稳定派生，重试命中精确重放而不会产生第二条结果消息。
   */
  private boolean deliver(HarnessRuntime runtime, UUID invocationId) {
    SubagentTask task = repository.findByInvocationId(invocationId);
    return task != null && deliver(runtime, task);
  }

  private boolean deliver(HarnessRuntime runtime, SubagentTask task) {
    if (task == null || task.delivered()) {
      return true;
    }
    if (!task.settled()) {
      return false;
    }
    String message =
        SubagentTaskMessages.completion(
            task.childThreadId(),
            task.agent(),
            task.outcome(),
            task.prompt(),
            task.report(),
            task.partialResult(),
            task.error());
    CustomMessageCommandPayload payload =
        new CustomMessageCommandPayload(AgentMessage.user(message));
    NewThreadCommand deliveryCommand =
        new NewThreadCommand(
            payload,
            SubagentTaskRunner.derive(task.invocationId(), DELIVERY_KEY_NAMESPACE),
            ThreadCommandPayloadJsonCodec.requestHash(payload));
    for (int attempt = 0; attempt < DELIVERY_ATTEMPTS; attempt++) {
      ThreadSnapshot parent = SubagentTaskActivity.readThread(runtime, task.parentThreadId());
      if (parent == null) {
        // 父 Thread 已不存在（记录随之级联删除）：本工作项已不存在，无需再交付。
        return true;
      }
      if (SubagentTaskTerminalProjection.stopped(parent)) {
        // 父处于显式停止态：只持久保留，等父恢复后再交付，绝不自动唤醒。
        return false;
      }
      try {
        AcceptedCommands accepted =
            runtime.acceptCommands(
                new AcceptCommandsCommand(
                    new AcceptCommandsTarget.Thread(
                        parent.thread().id(),
                        parent.thread().headEntryId(),
                        parent.thread().nextCommandSequence()),
                    List.of(deliveryCommand)),
                deliveryPreflight(task));
        if (accepted.replayed()) {
          // 命令已在此前事务中提交，状态推进也在同一事务内完成；这里只做幂等确认与修复。
          return confirmDelivered(task.invocationId());
        }
        return true;
      } catch (SubagentTaskSettlementConflictException conflict) {
        // 事务内复核失败：本事务已回滚（结果消息未入队）。只有确认并发方已完成交付才算完成，否则下一轮按最新事实重试。
        return confirmDelivered(task.invocationId());
      } catch (HarnessRuntimeConflictException conflict) {
        // 父 Thread 在读取与接受之间推进：下一轮以最新 cursor 重试（停止门禁仍以最新事实为准）。
      } catch (HarnessRuntimeNotFoundException notFound) {
        return true;
      }
    }
    log.warn("Subagent task result could not be delivered after retries: {}", task.invocationId());
    return false;
  }

  /**
   * 交付事务内的复核 preflight：父未被显式停止、交付内容仍是同一份持久结果、且 DELIVERED 与命令入队同事务生效。
   *
   * <p>任一条不成立都抛 {@link SubagentTaskSettlementConflictException} 让整个接受事务回滚：绝不把"父已停止时的注入"或"内容已变化的交付"
   * 留在队列里。
   */
  private AcceptancePreflight deliveryPreflight(SubagentTask task) {
    return (tx, session, commands) -> {
      requireParentNotStopped(tx, task.parentThreadId());
      SubagentTask current = repository.findByInvocationId(task.invocationId());
      if (current == null || current.delivered() || !current.settled()) {
        throw new SubagentTaskSettlementConflictException(
            "subagent task is no longer awaiting delivery");
      }
      if (current.outcome() != task.outcome()
          || !Objects.equals(current.report(), task.report())
          || !Objects.equals(current.partialResult(), task.partialResult())
          || !Objects.equals(current.error(), task.error())) {
        throw new SubagentTaskSettlementConflictException(
            "subagent task result changed before delivery");
      }
      if (!repository.markDelivered(task.invocationId())) {
        throw new SubagentTaskSettlementConflictException("subagent task delivery lost the race");
      }
      return commands;
    };
  }

  /**
   * 幂等确认：只有"记录已不存在（随父级联删除）"或"已交付"才算完成。
   *
   * <p>本方法在交付事务之外执行，因此**绝不修改状态**：事务外的 {@code markDelivered} 只能证明记录被标记，不能证明结果消息真的入队 （例如 preflight
   * 因父刚被停止而回滚，消息并未入队）。把这种情况修补成 {@code DELIVERED} 会永久吞掉一次真实交付：结果丢失、
   * 父永远收不到通知、记录也不再被扫描。因此这里只按事实判定，其余一律返回 false，由下一轮按最新事实重新交付。
   */
  private boolean confirmDelivered(UUID invocationId) {
    SubagentTask current = repository.findByInvocationId(invocationId);
    if (current == null || current.delivered()) {
      return true;
    }
    log.warn(
        "Subagent task {} still awaits delivery after a lost race; retrying next scan",
        invocationId);
    return false;
  }

  /**
   * 事务内停止门禁：父 Thread 已明确停止时任何注入都不得落地（不唤醒被显式停止的父）。
   *
   * <p>本方法对两类调用方的原子性并不相同，注释必须如实反映这一点：
   *
   * <ul>
   *   <li>**交付**（{@link #deliveryPreflight}）：目标 Thread 就是父 Thread，{@code acceptCommands} 在调用
   *       preflight 前已按规范锁序 锁住父 Session 与父 Thread（{@code FOR
   *       UPDATE}），因此父不可能在本次读取与提交之间被停止或推进，这里读到的是稳定事实。
   *   <li>**提醒**（{@link #remindIfDue}）：目标 Thread 是子 Thread，父 Thread 并未被本事务锁定。Harness Store 要求锁 rank
   *       单调 （Session 先于 Thread）且 Thread 锁按 id 升序获取，而目标 Session/Thread 已由 {@code acceptCommands}
   *       锁住，因此 preflight 内 **无法**再取得父 Session/父 Thread 的锁——父读取只能是快照读。提醒是软预算的尽力而为：它注入的是子 Thread，
   *       不会唤醒父；父停止与提醒之间的竞态窗口由停止传播在有界轮次内收敛（子执行被停止后提醒不再产生效果）。
   * </ul>
   */
  private void requireParentNotStopped(HarnessStore.Transaction tx, UUID parentThreadId) {
    Optional<ThreadState> parent = tx.findThread(parentThreadId);
    if (parent.isEmpty()) {
      throw new SubagentTaskSettlementConflictException("parent thread no longer exists");
    }
    Entry head =
        tx.findEntry(parent.get().headEntryId())
            .orElseThrow(
                () ->
                    new SubagentTaskSettlementConflictException(
                        "parent thread head entry is missing"));
    if (SubagentTaskTerminalProjection.stoppedHead(head)) {
      throw new SubagentTaskSettlementConflictException("parent thread is explicitly stopped");
    }
  }

  /**
   * 软预算提醒：达到阈值后周期性向仍在执行的子 Thread 注入一次收敛提醒。
   *
   * <p>幂等键由 invocation 与阈值稳定派生，阈值推进在提醒事务内 CAS：命令重放不会绕过阈值推进，阈值已推进也不会重复注入。父 Thread 停止时提醒同样
   * 不注入（先传播停止）。
   */
  private void remindIfDue(HarnessRuntime runtime, SubagentTask task, ThreadSnapshot child) {
    if (child.model() == null && child.toolSiblings().isEmpty()) {
      return;
    }
    int turns = SubagentTaskTerminalProjection.countTurns(child, task.sourceHeadEntryId());
    long threshold =
        task.reminderTurn() == 0
            ? effectiveMaxTurns(task)
            : Math.addExact(task.reminderTurn(), REMINDER_INTERVAL);
    if (turns < threshold) {
      return;
    }
    CustomMessageCommandPayload reminder =
        new CustomMessageCommandPayload(SystemReminder.message(SubagentPrompts.maxTurnsReminder()));
    NewThreadCommand reminderCommand =
        new NewThreadCommand(
            reminder,
            SubagentTaskRunner.derive(task.invocationId(), REMINDER_KEY_NAMESPACE + threshold),
            ThreadCommandPayloadJsonCodec.requestHash(reminder));
    try {
      runtime.acceptCommands(
          new AcceptCommandsCommand(
              new AcceptCommandsTarget.Thread(
                  child.thread().id(),
                  child.thread().headEntryId(),
                  child.thread().nextCommandSequence()),
              List.of(reminderCommand)),
          (tx, session, commands) -> {
            requireParentNotStopped(tx, task.parentThreadId());
            if (!repository.updateReminderTurn(
                task.invocationId(), task.reminderTurn(), threshold)) {
              throw new SubagentTaskSettlementConflictException(
                  "subagent reminder threshold was already advanced");
            }
            return commands;
          });
    } catch (RuntimeException ignored) {
      // 提醒是软预算的尽力而为：子 Thread 推进、阈值已推进或父已停止都不影响结算主路径。
      log.debug("Subagent max_turns reminder was not delivered: {}", task.invocationId());
    }
  }

  private int effectiveMaxTurns(SubagentTask task) {
    return task.maxTurns() != null ? task.maxTurns() : configProvider.subagentConfig().maxTurns();
  }

  /**
   * 有界传播停止：最多以最新 Thread version 重试三次；目标已不存在视为完成。
   *
   * <p>{@code stopRequestId} 由 invocation 稳定派生，因此反复传播是幂等重放（不写新
   * marker、不取消已被取消的命令），扫描每轮重试同一停止请求不会制造副作用。
   */
  private boolean stopChild(HarnessRuntime runtime, SubagentTask task) {
    for (int attempt = 0; attempt < STOP_ATTEMPTS; attempt++) {
      try {
        ThreadSnapshot snapshot = runtime.getThreadSnapshot(task.childThreadId());
        runtime.stop(
            new StopCommand(
                task.childThreadId(),
                SubagentTaskRunner.derive(task.invocationId(), STOP_KEY_NAMESPACE),
                snapshot.thread().version()));
        return true;
      } catch (HarnessRuntimeConflictException stale) {
        // 使用新版本重读后重试停止。
      } catch (HarnessRuntimeNotFoundException notFound) {
        return true;
      }
    }
    return false;
  }
}
