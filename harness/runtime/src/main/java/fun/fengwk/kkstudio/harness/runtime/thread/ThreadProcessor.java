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
 * 事件触发的 Thread 处理器。
 *
 * <p>{@link #kick(long)} 在有界 executor 上调度一次 activation；同一 Thread 通过 DB {@code
 * processorToken}/{@code processorUntil} 跨节点单飞。主循环从 durable 事实恢复：先收敛 tool，普通边界先 harvest input
 * 再偿还一次模型 response debt；RETRYING 例外地先偿还失败 Turn，跨 Tool chain 保留 debt，直到最终 assistant 完成后才恢复 RUNNING。
 */
public final class ThreadProcessor implements ThreadKick, ThreadProviderCancellation {
  private static final System.Logger LOGGER = System.getLogger(ThreadProcessor.class.getName());
  private static final Duration REJECTED_RETRY_DELAY = Duration.ofMillis(50);

  private final ThreadStore threadStore;
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
  private final ConcurrentHashMap<Long, ActiveProvider> activeProviders = new ConcurrentHashMap<>();

  public ThreadProcessor(
      ThreadStore threadStore,
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

  @Override
  public void cancelLocalProvider(long threadId) {
    ActiveProvider active = activeProviders.get(threadId);
    if (active != null) {
      active.cancel();
    }
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
            return;
          }
          case LOST_OWNERSHIP -> {
            return;
          }
          case POLICY_REJECTED -> {
            return;
          }
          case FAILED -> {
            return;
          }
          case IDLE -> {
            ThreadTransactions.QuiescenceResult quiescence =
                transactions.quiesce(threadId, token, clock.instant());
            if (quiescence == ThreadTransactions.QuiescenceResult.IDLE) {
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
    COMPLETED,
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
        if (transactions.waitForExternal(threadId, token, "tools_or_permission", clock.instant())) {
          return LoopExit.WAITING_EXTERNAL;
        }
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
      // chain 保留该 debt，直到最终无-tool assistant 成功。
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
            if (!transactions.completeRetriedTurn(threadId, token, clock.instant())) {
              return LoopExit.LOST_OWNERSHIP;
            }
            continue;
          }
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

      // Safe boundary: harvest the complete cutoff before deciding whether one response is owed.
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
      if (headRequiresModel(thread)) {
        TurnOutcome outcome = executeModelTurn(thread, token);
        switch (outcome) {
          case CONTINUE, COMPLETED -> {
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
      return LoopExit.IDLE;
    }
    throw new IllegalStateException("thread processor safety limit exceeded for " + threadId);
  }

  private ToolProgress progressTools(AgentThread thread, String token, Instant now) {
    // 始终尝试派发 due tools，以便 WAITING_APPROVAL 旁的 QUEUED 兄弟可被推进。
    toolPort.dispatchDue(thread.id(), now);
    List<ToolInvocation> open = toolPort.listNonTerminal(thread.id(), thread.headEntryId());
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

  private static SessionEntryPayloadRole payloadRole(SessionEntry entry) {
    if (entry.payload() instanceof CompactionEntryPayload) {
      return SessionEntryPayloadRole.COMPACTION;
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
    USER_OR_TOOL,
    ASSISTANT,
    COMPACTION,
    OTHER
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
    ActiveProvider active = new ActiveProvider(handler);
    activeProviders.put(thread.id(), active);
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
                active.cancel();
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
      active.set(turnHandle);
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
      activeProviders.remove(thread.id(), active);
    }
    if (handler.lostOwnership) {
      return TurnOutcome.LOST_OWNERSHIP;
    }
    if (handler.failed) {
      return TurnOutcome.FAILED;
    }
    return handler.completedTurn ? TurnOutcome.COMPLETED : TurnOutcome.CONTINUE;
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
    private volatile boolean failed;
    private volatile boolean lostOwnership;
    private volatile boolean completedTurn;
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
            completedTurn = true;
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
    List<ToolInvocation> listNonTerminal(long threadId, long assistantEntryId);

    int dispatchDue(long threadId, Instant now);

    boolean hasTerminalResultsPendingApply(long threadId, long headEntryId);
  }

  private final class ActiveProvider {
    private final TurnHandler handler;
    private final AtomicReference<AgentTurnHandle> handle = new AtomicReference<>();

    private ActiveProvider(TurnHandler handler) {
      this.handler = handler;
    }

    private void set(AgentTurnHandle value) {
      handle.set(value);
      if (handler.lostOwnership) {
        value.cancel();
      }
    }

    private void cancel() {
      handler.markLostOwnership();
      AgentTurnHandle current = handle.get();
      if (current != null) {
        current.cancel();
      }
    }
  }
}
