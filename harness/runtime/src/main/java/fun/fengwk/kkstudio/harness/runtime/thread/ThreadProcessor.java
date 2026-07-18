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
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
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
 * 事件触发的 Thread 处理器。
 *
 * <p>{@link #kick(long)} 在有界 executor 上调度一次 activation；同一 Thread 通过 DB {@code
 * processorToken}/{@code processorUntil} 跨节点单飞。主循环从 durable 事实恢复：先收敛 tool，再在 turn 边界先偿还模型再应用
 * input，直到 final assistant 且 queue 为空后释放 token。
 */
public final class ThreadProcessor implements ThreadKick {
  private static final System.Logger LOGGER = System.getLogger(ThreadProcessor.class.getName());
  private static final Duration REJECTED_RETRY_DELAY = Duration.ofMillis(50);

  private final ThreadStore threadStore;
  private final ThreadInputStore inputStore;
  private final ThreadTransactions transactions;
  private final SessionEntryStore entryStore;
  private final ThreadToolPort toolPort;
  private final SessionContextBuilder contextBuilder;
  private final ProviderMessageProjector messageProjector;
  private final TurnResourceResolver resourceResolver;
  private final CompactionService compactionService;
  private final ThreadProcessorConfig config;
  private final Clock clock;
  private final DeltaFlushScheduler deltaFlushScheduler;
  private final ProviderRequestInterceptorChain providerRequestInterceptors;
  private final HarnessLifecycleObservers lifecycleObservers;
  private final ThreadIdGenerator idGenerator;
  private final Executor executor;
  private final ScheduledExecutorService workerScheduler;
  private final ConcurrentHashMap<Long, Boolean> inflight = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<Long, AtomicInteger> pendingKicks = new ConcurrentHashMap<>();

  public ThreadProcessor(
      ThreadStore threadStore,
      ThreadInputStore inputStore,
      ThreadTransactions transactions,
      SessionEntryStore entryStore,
      ThreadToolPort toolPort,
      SessionContextBuilder contextBuilder,
      ProviderMessageProjector messageProjector,
      TurnResourceResolver resourceResolver,
      CompactionService compactionService,
      ThreadProcessorConfig config,
      Clock clock,
      DeltaFlushScheduler deltaFlushScheduler,
      ProviderRequestInterceptorChain providerRequestInterceptors,
      HarnessLifecycleObservers lifecycleObservers,
      ThreadIdGenerator idGenerator,
      Executor executor,
      ScheduledExecutorService workerScheduler) {
    this.threadStore = Objects.requireNonNull(threadStore, "threadStore");
    this.inputStore = Objects.requireNonNull(inputStore, "inputStore");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.entryStore = Objects.requireNonNull(entryStore, "entryStore");
    this.toolPort = Objects.requireNonNull(toolPort, "toolPort");
    this.contextBuilder = Objects.requireNonNull(contextBuilder, "contextBuilder");
    this.messageProjector = Objects.requireNonNull(messageProjector, "messageProjector");
    this.resourceResolver = Objects.requireNonNull(resourceResolver, "resourceResolver");
    this.compactionService = Objects.requireNonNull(compactionService, "compactionService");
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
    pendingKicks.compute(
        threadId,
        (id, counter) -> {
          AtomicInteger next = counter == null ? new AtomicInteger() : counter;
          next.incrementAndGet();
          return next;
        });
    schedule(threadId);
  }

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
              // 清零后移除空 entry，但若并发 kick 已再次递增则保留并重调度。
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
      // 保留 pending kick；在 worker scheduler 上异步重试，绝不在调用线程执行模型。
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

  /** 同步处理（测试用）；生产路径走 {@link #kick(long)}。 */
  public void process(long threadId) {
    Instant now = clock.instant();
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
            if (transactions.releaseForExternalWait(threadId, token, clock.instant())) {
              return;
            }
            // 终态 tool results 已适用或外部等待消失：继续持有 token。
          }
          case LOST_OWNERSHIP -> {
            return;
          }
          case POLICY_REJECTED -> {
            // Admission rejection is terminal policy failure: release even with pending inputs so
            // the same rejection cannot spin/retain across unapplied queue items. Provider/setup
            // failures keep releaseIfIdle retry-on-pending-input semantics.
            threadStore.release(threadId, token, clock.instant());
            return;
          }
          case FAILED -> {
            // 失败期间若已有 pending input 入队：原子 retain token 继续，避免跨节点 lost-wakeup。
            if (transactions.releaseIfIdle(threadId, token, clock.instant())) {
              return;
            }
          }
          case IDLE -> {
            if (transactions.releaseIfIdle(threadId, token, clock.instant())) {
              return;
            }
            // releaseIfIdle=false：出现新的 durable work，继续持有 token 处理。
          }
        }
      }
    } catch (RuntimeException error) {
      threadStore.release(threadId, token, clock.instant());
      throw error;
    }
  }

  private enum LoopExit {
    IDLE,
    WAITING_EXTERNAL,
    LOST_OWNERSHIP,
    FAILED,
    /** Policy admission rejection: force-release token; do not treat as provider retry. */
    POLICY_REJECTED
  }

  private enum TurnOutcome {
    CONTINUE,
    WAITING_EXTERNAL,
    LOST_OWNERSHIP,
    FAILED,
    POLICY_REJECTED
  }

  private LoopExit runLoop(long threadId, String token) {
    int safety = 0;
    while (safety++ < 10_000) {
      Instant now = clock.instant();
      if (!threadStore.renew(threadId, token, now, config.processorLease())) {
        return LoopExit.LOST_OWNERSHIP;
      }
      AgentThread thread =
          threadStore
              .find(threadId)
              .orElseThrow(() -> new IllegalStateException("thread disappeared: " + threadId));
      if (!token.equals(thread.processorToken())) {
        return LoopExit.LOST_OWNERSHIP;
      }

      ToolProgress toolProgress = progressTools(thread, token, now);
      if (toolProgress == ToolProgress.WAITING) {
        transactions.appendEvents(
            threadId,
            token,
            List.of(
                new ThreadEventDraft(
                    ThreadEventType.THREAD_WAITING,
                    ThreadEventPayloads.of("reason", "tools_or_permission"))),
            clock.instant());
        return LoopExit.WAITING_EXTERNAL;
      }
      if (toolProgress == ToolProgress.PROGRESSED) {
        continue;
      }
      if (toolProgress == ToolProgress.LOST_OWNERSHIP) {
        return LoopExit.LOST_OWNERSHIP;
      }

      // Turn boundary: USER/TOOL/compaction head 先偿还模型，再应用后续 input。
      thread =
          threadStore
              .find(threadId)
              .orElseThrow(() -> new IllegalStateException("thread disappeared: " + threadId));
      if (headRequiresModel(thread)) {
        TurnOutcome outcome = executeModelTurn(thread, token);
        switch (outcome) {
          case CONTINUE -> {
            continue;
          }
          case WAITING_EXTERNAL -> {
            return LoopExit.WAITING_EXTERNAL;
          }
          case LOST_OWNERSHIP -> {
            return LoopExit.LOST_OWNERSHIP;
          }
          case POLICY_REJECTED -> {
            return LoopExit.POLICY_REJECTED;
          }
          case FAILED -> {
            return LoopExit.FAILED;
          }
        }
      }

      ThreadTransactions.ApplyInputResult applied =
          transactions.applyNextInput(threadId, token, clock.instant());
      if (applied.applied()) {
        continue;
      }

      if (inputStore.findNextPending(threadId).isEmpty()) {
        thread =
            threadStore
                .find(threadId)
                .orElseThrow(() -> new IllegalStateException("thread disappeared: " + threadId));
        if (headRequiresModel(thread)) {
          continue;
        }
        Instant idleAt = clock.instant();
        if (!transactions.appendEvents(
            threadId,
            token,
            List.of(
                new ThreadEventDraft(
                    ThreadEventType.THREAD_IDLE, ThreadEventPayloads.of("reason", "queue_empty"))),
            idleAt)) {
          return LoopExit.LOST_OWNERSHIP;
        }
        lifecycleObservers.publish(new ThreadIdle(thread.id(), thread.sessionId(), idleAt));
        return LoopExit.IDLE;
      }
    }
    throw new IllegalStateException("thread processor safety limit exceeded for " + threadId);
  }

  private ToolProgress progressTools(AgentThread thread, String token, Instant now) {
    // 始终尝试派发 due tools，以便 WAITING_APPROVAL 旁的 QUEUED 兄弟可被推进。
    toolPort.dispatchDue(thread.id(), now);
    List<ToolInvocation> open = toolPort.listNonTerminal(thread.id());
    if (!open.isEmpty()) {
      // 任意非终态（含 WAITING_APPROVAL / CANCEL_REQUESTED）都进入外部等待。
      return ToolProgress.WAITING;
    }
    if (toolPort.hasTerminalResultsPendingApply(thread.id(), thread.headEntryId())) {
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

  private boolean headRequiresModel(AgentThread thread) {
    SessionEntry head =
        entryStore
            .find(thread.sessionId(), thread.headEntryId())
            .orElseThrow(
                () -> new IllegalStateException("missing head entry: " + thread.headEntryId()));
    if (head.payload() instanceof CompactionEntryPayload) {
      // 成功 compaction 后必须用压缩上下文重试模型，不可 idle。
      return true;
    }
    if (!(head.payload() instanceof MessageEntryPayload message)) {
      return false;
    }
    AgentMessageRole role = message.message().role();
    return role == AgentMessageRole.USER || role == AgentMessageRole.TOOL;
  }

  private TurnOutcome executeModelTurn(AgentThread thread, String token) {
    Instant turnStartedAt = clock.instant();
    // Atomic admit + TURN_STARTED (or policy failure events). No Provider call unless ADMITTED.
    // Cancellation / maxTurns bind only at this boundary; already-admitted in-flight work is not
    // interrupted.
    ThreadTransactions.BeginTurnResult begin =
        transactions.beginTurn(thread.id(), token, turnStartedAt);
    if (begin.isLostOwnership()) {
      return TurnOutcome.LOST_OWNERSHIP;
    }
    if (begin.isRejected()) {
      return TurnOutcome.POLICY_REJECTED;
    }
    lifecycleObservers.publish(new TurnStarted(thread.id(), thread.sessionId(), turnStartedAt));

    SessionContext context;
    TurnResources resources;
    try {
      context = contextBuilder.build(thread.sessionId(), thread.headEntryId());
      resources = resourceResolver.resolve(thread.sessionId(), thread.id(), context.config());
    } catch (RuntimeException error) {
      if (!failTurn(thread, token, error)) {
        return TurnOutcome.LOST_OWNERSHIP;
      }
      return TurnOutcome.FAILED;
    }

    long plannedAssistantEntryId = idGenerator.newSessionEntryId();
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
    ProviderRequestInterceptorChain chain =
        providerRequestInterceptors.andThen(new PromptCacheRequestFinalizer(thread.sessionId()));
    AgentTurnEngine engine = new DefaultAgentTurnEngine(resources.provider(), chain);

    CompletableFuture<Void> done = new CompletableFuture<>();
    handler.completion = done;
    AtomicReference<AgentTurnHandle> handleRef = new AtomicReference<>();
    // heartbeat 必须严格小于 lease/2，使用 lease/3。
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
                handler.markLostOwnership();
                AgentTurnHandle handle = handleRef.get();
                if (handle != null) {
                  handle.cancel();
                }
              }
            },
            renewEvery.toMillis(),
            renewEvery.toMillis(),
            TimeUnit.MILLISECONDS);
    try {
      AgentTurnHandle turnHandle =
          engine.execute(
              new AgentTurnRequest(
                  resources.model(),
                  resources.variant(),
                  messageProjector.project(context.messages()),
                  resources.toolDescriptors()),
              handler);
      handleRef.set(turnHandle);
      if (handler.lostOwnership) {
        turnHandle.cancel();
        return TurnOutcome.LOST_OWNERSHIP;
      }
      done.orTimeout(config.modelCallTimeout().toMillis(), TimeUnit.MILLISECONDS)
          .exceptionally(
              error -> {
                if (!handler.lostOwnership) {
                  turnHandle.cancel();
                  handler.onFailed(
                      new ProviderException(
                          ProviderErrorKind.TRANSIENT, "model call timed out", error));
                }
                return null;
              })
          .join();
    } catch (RuntimeException error) {
      if (!handler.lostOwnership) {
        handler.onFailed(
            new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "cannot start agent turn", error));
      }
    } finally {
      renewFuture.cancel(false);
    }
    if (handler.lostOwnership) {
      return TurnOutcome.LOST_OWNERSHIP;
    }
    if (handler.failed) {
      return TurnOutcome.FAILED;
    }
    if (handler.waitingExternal) {
      return TurnOutcome.WAITING_EXTERNAL;
    }
    return TurnOutcome.CONTINUE;
  }

  private boolean failTurn(AgentThread thread, String token, RuntimeException error) {
    Instant now = clock.instant();
    return transactions.fail(
        thread.id(),
        token,
        List.of(
            new ThreadEventDraft(
                ThreadEventType.ASSISTANT_FAILED,
                ThreadEventPayloads.of(
                    "kind", ProviderErrorKind.INVALID_REQUEST.name(), "message", message(error))),
            new ThreadEventDraft(
                ThreadEventType.THREAD_FAILED,
                ThreadEventPayloads.of("reason", "turn_setup_failed", "message", message(error)))),
        now);
  }

  private static String message(Throwable error) {
    return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
  }

  private enum ToolProgress {
    NONE,
    PROGRESSED,
    WAITING,
    LOST_OWNERSHIP
  }

  private final class TurnHandler implements AgentTurnEventHandler {
    private final AgentThread thread;
    private final String token;
    private final long plannedAssistantEntryId;
    private final SessionContext context;
    private final TurnResources resources;
    private final DeltaBatcher batcher;
    private final AtomicBoolean terminal = new AtomicBoolean();
    private volatile boolean waitingExternal;
    private volatile boolean failed;
    private volatile boolean lostOwnership;
    private volatile CompletableFuture<Void> completion = new CompletableFuture<>();

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
    }

    /** 丢租：禁止后续用 stale token 写终态；完成本地 wait。 */
    void markLostOwnership() {
      lostOwnership = true;
      terminal.set(true);
      completion.complete(null);
    }

    @Override
    public void onStarted() {
      if (lostOwnership) {
        return;
      }
      try {
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
        batcher.add(event);
      }
    }

    @Override
    public void onCompleted(AgentTurnResult result) {
      if (lostOwnership || !terminal.compareAndSet(false, true)) {
        return;
      }
      try {
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
            publishAssistantCompleted(response, toolCalls.size(), now);
          } catch (ConcurrentModificationException concurrency) {
            markLostOwnership();
          }
          return;
        }
        try {
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
              List.of(assistantCompleted),
              now)) {
            markLostOwnership();
            return;
          }
          publishAssistantCompleted(response, toolCalls.size(), now);
          toolPort.dispatchDue(thread.id(), now);
          waitingExternal = !toolPort.listNonTerminal(thread.id()).isEmpty();
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
      if (lostOwnership || !terminal.compareAndSet(false, true)) {
        return;
      }
      try {
        batcher.flush();
        switch (error.kind()) {
          case OVERFLOW -> overflowFailure(error);
          case TRANSIENT, CANCELLED, AUTHENTICATION, BILLING, INVALID_REQUEST -> permanentFailure(
              error);
        }
      } finally {
        completion.complete(null);
      }
    }

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

    private void permanentFailure(ProviderException error) {
      Instant now = clock.instant();
      try {
        if (!transactions.fail(
            thread.id(),
            token,
            List.of(
                new ThreadEventDraft(
                    ThreadEventType.ASSISTANT_FAILED,
                    plannedAssistantEntryId,
                    ThreadEventPayloads.of("kind", error.kind().name(), "message", message(error))),
                new ThreadEventDraft(
                    ThreadEventType.THREAD_FAILED,
                    ThreadEventPayloads.of(
                        "kind", error.kind().name(), "message", message(error)))),
            now)) {
          markLostOwnership();
          return;
        }
        failed = true;
      } catch (ConcurrentModificationException concurrency) {
        markLostOwnership();
      }
    }

    private void failToolPreparation(RuntimeException error, Instant now) {
      try {
        if (!transactions.fail(
            thread.id(),
            token,
            List.of(
                new ThreadEventDraft(
                    ThreadEventType.ASSISTANT_FAILED,
                    plannedAssistantEntryId,
                    ThreadEventPayloads.of(
                        "kind",
                        ProviderErrorKind.INVALID_REQUEST.name(),
                        "message",
                        message(error))),
                new ThreadEventDraft(
                    ThreadEventType.THREAD_FAILED,
                    ThreadEventPayloads.of(
                        "reason", "tool_preparation_failed", "message", message(error)))),
            now)) {
          markLostOwnership();
          return;
        }
        failed = true;
      } catch (ConcurrentModificationException concurrency) {
        markLostOwnership();
      }
    }

    private void publishAssistantCompleted(
        ProviderResponse response, int toolCallCount, Instant occurredAt) {
      lifecycleObservers.publish(
          new AssistantCompleted(
              thread.id(), thread.sessionId(), toolCallCount, response.stopReason(), occurredAt));
    }
  }

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

  /** Thread 工具收敛端口：查询/派发/判断是否待 apply tool results。 */
  public interface ThreadToolPort {
    List<ToolInvocation> listNonTerminal(long threadId);

    int dispatchDue(long threadId, Instant now);

    boolean hasTerminalResultsPendingApply(long threadId, long headEntryId);
  }
}
