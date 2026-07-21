package fun.fengwk.kkstudio.harness.runtime.thread;

import fun.fengwk.kkstudio.harness.agent.AgentAssistantMessage;
import fun.fengwk.kkstudio.harness.agent.AgentTurnEngine;
import fun.fengwk.kkstudio.harness.agent.AgentTurnEventHandler;
import fun.fengwk.kkstudio.harness.agent.AgentTurnHandle;
import fun.fengwk.kkstudio.harness.agent.AgentTurnRequest;
import fun.fengwk.kkstudio.harness.agent.AgentTurnResult;
import fun.fengwk.kkstudio.harness.agent.DefaultAgentTurnEngine;
import fun.fengwk.kkstudio.harness.agent.extension.ProviderRequestInterceptorChain;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.cache.PromptCacheRequestFinalizer;
import fun.fengwk.kkstudio.harness.runtime.context.SessionContext;
import fun.fengwk.kkstudio.harness.runtime.context.SessionContextBuilder;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.AssistantCompleted;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.CompactionCompleted;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.ThreadIdle;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.TurnStarted;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservers;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantErrorEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.CustomMessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntryStore;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInterceptorException;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageDraft;
import fun.fengwk.kkstudio.harness.tool.ToolCall;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 事件触发的、可恢复的 {@link AgentThread} 执行器。
 *
 * <p>这个类不把 Java 线程、HTTP 连接或 SSE 连接当作执行事实。唯一的执行事实是数据库中的 Thread、Input、Entry、 ToolInvocation 和
 * processor lease：任一进程重启或任一 {@code kick} 丢失后，恢复任务仍可从这些 durable facts 重新激活本处理器。
 *
 * <p><strong>两个层次的单飞：</strong>
 *
 * <ol>
 *   <li>{@link #inflight} 仅在本 JVM 合并同一 Thread 的重复 {@link #kick(long)}，避免本地 executor 堆积。
 *   <li>数据库 {@code processorToken}/{@code processorUntil} 才是跨节点正确性边界。普通 processor mutation （推进
 *       head、应用 Input、写 Entry/Event/Usage）都必须携带当前 token；Stop 是显式例外，它在锁定 Thread 后清除 token，以 fence
 *       旧持有者。
 * </ol>
 *
 * <p><strong>一次 activation 的主循环：</strong>
 *
 * <ol>
 *   <li>取得并持续续租 processor lease；失去 token 立即停止本地工作。
 *   <li>先收敛当前 head 的 Tool：派发可执行 Tool、等待未终态 Tool，或把已终态结果物化为 Tool Result Entry。
 *   <li>非 {@link ThreadStatus#RETRYING} 的安全边界一次 harvest 全部已入队 Input；同批任意数量的消息只形成 一次 response debt。
 *   <li>若当前 durable head 以 USER、TOOL 或 Compaction 结尾且尚无对应 Assistant，则执行一次 Provider Turn。
 *   <li>没有可推进事实时，使用原子 quiesce 检查并切到 IDLE；该检查防止与并发 enqueue/Tool 完成竞争而丢 work。
 * </ol>
 *
 * <p>{@link ThreadStatus#RETRYING} 是自动重试的刻意例外：它必须先从失败时的 durable head 偿还同一个 Turn，不能先 harvest 后续消息。
 * 若该 Turn 进入 Tool chain，RETRYING debt 会跨越外部等待，直到最终无 Tool 的 Assistant 成功提交后才恢复 RUNNING。
 */
public final class ThreadProcessor implements ThreadKick, ThreadProviderCancellation {
  private static final System.Logger LOGGER = System.getLogger(ThreadProcessor.class.getName());
  private static final Duration REJECTED_RETRY_DELAY = Duration.ofMillis(50);
  private static final long MAX_IDLE_TIMEOUT_CHECK_MILLIS = 1_000L;

  private final ThreadStore threadStore;
  private final ThreadTransactions transactions;
  private final SessionEntryStore entryStore;
  private final ThreadToolPort toolPort;
  private final SessionContextBuilder contextBuilder;
  private final ThreadRuntimeConfigResolver runtimeConfigResolver;
  private final ProviderMessageProjector messageProjector;
  private final TurnResourceResolver resourceResolver;
  private final CompactionService compactionService;
  private final ThreadRetryPolicyResolver retryPolicyResolver;
  private final ThreadProcessorConfig config;
  private final Clock clock;
  private final DeltaFlushScheduler deltaFlushScheduler;
  private final ProviderRequestInterceptorChain providerRequestInterceptors;
  private final HarnessLifecycleObservers lifecycleObservers;
  private final ThreadIdGenerator idGenerator;
  private final Executor executor;
  private final ScheduledExecutorService workerScheduler;

  /**
   * 本 JVM 内正在调度或执行 activation 的 Thread 集合。
   *
   * <p>它不是锁，也不参与多节点正确性；真正的执行所有权仍由数据库 lease 决定。其作用只是让同一 JVM 中的多次 kick 共用一个 executor task。
   */
  private final ConcurrentHashMap<Long, Boolean> inflight = new ConcurrentHashMap<>();

  /**
   * 尚未被本地 activation 观察到的 kick 数量。
   *
   * <p>计数本身没有业务语义，也不是 Input queue。它只保证在 activation 清空计数并退出的临界窗口中到达的 kick 会再次调度；真正需要执行什么始终由下一次
   * {@link #process(long)} 从数据库读取。
   */
  private final ConcurrentHashMap<Long, AtomicInteger> pendingKicks = new ConcurrentHashMap<>();

  /**
   * 本节点当前正在等待 Provider 回调的 Turn handle。
   *
   * <p>Stop 和 heartbeat 丢租通过它 best-effort 取消本地 Provider socket。跨节点不能保证立即断开远端 socket， 因此 {@link
   * TurnHandler#markLostOwnership()} 同时 fence 后续回调，数据库 token 校验最终保证晚到结果不能落库。
   */
  private final ConcurrentHashMap<Long, ActiveProvider> activeProviders = new ConcurrentHashMap<>();

  public ThreadProcessor(
      ThreadStore threadStore,
      ThreadTransactions transactions,
      SessionEntryStore entryStore,
      ThreadToolPort toolPort,
      SessionContextBuilder contextBuilder,
      ThreadRuntimeConfigResolver runtimeConfigResolver,
      ProviderMessageProjector messageProjector,
      TurnResourceResolver resourceResolver,
      CompactionService compactionService,
      ThreadRetryPolicyResolver retryPolicyResolver,
      ThreadProcessorConfig config,
      Clock clock,
      DeltaFlushScheduler deltaFlushScheduler,
      ProviderRequestInterceptorChain providerRequestInterceptors,
      HarnessLifecycleObservers lifecycleObservers,
      ThreadIdGenerator idGenerator,
      Executor executor,
      ScheduledExecutorService workerScheduler) {
    this.threadStore = Objects.requireNonNull(threadStore, "threadStore");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.entryStore = Objects.requireNonNull(entryStore, "entryStore");
    this.toolPort = Objects.requireNonNull(toolPort, "toolPort");
    this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder");
    this.runtimeConfigResolver =
        Objects.requireNonNull(runtimeConfigResolver, "runtimeConfigResolver");
    this.messageProjector = Objects.requireNonNull(messageProjector, "messageProjector");
    this.resourceResolver = Objects.requireNonNull(resourceResolver, "resourceResolver");
    this.compactionService = Objects.requireNonNull(compactionService, "compactionService");
    this.retryPolicyResolver = Objects.requireNonNull(retryPolicyResolver, "retryPolicyResolver");
    this.config = Objects.requireNonNull(config, "config");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.deltaFlushScheduler = Objects.requireNonNull(deltaFlushScheduler, "deltaFlushScheduler");
    this.providerRequestInterceptors =
        Objects.requireNonNull(providerRequestInterceptors, "providerRequestInterceptors");
    this.lifecycleObservers = Objects.requireNonNull(lifecycleObservers, "lifecycleObservers");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.workerScheduler = Objects.requireNonNull(workerScheduler, "workerScheduler");
  }

  @Override
  public void kick(long threadId) {
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    // 先记录 durable work 已可能变化，再尝试调度。即使本次调度被其他 activation 合并，计数也会令它再跑一轮。
    pendingKicks.compute(
        threadId,
        (id, counter) -> {
          AtomicInteger next = counter == null ? new AtomicInteger() : counter;
          next.incrementAndGet();
          return next;
        });
    schedule(threadId);
  }

  @Override
  public void cancelLocalProvider(long threadId) {
    // 调用方已先完成 durable Stop/fencing；这里仅处理同一 JVM 尚在运行的外部连接。
    ActiveProvider active = activeProviders.get(threadId);
    if (active != null) {
      active.cancel();
    }
  }

  /**
   * 尝试为某个 Thread 安排一个本地 activation。
   *
   * <p>一个 activation 会把它看到的 pending kick 清零后调用 {@link #process(long)}。在处理期间新到的 kick 不会 新建并发任务，而是在当前
   * task 返回前被下一轮循环观察到。这样既不把 executor 当 queue，也不会遗漏退出窗口 内的并发 kick。
   */
  private void schedule(long threadId) {
    if (inflight.putIfAbsent(threadId, Boolean.TRUE) != null) {
      return;
    }
    try {
      executor.execute(
          () -> {
            try {
              while (true) {
                AtomicInteger pending = pendingKicks.get(threadId);
                if (pending == null || pending.getAndSet(0) <= 0) {
                  break;
                }
                try {
                  // process 会重新读取 durable 状态；多个 kick 不需要一一对应一次模型调用。
                  process(threadId);
                } catch (RuntimeException error) {
                  LOGGER.log(
                      System.Logger.Level.WARNING,
                      "thread processor failed for " + threadId,
                      error);
                }
              }
            } finally {
              inflight.remove(threadId);
              // 清零后移除空 entry；若并发 kick 已再次递增则保留并重调度，避免任务刚退出就丢 wake-up。
              AtomicInteger leftover =
                  pendingKicks.compute(
                      threadId,
                      (id, counter) -> {
                        if (counter == null || counter.get() <= 0) {
                          return null;
                        }
                        return counter;
                      });
              if (leftover != null) {
                schedule(threadId);
              }
            }
          });
    } catch (RejectedExecutionException rejected) {
      // 保留 pending kick；在 worker scheduler 上异步重试，绝不降级为在 HTTP/提交调用线程执行模型。
      inflight.remove(threadId);
      try {
        workerScheduler.schedule(
            () -> schedule(threadId), REJECTED_RETRY_DELAY.toMillis(), TimeUnit.MILLISECONDS);
      } catch (RejectedExecutionException scheduleRejected) {
        LOGGER.log(
            System.Logger.Level.WARNING,
            "cannot schedule rejected activation retry for " + threadId + "; recovery may pick up",
            scheduleRejected);
      }
    }
  }

  /**
   * 尝试同步推进一个 Thread，直到它进入稳定边界。
   *
   * <p>生产路径通常由 {@link #kick(long)} 在共享有界 executor 中调用；保留此同步入口是为了测试和 recovery 可以
   * 直接验证同一套状态机。它不绕过数据库单飞：先 {@link ThreadStore#tryAcquire(long, String, Instant, Duration)}，
   * 获取失败即表示当前状态不可运行或其他节点持有有效 lease。
   *
   * <p>正常出口不在 finally 中盲目 release token：WAITING、RETRY_SCHEDULED、FAILED、POLICY_REJECTED 和 IDLE 分别由相应
   * durable transaction 原子地写状态并释放/撤销 lease。只有未经预期处理的运行时异常才 best-effort release，避免异常把 lease 无谓地占到过期。
   */
  public void process(long threadId) {
    Instant now = clock.instant();
    // token 是一次 acquisition 的 fencing identity，不能复用；所有持久化 mutation 都以它作为写权限凭证。
    String token = UUID.randomUUID().toString();
    Optional<AgentThread> acquired =
        threadStore.tryAcquire(threadId, token, now, config.processorLease());
    if (acquired.isEmpty()) {
      return;
    }
    try {
      while (true) {
        LoopExit exit = runLoop(threadId, token);
        switch (exit) {
          case WAITING_EXTERNAL -> {
            // waitForExternal 已把 Thread 置为 WAITING（或保留 RETRYING debt）并释放 token。
            return;
          }
          case LOST_OWNERSHIP -> {
            // token 已失效，当前 JVM 不得再写任何终态；新 owner 或 recovery 将从 durable facts 继续。
            return;
          }
          case POLICY_REJECTED -> {
            // beginTurn 已持久化策略拒绝，不能把它当 Provider 瞬态错误自动重试。
            return;
          }
          case RETRY_SCHEDULED -> {
            // scheduleRetry 已写入到期时间并释放 token；本地 timer/recovery 会在到期后重新 kick。
            return;
          }
          case FAILED -> {
            // failure transaction 已写 FAILED 并释放 token；后续用户消息会重新启动正常循环。
            return;
          }
          case IDLE -> {
            // runLoop 的“看起来空闲”不是最终结论：enqueue 或 Tool 终态可能刚好在检查后提交。
            ThreadTransactions.QuiescenceResult quiescence =
                transactions.quiesce(threadId, token, clock.instant());
            if (quiescence == ThreadTransactions.QuiescenceResult.IDLE) {
              // 只有 IDLE 状态和 token 释放均已 durable commit 后，Observer 才能被通知。
              threadStore
                  .find(threadId)
                  .ifPresent(
                      thread ->
                          lifecycleObservers.publish(
                              new ThreadIdle(thread.id(), thread.sessionId(), clock.instant())));
              return;
            }
            if (quiescence == ThreadTransactions.QuiescenceResult.LOST_OWNERSHIP) {
              return;
            }
            // WORK_REMAINS：仍持有 token，立即回到 runLoop 收敛新出现的 durable work。
          }
        }
      }
    } catch (RuntimeException error) {
      // 这个分支没有可证明的业务终态；释放匹配 token 的 lease，让 recovery/后续 kick 能接管。
      threadStore.release(threadId, token, clock.instant());
      throw error;
    }
  }

  /** {@link #runLoop(long, String)} 的稳定出口；每个出口都明确对应下一位推进者。 */
  private enum LoopExit {
    /** 没有当前可推进的 durable work，仍须经过 quiesce 的最终竞争检查。 */
    IDLE,
    /** 存在 Tool/permission 等外部依赖；transaction 已释放 token，外部终态回调会再次 kick。 */
    WAITING_EXTERNAL,
    /** lease 或 fencing token 已失效，当前 activation 必须无条件停止。 */
    LOST_OWNERSHIP,
    /** 自动重试已写入 durable 到期时间并释放 token。 */
    RETRY_SCHEDULED,
    /** Provider/setup/tool prepare 已写 durable FAILED，等待下一条用户消息重新启动。 */
    FAILED,
    /** Policy admission rejection: force-release token; do not treat as provider retry. */
    POLICY_REJECTED
  }

  /** 单次 Provider Turn 的结果；CONTINUE 表示 durable 状态已有推进但还不能结束当前主循环。 */
  private enum TurnOutcome {
    CONTINUE,
    COMPLETED,
    LOST_OWNERSHIP,
    RETRY_SCHEDULED,
    FAILED,
    POLICY_REJECTED
  }

  /**
   * 在仍持有 token 时反复推进 durable work，直到抵达一个稳定边界。
   *
   * <p>顺序不可交换：先处理当前 head 的 Tool，才能安全地把新的 mailbox Input 加入 Context；先处理 RETRYING debt，才能避免后续用户消息越过失败
   * Turn；普通状态下则必须先 harvest 完整 cutoff，才能让同一批消息共享一次 Assistant 响应。
   *
   * <p>循环上限是编程错误保护，而不是正常分页机制。正常每次迭代都应收敛 durable state、进入等待，或退出空闲。
   */
  private LoopExit runLoop(long threadId, String token) {
    int safety = 0;
    while (safety++ < 10_000) {
      Instant now = clock.instant();
      // 每轮先续租；即便本次循环很快，统一的先验检查也使所有后续写入建立在有效 lease 上。
      if (!threadStore.renew(threadId, token, now, config.processorLease())) {
        return LoopExit.LOST_OWNERSHIP;
      }
      // renew 成功后仍读取快照并复核 token，防御 Stop/其他节点在两个 DB 操作之间改变所有权。
      AgentThread thread =
          threadStore
              .find(threadId)
              .orElseThrow(() -> new IllegalStateException("thread disappeared: " + threadId));
      if (!token.equals(thread.processorToken())) {
        return LoopExit.LOST_OWNERSHIP;
      }

      // 当前 Assistant 的 Tool chain 必须先收敛：Tool Result 会改变下一次 Provider Context。
      ToolProgress toolProgress = progressTools(thread, token, now);
      if (toolProgress == ToolProgress.WAITING) {
        if (transactions.waitForExternal(threadId, token, "tools_or_permission", clock.instant())) {
          return LoopExit.WAITING_EXTERNAL;
        }
        // 事务返回 false 时不能假设一定丢租：先复查 snapshot；若仍是 owner，重试主循环收敛并发变化。
        AgentThread refreshed = threadStore.find(threadId).orElse(thread);
        if (!token.equals(refreshed.processorToken())) {
          return LoopExit.LOST_OWNERSHIP;
        }
        continue;
      }
      if (toolProgress == ToolProgress.PROGRESSED) {
        continue;
      }
      if (toolProgress == ToolProgress.LOST_OWNERSHIP) {
        return LoopExit.LOST_OWNERSHIP;
      }

      // RETRYING 是唯一禁止 harvest 的状态：先用失败时 durable head 偿还同一 Turn，并跨 Tool
      // chain 保留该 debt，直到最终无-tool assistant 成功。否则新输入可越过失败请求而改变其 Context。
      thread =
          threadStore
              .find(threadId)
              .orElseThrow(() -> new IllegalStateException("thread disappeared: " + threadId));
      if (!token.equals(thread.processorToken())) {
        return LoopExit.LOST_OWNERSHIP;
      }
      if (thread.status() == ThreadStatus.RETRYING) {
        TurnOutcome outcome = executeModelTurn(thread, token);
        switch (outcome) {
          case COMPLETED -> {
            // 只有最终无 Tool 的 Assistant commit 才返回 COMPLETED；此时才可清除 retry debt 并恢复普通 harvest。
            if (!transactions.completeRetryDebt(threadId, token, clock.instant())) {
              return LoopExit.LOST_OWNERSHIP;
            }
            continue;
          }
          case CONTINUE -> {
            continue;
          }
          case LOST_OWNERSHIP -> {
            return LoopExit.LOST_OWNERSHIP;
          }
          case POLICY_REJECTED -> {
            return LoopExit.POLICY_REJECTED;
          }
          case RETRY_SCHEDULED -> {
            return LoopExit.RETRY_SCHEDULED;
          }
          case FAILED -> {
            return LoopExit.FAILED;
          }
        }
      }

      // 普通安全边界：在判定 response debt 前，原子 harvest 此刻完整 cutoff 内的全部 QUEUED Input。
      // 因此 N 条连续 USER/CUSTOM 消息只生成一个后续 Provider Turn；只有配置的 batch 不会制造 debt。
      ThreadTransactions.HarvestResult harvested =
          transactions.harvestQueuedInputs(threadId, token, clock.instant());
      if (harvested.harvested()) {
        continue;
      }

      thread =
          threadStore
              .find(threadId)
              .orElseThrow(() -> new IllegalStateException("thread disappeared: " + threadId));
      if (!token.equals(thread.processorToken())) {
        return LoopExit.LOST_OWNERSHIP;
      }
      // Input/Tool/Compaction 已 materialize 到 Entry path 后，再从 durable head 计算是否欠一次 Assistant。
      if (headRequiresModel(thread)) {
        TurnOutcome outcome = executeModelTurn(thread, token);
        switch (outcome) {
          case CONTINUE, COMPLETED -> {
            continue;
          }
          case LOST_OWNERSHIP -> {
            return LoopExit.LOST_OWNERSHIP;
          }
          case POLICY_REJECTED -> {
            return LoopExit.POLICY_REJECTED;
          }
          case RETRY_SCHEDULED -> {
            return LoopExit.RETRY_SCHEDULED;
          }
          case FAILED -> {
            return LoopExit.FAILED;
          }
        }
      }
      return LoopExit.IDLE;
    }
    throw new IllegalStateException("thread processor safety limit exceeded for " + threadId);
  }

  /**
   * 收敛当前 head Assistant 所属的 ToolInvocation。
   *
   * <p>Tool 不在 ThreadProcessor 内同步执行。这里仅派发 due invocation、观察非终态 invocation，并把全部终态 result 以固定顺序追加为
   * Tool Result Entry。任何 non-terminal（包括 WAITING_APPROVAL）都会令 Thread 释放 token 等待外部
   * worker/用户决策；外部完成后通过 durable commit + kick 重新进入本方法。
   */
  private ToolProgress progressTools(AgentThread thread, String token, Instant now) {
    // 始终尝试派发 due tools，以便 WAITING_APPROVAL 旁的 QUEUED 兄弟可被推进。
    toolPort.dispatchDue(thread.id(), now);
    List<ToolInvocation> open = toolPort.listNonTerminal(thread.id(), thread.headEntryId());
    if (!open.isEmpty()) {
      // 任意非终态（含 WAITING_APPROVAL / CANCEL_REQUESTED）都进入外部等待。
      return ToolProgress.WAITING;
    }
    if (toolPort.hasTerminalResultsPendingApply(thread.id(), thread.headEntryId())) {
      // apply 同时推进 Thread head；成功后必须重新循环，因为新 Tool Result 会形成下一次 response debt。
      if (transactions.applyTerminalToolResults(thread.id(), token, now)) {
        return ToolProgress.PROGRESSED;
      }
      AgentThread refreshed = threadStore.find(thread.id()).orElse(thread);
      if (!token.equals(refreshed.processorToken())) {
        return ToolProgress.LOST_OWNERSHIP;
      }
      return ToolProgress.WAITING;
    }
    return ToolProgress.NONE;
  }

  /**
   * 根据当前 branch path 判定是否欠一轮 Provider 响应（response debt）。
   *
   * <p>从 head 反向忽略配置等非消息 Entry：最近相关事实为 USER、TOOL 或 Compaction 时，说明后面尚未有 Assistant，必须执行一轮模型；最近相关事实为
   * Assistant 时，说明债务已经偿还。SYSTEM Custom Message 只改变 Context，不单独要求模型回答。
   */
  private boolean headRequiresModel(AgentThread thread) {
    List<SessionEntry> path = entryStore.loadPath(thread.sessionId(), thread.headEntryId());
    for (int i = path.size() - 1; i >= 0; i--) {
      SessionEntryPayloadRole payloadRole = payloadRole(path.get(i));
      if (payloadRole == SessionEntryPayloadRole.COMPACTION
          || payloadRole == SessionEntryPayloadRole.USER_OR_TOOL) {
        return true;
      }
      if (payloadRole == SessionEntryPayloadRole.ASSISTANT) {
        return false;
      }
    }
    return false;
  }

  /** 将不同 Entry payload 归约为 response-debt 判定真正关心的四类语义。 */
  private static SessionEntryPayloadRole payloadRole(SessionEntry entry) {
    if (entry.payload() instanceof CompactionEntryPayload) {
      return SessionEntryPayloadRole.COMPACTION;
    }
    // AssistantErrorEntryPayload is a failed-attempt audit. It must not request another model
    // call when it sits at the head outside of a RETRYING cycle, so map it explicitly to OTHER
    // rather than relying on fall-through behavior.
    if (entry.payload() instanceof AssistantErrorEntryPayload) {
      return SessionEntryPayloadRole.OTHER;
    }
    AgentMessage message = null;
    if (entry.payload() instanceof MessageEntryPayload value) {
      message = value.message();
    } else if (entry.payload() instanceof CustomMessageEntryPayload value) {
      message = value.message();
    }
    if (message == null) {
      return SessionEntryPayloadRole.OTHER;
    }
    return switch (message.role()) {
      case USER, TOOL -> SessionEntryPayloadRole.USER_OR_TOOL;
      case ASSISTANT -> SessionEntryPayloadRole.ASSISTANT;
      default -> SessionEntryPayloadRole.OTHER;
    };
  }

  private enum SessionEntryPayloadRole {
    /** USER 或 TOOL 均要求后续 Assistant 继续对话。 */
    USER_OR_TOOL,
    /** Assistant 已完成当前 response debt。 */
    ASSISTANT,
    /** Compaction 替换了部分 Context，仍需重新请求 Assistant。 */
    COMPACTION,
    /** 配置、SYSTEM custom message 等只影响 Context，不直接制造 response debt。 */
    OTHER
  }

  /**
   * 执行一轮已被 durable {@code beginTurn} 准入的 Provider 调用。
   *
   * <p>该方法在共享 executor 的 activation 线程中等待本轮完成；Provider 的 stream callback 和 lease heartbeat
   * 可以来自其他线程。所有 callback 通过 {@link TurnHandler} 汇聚为一次 token-fenced durable terminal
   * transaction，不能直接修改 Thread 内存状态作为事实。
   *
   * <p>执行阶段依次为：
   *
   * <ol>
   *   <li>原子准入并记录 {@code TURN_STARTED}，拒绝时绝不解析资源或调用 Provider。
   *   <li>从当前 Entry head 构建 Context，冻结本轮 Provider/Model/Tool 资源。
   *   <li>预分配 Assistant Entry ID，以便开始、Delta、完成事件能够关联到同一个未来 Entry。
   *   <li>启动 heartbeat，并注册可由 Stop/丢租取消的本地 Provider handle。
   *   <li>等待完成、失败、超时或 fencing 丢失；finally 中只撤销瞬态资源，durable 状态由 callback transaction 决定。
   * </ol>
   */
  private TurnOutcome executeModelTurn(AgentThread thread, String token) {
    Instant turnStartedAt = clock.instant();
    // 原子准入 + TURN_STARTED（或 policy failure events）。未 ADMITTED 时绝不调用 Provider。
    // cancellation/maxTurns 仅在这个边界生效；已准入的 in-flight Turn 不会被后续策略变化隐式中断。
    ThreadTransactions.BeginTurnResult begin =
        transactions.beginTurn(thread.id(), token, turnStartedAt);
    if (begin.isLostOwnership()) {
      return TurnOutcome.LOST_OWNERSHIP;
    }
    if (begin.isRejected()) {
      return TurnOutcome.POLICY_REJECTED;
    }
    lifecycleObservers.publish(new TurnStarted(thread.id(), thread.sessionId(), turnStartedAt));

    // Assistant Entry 直到 terminal commit 才真正存在；提前分配 ID 仅用于把流式 Event 稳定关联到它。
    // 必须在 beginTurn 之后、资源解析之前分配，使 resource resolution 失败也能复用该 id 落库 error
    // entry，UI/审计与 SSE 临时投影始终引用同一个 planned id。
    long plannedAssistantEntryId = idGenerator.newSessionEntryId();

    SessionContext context;
    TurnResources resources;
    try {
      // 运行时配置来自 Thread 状态 + 当前 AgentDefinition；消息路径只负责 transcript/compaction 投影。
      context =
          contextBuilder.build(
              thread.sessionId(), thread.headEntryId(), runtimeConfigResolver.resolve(thread));
      // 将配置解析为本 Turn 不可变的 provider/model/tool/environment snapshot，避免流中途读到新配置。
      resources = resourceResolver.resolve(thread.sessionId(), thread.id(), context.config());
    } catch (RuntimeException error) {
      if (!failTurn(thread, token, plannedAssistantEntryId, error)) {
        return TurnOutcome.LOST_OWNERSHIP;
      }
      return TurnOutcome.FAILED;
    }

    DeltaBatcher batcher =
        new DeltaBatcher(
            thread.id(),
            plannedAssistantEntryId,
            token,
            transactions,
            clock,
            deltaFlushScheduler,
            config.deltaFlushInterval(),
            config.deltaBatchBytes(),
            turnStartedAt);

    TurnHandler handler =
        new TurnHandler(thread, token, plannedAssistantEntryId, context, resources, batcher);
    // Host interceptor 先执行，再追加以 Session 为作用域的 prompt-cache finalizer。
    ProviderRequestInterceptorChain chain =
        providerRequestInterceptors.andThen(new PromptCacheRequestFinalizer(thread.sessionId()));
    AgentTurnEngine engine = new DefaultAgentTurnEngine(resources.provider(), chain);

    CompletableFuture<Void> done = new CompletableFuture<>();
    handler.completion = done;
    // 先注册占位对象，再调用 engine：engine 可能同步触发 onStarted/onFailed；Stop 也可能早于 handle 返回。
    ActiveProvider active = new ActiveProvider(handler);
    activeProviders.put(thread.id(), active);
    // heartbeat 必须严格小于 lease/2，使用 lease/3，为调度抖动和一次 DB round-trip 保留余量。
    Duration renewEvery = config.processorLease().dividedBy(3);
    if (renewEvery.isZero() || renewEvery.isNegative()) {
      renewEvery = Duration.ofMillis(1);
    }
    ScheduledFuture<?> renewFuture =
        workerScheduler.scheduleAtFixedRate(
            () -> {
              if (handler.lostOwnership || handler.terminal.get()) {
                return;
              }
              if (!threadStore.renew(
                  thread.id(), token, clock.instant(), config.processorLease())) {
                // 先原子标记丢租并 complete 本地 wait，再 cancel；后续 callback/delta 必须 no-op。
                active.cancel();
              }
            },
            renewEvery.toMillis(),
            renewEvery.toMillis(),
            TimeUnit.MILLISECONDS);
    // 两类 Provider timeout 属于本次冻结资源，而不是部署级 ThreadProcessor 参数。总超时从请求启动
    // 时开始；idle timeout 只在没有 Provider 生命周期活动（started/delta/terminal）时触发。
    ScheduledFuture<?> totalTimeoutFuture =
        workerScheduler.schedule(
            () -> {
              if (handler.fail(
                  new ProviderException(ProviderErrorKind.TRANSIENT, "model call timed out"))) {
                active.cancelHandle();
              }
            },
            resources.modelCallTimeoutPolicy().modelCallTimeout().toMillis(),
            TimeUnit.MILLISECONDS);
    long idleCheckMillis =
        Math.max(
            1L,
            Math.min(
                resources.modelCallTimeoutPolicy().modelCallIdleTimeout().toMillis(),
                MAX_IDLE_TIMEOUT_CHECK_MILLIS));
    ScheduledFuture<?> idleTimeoutFuture =
        workerScheduler.scheduleAtFixedRate(
            () -> {
              if (handler.isIdleFor(
                      resources.modelCallTimeoutPolicy().modelCallIdleTimeout(), clock.instant())
                  && handler.fail(
                      new ProviderException(
                          ProviderErrorKind.TRANSIENT, "model call idle timed out"))) {
                active.cancelHandle();
              }
            },
            idleCheckMillis,
            idleCheckMillis,
            TimeUnit.MILLISECONDS);
    try {
      // execute 可以同步抛错，也可以在返回前同步发回调；因此 set() 会处理“handle 到手前已失去所有权”。
      AgentTurnHandle turnHandle =
          engine.execute(
              new AgentTurnRequest(
                  resources.model(),
                  resources.variant(),
                  messageProjector.project(context.messages()),
                  resources.toolDescriptors()),
              handler);
      active.set(turnHandle);
      if (handler.lostOwnership) {
        turnHandle.cancel();
        return TurnOutcome.LOST_OWNERSHIP;
      }
      // completion 由 onCompleted/onFailed/markLostOwnership/timeout 终结；timeout 先持久化失败，
      // 再取消底层调用，避免取消 callback 抢先把原因降级为 CANCELLED。
      done.join();
    } catch (RuntimeException error) {
      if (!handler.lostOwnership) {
        handler.onFailed(
            new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "cannot start agent turn", error));
      }
    } finally {
      // 只清理进程内资源；已落库的 Entry/Event/status 不在此处回滚或二次修改。
      renewFuture.cancel(false);
      totalTimeoutFuture.cancel(false);
      idleTimeoutFuture.cancel(false);
      activeProviders.remove(thread.id(), active);
    }
    if (handler.lostOwnership) {
      return TurnOutcome.LOST_OWNERSHIP;
    }
    if (handler.retryScheduled) {
      return TurnOutcome.RETRY_SCHEDULED;
    }
    if (handler.failed) {
      return TurnOutcome.FAILED;
    }
    // Tool chain 与成功 Compaction 都会推进 durable state，但尚未完成最终 Assistant debt，因此继续主循环。
    return handler.completedTurn ? TurnOutcome.COMPLETED : TurnOutcome.CONTINUE;
  }

  /** 将 Context/资源解析等 Provider 调用前异常落为 durable FAILED，而非让 activation 静默退出。 */
  private boolean failTurn(
      AgentThread thread, String token, long plannedAssistantEntryId, RuntimeException error) {
    Instant now = clock.instant();
    String reason = "turn_setup_failed";
    AssistantErrorEntryPayload payload =
        new AssistantErrorEntryPayload(
            ProviderErrorKind.INVALID_REQUEST.name(),
            message(error),
            thread.retryAttempt(),
            null,
            false);
    return transactions.recordAssistantError(
        thread.id(),
        token,
        plannedAssistantEntryId,
        payload,
        ThreadTransactions.AssistantErrorOutcome.FAILED,
        null,
        List.of(
            new ThreadEventDraft(
                ThreadEventType.ASSISTANT_FAILED,
                plannedAssistantEntryId,
                ThreadEventPayloads.of(
                    "kind",
                    ProviderErrorKind.INVALID_REQUEST.name(),
                    "message",
                    message(error),
                    "retryScheduled",
                    false,
                    "retryAttempt",
                    thread.retryAttempt(),
                    "maxRetries",
                    null)),
            new ThreadEventDraft(
                ThreadEventType.THREAD_FAILED,
                ThreadEventPayloads.of(
                    "reason",
                    reason,
                    "message",
                    message(error),
                    "kind",
                    ProviderErrorKind.INVALID_REQUEST.name(),
                    "retryAttempt",
                    thread.retryAttempt()))),
        now);
  }

  private static String message(Throwable error) {
    return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
  }

  private void scheduleRetryWake(long threadId, Instant retryAt) {
    // Duration#toMillis 向下取整；不留余量可能让本地 wake 比 durable retry_at 早一个子毫秒，
    // tryAcquire 随即拒绝且本次 wake 被消耗。额外 1ms 使本地 timer 不早于持久到期点。
    long delayMillis = Math.max(1L, Duration.between(clock.instant(), retryAt).toMillis() + 1L);
    try {
      workerScheduler.schedule(() -> kick(threadId), delayMillis, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException rejected) {
      LOGGER.log(
          System.Logger.Level.WARNING,
          "cannot schedule automatic retry for " + threadId + "; durable recovery will pick up",
          rejected);
    }
  }

  private enum ToolProgress {
    /** 当前 head 没有 Tool 需要处理。 */
    NONE,
    /** 已把终态 Tool Result 写入 Entry path；下一轮应重新判定 response debt。 */
    PROGRESSED,
    /** 当前 Tool 尚依赖 worker、权限或外部系统，Thread 应释放 token。 */
    WAITING,
    /** Tool result apply 的 fencing 校验失败，不能继续使用当前 token。 */
    LOST_OWNERSHIP
  }

  /**
   * 将 AgentTurnEngine 的异步回调转换为 token-fenced durable Thread 事实。
   *
   * <p>{@link #terminal} 使成功和失败互斥且至多执行一次；{@link #lostOwnership} 则是比 Provider cancel 更强的 写入熔断器。只要
   * Stop、lease 到期或其他节点接管使 token 无效，后续 started/delta/completed/failed callback 都不能再把旧 Turn 写回数据库。
   */
  private final class TurnHandler implements AgentTurnEventHandler {
    private final AgentThread thread;
    private final String token;
    private final long plannedAssistantEntryId;
    private final SessionContext context;
    private final TurnResources resources;
    private final DeltaBatcher batcher;

    /** 防止 Provider 重复/并发发送 completed 与 failed。 */
    private final AtomicBoolean terminal = new AtomicBoolean();

    /** Provider 已提交 durable failure；executeModelTurn 应以 FAILED 退出。 */
    private volatile boolean failed;

    /** Provider 瞬态失败已经提交自动 retry schedule；当前 activation 必须立即结束。 */
    private volatile boolean retryScheduled;

    /** 当前 token 已不可写；一旦置位，所有后续 Provider callback 都应退化为 no-op。 */
    private volatile boolean lostOwnership;

    /** 仅指最终无 Tool Assistant 已成功提交；用于清除 RETRYING debt。 */
    private volatile boolean completedTurn;

    /** 让 activation 线程等待 Provider 回调终结，而不是轮询 Provider handle。 */
    private volatile CompletableFuture<Void> completion = new CompletableFuture<>();

    /** 最近一次 Provider lifecycle activity；idle timeout 从 Turn 创建时开始计时。 */
    private volatile Instant lastActivityAt;

    private TurnHandler(
        AgentThread thread,
        String token,
        long plannedAssistantEntryId,
        SessionContext context,
        TurnResources resources,
        DeltaBatcher batcher) {
      this.thread = thread;
      this.token = token;
      this.plannedAssistantEntryId = plannedAssistantEntryId;
      this.context = context;
      this.resources = resources;
      this.batcher = batcher;
      this.lastActivityAt = clock.instant();
    }

    /**
     * 将本地 Turn fence 为失去所有权，并唤醒正在等待 completion 的 activation。
     *
     * <p>不在这里直接写数据库：调用此方法的原因正是 token 已不能证明写权限。数据库中的 Stop、lease replacement 或其他 transaction
     * 才是权威状态变更。
     */
    void markLostOwnership() {
      lostOwnership = true;
      terminal.set(true);
      completion.complete(null);
    }

    @Override
    public void onStarted() {
      if (terminal.get() || lostOwnership) {
        return;
      }
      markActivity();
      try {
        // plannedAssistantEntryId 尚未 materialize；started event 让前端可提前建立同一 ID 的流式投影。
        if (!transactions.appendEvents(
            thread.id(),
            token,
            List.of(
                new ThreadEventDraft(
                    ThreadEventType.ASSISTANT_STARTED,
                    plannedAssistantEntryId,
                    ThreadEventPayloads.of(
                        "assistantEntryId", Long.toString(plannedAssistantEntryId)))),
            clock.instant())) {
          markLostOwnership();
        }
      } catch (ConcurrentModificationException concurrency) {
        markLostOwnership();
      }
    }

    @Override
    public void onDelta(ProviderStreamEvent event) {
      if (!terminal.get() && !lostOwnership) {
        markActivity();
        // Delta 只进入有界 batch；最终 Entry 仍以 onCompleted 的完整 Provider response 为准。
        batcher.add(event);
      }
    }

    @Override
    public void onCompleted(AgentTurnResult result) {
      if (lostOwnership || !terminal.compareAndSet(false, true)) {
        return;
      }
      try {
        // completion event 前先 flush，保证 durable journal 里的 delta 先于 terminal 可见。
        batcher.flush();
        ProviderResponse response = result.providerResponse();
        ModelUsageDraft usageDraft;
        try {
          usageDraft = ModelUsageDraft.from(result.providerRequest(), response);
        } catch (RuntimeException error) {
          permanentFailure(
              new ProviderException(
                  ProviderErrorKind.INVALID_REQUEST, "invalid model usage draft", error));
          return;
        }
        MessageEntryPayload assistant = assistantPayload(result.assistantMessage(), response);
        List<ToolCall> toolCalls = result.toolCalls();
        Instant now = clock.instant();
        ThreadEventDraft assistantCompleted =
            new ThreadEventDraft(
                ThreadEventType.ASSISTANT_COMPLETED,
                plannedAssistantEntryId,
                ThreadEventPayloads.of(
                    "toolCallCount",
                    toolCalls.size(),
                    "stopReason",
                    response.stopReason().name(),
                    "usage",
                    response.usage(),
                    "cost",
                    response.cost()));
        if (toolCalls.isEmpty()) {
          // 一个原子事务同时提交 Assistant Entry、head 推进、usage 与 completed event。
          try {
            if (!transactions.commitFinalAssistant(
                thread.id(),
                token,
                plannedAssistantEntryId,
                assistant,
                usageDraft,
                List.of(assistantCompleted),
                now)) {
              markLostOwnership();
              return;
            }
            completedTurn = true;
            publishAssistantCompleted(response, toolCalls.size(), now);
          } catch (ConcurrentModificationException concurrency) {
            markLostOwnership();
          }
          return;
        }
        try {
          // 有 Tool 时 Assistant Entry 与 Invocation 必须一起提交；head 停在 Assistant，等待 Tool Result 回填。
          if (!transactions.prepareTools(
              thread.id(),
              token,
              plannedAssistantEntryId,
              assistant,
              usageDraft,
              toolCalls,
              resources.toolBindings(),
              resources.workdir(),
              resources.environmentRoot(),
              context.config().yoloEnabled(),
              List.of(assistantCompleted),
              now)) {
            markLostOwnership();
            return;
          }
          publishAssistantCompleted(response, toolCalls.size(), now);
          toolPort.dispatchDue(thread.id(), now);
        } catch (ConcurrentModificationException concurrency) {
          markLostOwnership();
        } catch (IllegalArgumentException | ToolInterceptorException error) {
          failToolPreparation(error, now);
        }
      } finally {
        completion.complete(null);
      }
    }

    @Override
    public void onFailed(ProviderException error) {
      fail(error);
    }

    private boolean fail(ProviderException error) {
      if (lostOwnership || !terminal.compareAndSet(false, true)) {
        return false;
      }
      try {
        // 已送达的部分 delta 仍是流式观测事实；flush 后再写失败，前端可以完整地呈现中断过程。
        batcher.flush();
        switch (error.kind()) {
          case OVERFLOW -> overflowFailure(error);
          case TRANSIENT -> scheduleOrFail(error);
          case CANCELLED, AUTHENTICATION, BILLING, INVALID_REQUEST -> permanentFailure(error);
        }
      } finally {
        completion.complete(null);
      }
      return true;
    }

    private boolean isIdleFor(Duration idleTimeout, Instant now) {
      if (terminal.get() || lostOwnership) {
        return false;
      }
      return !now.isBefore(lastActivityAt.plus(idleTimeout));
    }

    private void markActivity() {
      lastActivityAt = clock.instant();
    }

    /**
     * 仅对 Provider OVERFLOW 尝试压缩 Context。
     *
     * <p>压缩成功会写入新的 Compaction Entry 而不是伪造 Assistant 失败；主循环随后从新 head 重建 Context 并重试同一 response
     * debt。无可压缩内容或压缩自身失败时，才走永久失败路径。
     */
    private void overflowFailure(ProviderException error) {
      Instant now = clock.instant();
      Optional<CompactionEntryPayload> compaction;
      try {
        compaction = compactionService.compact(thread.sessionId(), thread.headEntryId(), context);
      } catch (RuntimeException compactError) {
        permanentFailure(
            new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "compaction failed", compactError));
        return;
      }
      if (compaction.isEmpty()) {
        permanentFailure(error);
        return;
      }
      CompactionEntryPayload payload = compaction.orElseThrow();
      try {
        if (!transactions.compact(
            thread.id(),
            token,
            payload,
            List.of(
                new ThreadEventDraft(ThreadEventType.COMPACTION_STARTED, ThreadEventPayloads.of()),
                new ThreadEventDraft(
                    ThreadEventType.COMPACTION_COMPLETED,
                    ThreadEventPayloads.of(
                        "firstKeptEntryId", Long.toString(payload.firstKeptEntryId())))),
            now)) {
          markLostOwnership();
          return;
        }
        lifecycleObservers.publish(
            new CompactionCompleted(
                thread.id(), thread.sessionId(), payload.firstKeptEntryId(), now));
      } catch (ConcurrentModificationException concurrency) {
        markLostOwnership();
      }
    }

    /** 瞬态 Provider failure 按当前策略写入一次 durable automatic retry。 */
    private void scheduleOrFail(ProviderException error) {
      ThreadRetryPolicy policy;
      try {
        policy = retryPolicyResolver.resolve();
      } catch (RuntimeException policyError) {
        permanentFailure(
            new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "cannot resolve retry policy", policyError));
        return;
      }
      int attempt = thread.retryAttempt() + 1;
      if (!policy.allowsRetry(attempt)) {
        permanentFailure(error, "retry_exhausted", policy.maxRetries());
        return;
      }
      Instant now = clock.instant();
      Duration delay = policy.delayBeforeRetry(attempt);
      Instant retryAt = now.plus(delay);
      try {
        AssistantErrorEntryPayload payload =
            new AssistantErrorEntryPayload(
                error.kind().name(), message(error), attempt, policy.maxRetries(), true);
        if (!transactions.recordAssistantError(
            thread.id(),
            token,
            plannedAssistantEntryId,
            payload,
            ThreadTransactions.AssistantErrorOutcome.RETRY_SCHEDULED,
            retryAt,
            List.of(
                new ThreadEventDraft(
                    ThreadEventType.ASSISTANT_FAILED,
                    plannedAssistantEntryId,
                    ThreadEventPayloads.of(
                        "kind",
                        error.kind().name(),
                        "message",
                        message(error),
                        "retryScheduled",
                        true,
                        "retryAttempt",
                        attempt,
                        "maxRetries",
                        policy.maxRetries())),
                new ThreadEventDraft(
                    ThreadEventType.THREAD_RETRY_SCHEDULED,
                    ThreadEventPayloads.of(
                        "retryAttempt",
                        attempt,
                        "maxRetries",
                        policy.maxRetries(),
                        "backoffStrategy",
                        policy.backoffStrategy().name(),
                        "delayMillis",
                        delay.toMillis(),
                        "retryAt",
                        retryAt,
                        "kind",
                        error.kind().name()))),
            now)) {
          markLostOwnership();
          return;
        }
        retryScheduled = true;
        scheduleRetryWake(thread.id(), retryAt);
      } catch (ConcurrentModificationException concurrency) {
        markLostOwnership();
      }
    }

    /** 将不可恢复的 Provider failure 固化为 FAILED。 */
    private void permanentFailure(ProviderException error) {
      permanentFailure(error, "not_retryable", null);
    }

    private void permanentFailure(
        ProviderException error, String reason, Integer configuredMaxRetries) {
      Instant now = clock.instant();
      try {
        AssistantErrorEntryPayload payload =
            new AssistantErrorEntryPayload(
                error.kind().name(),
                message(error),
                thread.retryAttempt(),
                configuredMaxRetries,
                false);
        if (!transactions.recordAssistantError(
            thread.id(),
            token,
            plannedAssistantEntryId,
            payload,
            ThreadTransactions.AssistantErrorOutcome.FAILED,
            null,
            List.of(
                new ThreadEventDraft(
                    ThreadEventType.ASSISTANT_FAILED,
                    plannedAssistantEntryId,
                    ThreadEventPayloads.of(
                        "kind",
                        error.kind().name(),
                        "message",
                        message(error),
                        "retryScheduled",
                        false,
                        "retryAttempt",
                        thread.retryAttempt(),
                        "maxRetries",
                        configuredMaxRetries)),
                new ThreadEventDraft(
                    ThreadEventType.THREAD_FAILED,
                    ThreadEventPayloads.of(
                        "reason",
                        reason,
                        "kind",
                        error.kind().name(),
                        "message",
                        message(error),
                        "retryAttempt",
                        thread.retryAttempt(),
                        "maxRetries",
                        configuredMaxRetries))),
            now)) {
          markLostOwnership();
          return;
        }
        failed = true;
      } catch (ConcurrentModificationException concurrency) {
        markLostOwnership();
      }
    }

    /** Assistant 已产生 ToolCall 但无法原子准备 Invocation 时，不能留下半提交 Tool chain。 */
    private void failToolPreparation(RuntimeException error, Instant now) {
      try {
        AssistantErrorEntryPayload payload =
            new AssistantErrorEntryPayload(
                ProviderErrorKind.INVALID_REQUEST.name(),
                message(error),
                thread.retryAttempt(),
                null,
                false);
        if (!transactions.recordAssistantError(
            thread.id(),
            token,
            plannedAssistantEntryId,
            payload,
            ThreadTransactions.AssistantErrorOutcome.FAILED,
            null,
            List.of(
                new ThreadEventDraft(
                    ThreadEventType.ASSISTANT_FAILED,
                    plannedAssistantEntryId,
                    ThreadEventPayloads.of(
                        "kind",
                        ProviderErrorKind.INVALID_REQUEST.name(),
                        "message",
                        message(error),
                        "retryScheduled",
                        false,
                        "retryAttempt",
                        thread.retryAttempt(),
                        "maxRetries",
                        null)),
                new ThreadEventDraft(
                    ThreadEventType.THREAD_FAILED,
                    ThreadEventPayloads.of(
                        "reason",
                        "tool_preparation_failed",
                        "message",
                        message(error),
                        "kind",
                        ProviderErrorKind.INVALID_REQUEST.name(),
                        "retryAttempt",
                        thread.retryAttempt()))),
            now)) {
          markLostOwnership();
          return;
        }
        failed = true;
      } catch (ConcurrentModificationException concurrency) {
        markLostOwnership();
      }
    }

    /** 仅在 Assistant durable commit 成功后通知观测扩展；Observer 不参与执行恢复。 */
    private void publishAssistantCompleted(
        ProviderResponse response, int toolCallCount, Instant occurredAt) {
      lifecycleObservers.publish(
          new AssistantCompleted(
              thread.id(), thread.sessionId(), toolCallCount, response.stopReason(), occurredAt));
    }
  }

  /**
   * 将经 AgentTurnEngine 校验后的完整 Assistant 结果变为可恢复的 Session Entry。
   *
   * <p>流式 delta 只服务实时投影，重连后的权威内容来自这里。保留 text、thinking、ToolCall 的语义边界；即使 Provider 返回完全空的
   * Assistant，也显式写入空 Text，避免“Turn 已成功但没有 Assistant Entry”的歧义。
   */
  private static MessageEntryPayload assistantPayload(
      AgentAssistantMessage message, ProviderResponse response) {
    List<AgentMessageContent> contents = new ArrayList<>();
    if (!message.text().isEmpty()) {
      contents.add(new TextMessageContent(message.text()));
    }
    if (!message.thinking().isEmpty()) {
      contents.add(new ThinkingMessageContent(message.thinking()));
    }
    for (ToolCall toolCall : message.toolCalls()) {
      contents.add(
          new ToolCallMessageContent(toolCall.id(), toolCall.toolName(), toolCall.argumentsJson()));
    }
    if (contents.isEmpty()) {
      contents.add(new TextMessageContent(""));
    }
    return new MessageEntryPayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, contents),
        new AssistantMessageMetadata(response.stopReason(), response.usage(), response.cost()));
  }

  /**
   * Thread 与异步 Tool worker 的最小收敛端口。
   *
   * <p>Processor 只依赖三个问题：当前 head 是否仍有非终态 Tool、哪些 due Tool 应被派发、以及是否有全部终态但 尚未追加为 Entry 的 result。Tool
   * 的权限、外部副作用、lease 和实际执行均在该端口背后的持久化 worker 中处理。
   */
  public interface ThreadToolPort {
    List<ToolInvocation> listNonTerminal(long threadId, long assistantEntryId);

    int dispatchDue(long threadId, Instant now);

    boolean hasTerminalResultsPendingApply(long threadId, long headEntryId);
  }

  /**
   * 当前 JVM 的 Provider handle 与其 TurnHandler 的取消门。
   *
   * <p>handle 可能晚于 Stop 或 heartbeat 丢租才返回，因此 {@link #set(AgentTurnHandle)} 必须在保存 handle 后 再检查
   * {@code lostOwnership}。无论先后顺序如何，最终都会取消 handle，且 handler 已先禁止所有晚到 callback 持久化。
   */
  private final class ActiveProvider {
    private final TurnHandler handler;
    private final AtomicReference<AgentTurnHandle> handle = new AtomicReference<>();
    private final AtomicBoolean cancellationRequested = new AtomicBoolean();

    private ActiveProvider(TurnHandler handler) {
      this.handler = handler;
    }

    private void set(AgentTurnHandle value) {
      handle.set(value);
      if (handler.lostOwnership || cancellationRequested.get()) {
        // Stop、丢租或本地 timeout 可能发生在 Provider engine 返回 handle 之前。
        value.cancel();
      }
    }

    private void cancel() {
      // 先 fence 回调再请求底层取消；Provider 即使忽略或延迟取消，也无法用旧 token 提交结果。
      handler.markLostOwnership();
      cancelHandle();
    }

    /** 请求取消本地 handle，但保留当前 token 的失败写权限。 */
    private void cancelHandle() {
      cancellationRequested.set(true);
      AgentTurnHandle current = handle.get();
      if (current != null) {
        current.cancel();
      }
    }
  }
}
