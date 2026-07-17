package fun.fengwk.kkstudio.harness.runtime.run;

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
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.RunTerminated;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservation.TurnStarted;
import fun.fengwk.kkstudio.harness.runtime.extension.HarnessLifecycleObservers;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInterceptorException;
import fun.fengwk.kkstudio.harness.runtime.usage.ModelUsageDraft;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** 数据库 claim 驱动的单 Turn Worker。实例内不保存调度器或可恢复事实，只让当前 Provider stream 及其 Delta buffer 存活到本次回调终态。 */
public final class AgentTurnWorker {
  private static final System.Logger LOGGER = System.getLogger(AgentTurnWorker.class.getName());
  private static final AgentTurnHandle TERMINAL_HANDLE =
      new AgentTurnHandle() {
        @Override
        public void cancel() {}

        @Override
        public boolean isCancelled() {
          return true;
        }

        @Override
        public boolean isDone() {
          return true;
        }
      };

  private final RunStore runStore;
  private final RunEventStore eventStore;
  private final RunTransactions transactions;
  private final ToolPreparationPort toolPreparationPort;
  private final SessionContextBuilder contextBuilder;
  private final ProviderMessageProjector messageProjector;
  private final TurnResourceResolver resourceResolver;
  private final CompactionService compactionService;
  private final RunWorkerConfig config;
  private final Clock clock;
  private final DeltaFlushScheduler deltaFlushScheduler;
  private final ProviderRequestInterceptorChain providerRequestInterceptors;
  private final HarnessLifecycleObservers lifecycleObservers;

  public AgentTurnWorker(
      RunStore runStore,
      RunEventStore eventStore,
      RunTransactions transactions,
      ToolPreparationPort toolPreparationPort,
      SessionContextBuilder contextBuilder,
      ProviderMessageProjector messageProjector,
      TurnResourceResolver resourceResolver,
      CompactionService compactionService,
      RunWorkerConfig config,
      Clock clock,
      DeltaFlushScheduler deltaFlushScheduler) {
    this(
        runStore,
        eventStore,
        transactions,
        toolPreparationPort,
        contextBuilder,
        messageProjector,
        resourceResolver,
        compactionService,
        config,
        clock,
        deltaFlushScheduler,
        new ProviderRequestInterceptorChain(List.of()),
        new HarnessLifecycleObservers(List.of()));
  }

  public AgentTurnWorker(
      RunStore runStore,
      RunEventStore eventStore,
      RunTransactions transactions,
      ToolPreparationPort toolPreparationPort,
      SessionContextBuilder contextBuilder,
      ProviderMessageProjector messageProjector,
      TurnResourceResolver resourceResolver,
      CompactionService compactionService,
      RunWorkerConfig config,
      Clock clock,
      DeltaFlushScheduler deltaFlushScheduler,
      ProviderRequestInterceptorChain providerRequestInterceptors,
      HarnessLifecycleObservers lifecycleObservers) {
    this.runStore = Objects.requireNonNull(runStore, "runStore");
    this.eventStore = Objects.requireNonNull(eventStore, "eventStore");
    this.transactions = Objects.requireNonNull(transactions, "transactions");
    this.toolPreparationPort = Objects.requireNonNull(toolPreparationPort, "toolPreparationPort");
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
  }

  /** claim 至多一个 due Run，并为它启动恰好一个 Turn。 */
  public Optional<ClaimedTurn> executeNext(String workerId) {
    if (workerId == null || workerId.isBlank()) {
      throw new IllegalArgumentException("workerId must not be blank");
    }
    Instant claimedAt = clock.instant();
    Optional<AgentRun> claimed = runStore.claimDue(workerId, claimedAt, config.leaseDuration());
    if (claimed.isEmpty()) {
      return Optional.empty();
    }
    AgentRun run = claimed.orElseThrow();
    if (run.turnIndex() == 0 && run.attempt() == 1) {
      attemptEvent(run, RunEventType.RUN_STARTED);
    }
    Instant turnStartedAt = clock.instant();
    attemptEventAt(run, RunEventType.TURN_STARTED, turnStartedAt);
    lifecycleObservers.publish(
        new TurnStarted(run.id(), run.sessionId(), run.attempt(), run.turnIndex(), turnStartedAt));
    if (run.cancelRequestedAt() != null) {
      cancelBeforeStart(run);
      return Optional.of(new ClaimedTurn(run, TERMINAL_HANDLE));
    }

    // consumeSteering in the same turn boundary, before context build / provider
    if (!transactions.consumeSteering(run, claimedAt)) {
      // consumeSteering 可能因 cancelRequestedAt 在事务内 cancelOwned（返 false），也可能因
      // ownership 已丢失（lease/attempt 不匹配）。reload 真实 status，仅实际 terminal 时发布。
      observeCancelWinsTerminal(run, claimedAt);
      return Optional.of(new ClaimedTurn(run, TERMINAL_HANDLE));
    }

    SessionContext context;
    TurnResources resources;
    try {
      context = contextBuilder.build(run.sessionId());
      resources = resourceResolver.resolve(run.sessionId(), context.config());
    } catch (RuntimeException error) {
      failBeforeStream(run, error);
      return Optional.of(new ClaimedTurn(run, TERMINAL_HANDLE));
    }

    DeltaBatcher batcher =
        new DeltaBatcher(
            run.id(),
            run.attempt(),
            run.turnIndex(),
            eventStore,
            clock,
            deltaFlushScheduler,
            config.deltaFlushInterval(),
            config.deltaBatchBytes(),
            claimedAt);
    TurnHandler handler = new TurnHandler(run, context, resources, batcher);
    // 唯一可信 cache control 派生点固定在所有 Extension Host hooks 之后；sessionId 仅用于
    // affinity 派生，不会被写入 ProviderRequest，也不作为 Spring 单例存在。
    ProviderRequestInterceptorChain perRunChain =
        providerRequestInterceptors.andThen(new PromptCacheRequestFinalizer(run.sessionId()));
    AgentTurnEngine engine = new DefaultAgentTurnEngine(resources.provider(), perRunChain);
    AgentTurnHandle handle;
    try {
      handle =
          engine.execute(
              new AgentTurnRequest(
                  resources.model(),
                  resources.variant(),
                  messageProjector.project(context.messages()),
                  resources.toolDescriptors()),
              handler);
    } catch (RuntimeException error) {
      handler.onFailed(
          new ProviderException(
              ProviderErrorKind.INVALID_REQUEST, "cannot start agent turn", error));
      handle = TERMINAL_HANDLE;
    }
    return Optional.of(new ClaimedTurn(run, handle));
  }

  /** 由进程级 worker cadence 调用；本类不持有 timer。 */
  public boolean heartbeat(ClaimedTurn turn) {
    AgentRun run = turn.run();
    if (!runStore.heartbeat(
        run.id(), run.leaseOwner(), run.attempt(), clock.instant(), config.leaseDuration())) {
      turn.handle().cancel();
      return false;
    }
    return true;
  }

  private void cancelBeforeStart(AgentRun run) {
    Instant now = clock.instant();
    terminate(
        run,
        RunStatus.CANCELLED,
        List.of(
            attemptDraft(
                run, RunEventType.RUN_CANCELLED, "cancelRequestedAt", run.cancelRequestedAt())),
        now);
  }

  private void failBeforeStream(AgentRun run, RuntimeException error) {
    Instant now = clock.instant();
    terminate(
        run,
        RunStatus.FAILED,
        List.of(
            attemptDraft(
                run,
                RunEventType.ASSISTANT_FAILED,
                "kind",
                ProviderErrorKind.INVALID_REQUEST.name(),
                "message",
                message(error)),
            attemptDraft(
                run,
                RunEventType.RUN_FAILED,
                "reason",
                "turn_setup_failed",
                "message",
                message(error))),
        now);
  }

  private void event(AgentRun run, RunEventType type, String payload) {
    eventStore.append(run.id(), type, payload, clock.instant());
  }

  private void attemptEvent(AgentRun run, RunEventType type, Object... fields) {
    event(run, type, RunEventPayloads.forAttempt(run, fields));
  }

  private void attemptEventAt(AgentRun run, RunEventType type, Instant now, Object... fields) {
    eventStore.append(run.id(), type, RunEventPayloads.forAttempt(run, fields), now);
  }

  private void terminate(
      AgentRun run, RunStatus terminalStatus, List<RunEventDraft> terminalEvents, Instant now) {
    if (!transactions.terminate(run, terminalStatus, terminalEvents, now)) {
      return;
    }
    publishTerminalIfDurable(run, now);
  }

  /**
   * 用于 consumeSteering 返 false / requeue 返 true 这类 Turn 边界：reload 真实 status，仅当 actual
   * terminal（CANCELLED 即 cancel-wins）才发布 RunTerminated；非terminal（QUEUED 是正常 requeue， RUNNING 是
   * ownership 丢失）静默跳过，不打误导 WARNING。 reload 缺失/异常已由 reloadRun 内部 WARNING。
   */
  private void observeCancelWinsTerminal(AgentRun run, Instant now) {
    Optional<AgentRun> reloaded = reloadRun(run.id());
    if (reloaded.isEmpty()) {
      return;
    }
    AgentRun current = reloaded.orElseThrow();
    RunStatus status = current.status();
    if (status.terminal()) {
      publishRunTerminated(current, status, now);
    }
  }

  /** 重载 Run 以 DB 为唯一事实源决定是否发布 terminal observation。 reload 缺失/异常/非终态仅 WARNING 跳过， 不能抛错影响已持久状态。 */
  private void publishTerminalIfDurable(AgentRun run, Instant now) {
    Optional<AgentRun> reloaded = reloadRun(run.id());
    if (reloaded.isEmpty()) {
      return;
    }
    AgentRun terminalRun = reloaded.orElseThrow();
    RunStatus status = terminalRun.status();
    if (!status.terminal()) {
      LOGGER.log(
          Level.WARNING,
          "Run terminal observation skipped because reload returned nonterminal status: " + status);
      return;
    }
    publishRunTerminated(terminalRun, status, now);
  }

  /**
   * complete CAS 成功后根据 reload 实际 status 决定 Assistant/Run 组合。 cancel-wins 实际 CANCELLED 时只发
   * RunTerminated；control requeue QUEUED 只发 Assistant；自然 SUCCEEDED 按 Assistant -> Run 顺序发。
   */
  private void publishAssistantObservation(
      AgentRun run, ProviderResponse response, int toolCallCount, Instant now) {
    Optional<AgentRun> reloaded = reloadRun(run.id());
    if (reloaded.isEmpty()) {
      return;
    }
    AgentRun current = reloaded.orElseThrow();
    switch (current.status()) {
      case SUCCEEDED -> {
        publishAssistantCompleted(run, response, toolCallCount, now);
        publishRunTerminated(current, RunStatus.SUCCEEDED, now);
      }
      case QUEUED -> publishAssistantCompleted(run, response, toolCallCount, now);
      case CANCELLED -> publishRunTerminated(current, RunStatus.CANCELLED, now);
      default -> LOGGER.log(
          Level.WARNING,
          "Assistant completion observation skipped because reload returned unexpected"
              + " status: "
              + current.status());
    }
  }

  /** prepare CAS 成功后 reload 实际 status：WAITING_TOOLS 仅 Assistant；CANCELLED 仅 Run；其他仅 WARNING 跳过。 */
  private void publishPreparationObservation(
      AgentRun run, ProviderResponse response, int toolCallCount, Instant now) {
    Optional<AgentRun> reloaded = reloadRun(run.id());
    if (reloaded.isEmpty()) {
      return;
    }
    AgentRun current = reloaded.orElseThrow();
    switch (current.status()) {
      case WAITING_TOOLS -> publishAssistantCompleted(run, response, toolCallCount, now);
      case CANCELLED -> publishRunTerminated(current, RunStatus.CANCELLED, now);
      default -> LOGGER.log(
          Level.WARNING,
          "Preparation observation skipped because reload returned unexpected status: "
              + current.status());
    }
  }

  /**
   * compactAndRequeue CAS 成功后 reload 实际 status：QUEUED 仅 Compaction；CANCELLED 仅 Run；其他 WARNING 跳过。
   */
  private void publishCompactionObservation(
      AgentRun run, CompactionEntryPayload completedCompaction, Instant now) {
    Optional<AgentRun> reloaded = reloadRun(run.id());
    if (reloaded.isEmpty()) {
      return;
    }
    AgentRun current = reloaded.orElseThrow();
    switch (current.status()) {
      case QUEUED -> lifecycleObservers.publish(
          new CompactionCompleted(
              run.id(), run.sessionId(), completedCompaction.firstKeptEntryId(), now));
      case CANCELLED -> publishRunTerminated(current, RunStatus.CANCELLED, now);
      default -> LOGGER.log(
          Level.WARNING,
          "Compaction observation skipped because reload returned unexpected status: "
              + current.status());
    }
  }

  /** DB 是 Run 唯一事实源；reload 失败或缺失仅 WARNING，跳过本次 observation 不能影响已持久状态。 */
  private Optional<AgentRun> reloadRun(long runId) {
    try {
      Optional<AgentRun> reloaded = runStore.find(runId);
      if (reloaded.isEmpty()) {
        LOGGER.log(Level.WARNING, "Run reload skipped because find returned empty for " + runId);
      }
      return reloaded;
    } catch (RuntimeException error) {
      LOGGER.log(Level.WARNING, "Run reload skipped because find failed for " + runId, error);
      return Optional.empty();
    }
  }

  private void publishAssistantCompleted(
      AgentRun run, ProviderResponse response, int toolCallCount, Instant now) {
    lifecycleObservers.publish(
        new AssistantCompleted(
            run.id(), run.sessionId(), toolCallCount, response.stopReason(), now));
  }

  private void publishRunTerminated(AgentRun run, RunStatus status, Instant now) {
    lifecycleObservers.publish(new RunTerminated(run.id(), run.sessionId(), status, now));
  }

  private RunEventDraft attemptDraft(AgentRun run, RunEventType type, Object... fields) {
    return new RunEventDraft(type, RunEventPayloads.forAttempt(run, fields));
  }

  private static String message(Throwable error) {
    return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
  }

  public record ClaimedTurn(AgentRun run, AgentTurnHandle handle) {
    public ClaimedTurn {
      run = Objects.requireNonNull(run, "run");
      handle = Objects.requireNonNull(handle, "handle");
    }
  }

  private final class TurnHandler implements AgentTurnEventHandler {
    private final AgentRun run;
    private final SessionContext context;
    private final TurnResources resources;
    private final DeltaBatcher batcher;
    private final AtomicBoolean terminal = new AtomicBoolean();

    private TurnHandler(
        AgentRun run, SessionContext context, TurnResources resources, DeltaBatcher batcher) {
      this.run = run;
      this.context = context;
      this.resources = resources;
      this.batcher = batcher;
    }

    @Override
    public void onStarted() {
      attemptEvent(run, RunEventType.ASSISTANT_STARTED);
    }

    @Override
    public void onDelta(ProviderStreamEvent event) {
      if (!terminal.get()) {
        batcher.add(event);
      }
    }

    @Override
    public void onCompleted(AgentTurnResult result) {
      if (!terminal.compareAndSet(false, true)) {
        return;
      }
      batcher.flush();
      ProviderResponse response = result.providerResponse();
      ModelUsageDraft usageDraft;
      try {
        usageDraft = ModelUsageDraft.from(result.providerRequest(), response);
      } catch (RuntimeException error) {
        permanentFailure(
            new ProviderException(
                ProviderErrorKind.INVALID_REQUEST, "invalid model usage draft", error),
            attemptDraft(
                run,
                RunEventType.ASSISTANT_FAILED,
                "kind",
                ProviderErrorKind.INVALID_REQUEST.name(),
                "message",
                message(error)));
        return;
      }
      MessageEntryPayload assistant = assistantPayload(result.assistantMessage(), response);
      List<ToolCall> toolCalls = result.toolCalls();
      Instant now = clock.instant();
      RunEventDraft assistantCompleted =
          attemptDraft(
              run,
              RunEventType.ASSISTANT_COMPLETED,
              "toolCallCount",
              toolCalls.size(),
              "stopReason",
              response.stopReason().name(),
              "usage",
              response.usage(),
              "cost",
              response.cost());
      if (toolCalls.isEmpty()) {
        if (transactions.complete(run, assistant, usageDraft, assistantCompleted, now)) {
          publishAssistantObservation(run, response, toolCalls.size(), now);
        }
        return;
      }
      try {
        if (toolPreparationPort.prepare(
            run,
            assistant,
            usageDraft,
            toolCalls,
            resources.toolBindings(),
            resources.workdir(),
            resources.environmentRoot(),
            List.of(assistantCompleted),
            now)) {
          publishPreparationObservation(run, response, toolCalls.size(), now);
        }
      } catch (IllegalArgumentException | ToolInterceptorException error) {
        failToolPreparation(error, now);
      }
    }

    private void failToolPreparation(RuntimeException error, Instant now) {
      terminate(
          run,
          RunStatus.FAILED,
          List.of(
              attemptDraft(
                  run,
                  RunEventType.ASSISTANT_FAILED,
                  "kind",
                  ProviderErrorKind.INVALID_REQUEST.name(),
                  "message",
                  message(error)),
              attemptDraft(
                  run,
                  RunEventType.RUN_FAILED,
                  "reason",
                  "tool_preparation_failed",
                  "message",
                  message(error))),
          now);
    }

    @Override
    public void onFailed(ProviderException error) {
      if (!terminal.compareAndSet(false, true)) {
        return;
      }
      batcher.flush();
      RunEventDraft assistantFailed =
          attemptDraft(
              run,
              RunEventType.ASSISTANT_FAILED,
              "kind",
              error.kind().name(),
              "message",
              message(error));
      switch (error.kind()) {
        case TRANSIENT -> transientFailure(error, assistantFailed);
        case OVERFLOW -> overflowFailure(assistantFailed);
        case CANCELLED -> cancelledFailure(assistantFailed);
        case AUTHENTICATION, BILLING, INVALID_REQUEST -> permanentFailure(error, assistantFailed);
      }
    }

    private void transientFailure(ProviderException error, RunEventDraft assistantFailed) {
      Instant now = clock.instant();
      if (run.attempt() >= config.maxAttempts()) {
        permanentFailure(error, assistantFailed);
        return;
      }
      Instant nextAttemptAt = now.plus(config.backoffForAttempt(run.attempt()));
      if (transactions.requeue(
          run,
          nextAttemptAt,
          List.of(
              assistantFailed,
              attemptDraft(run, RunEventType.RETRY_SCHEDULED, "nextAttemptAt", nextAttemptAt)),
          now)) {
        // requeue 返 true 可能 cancel-wins（事务内 cancelOwned 变 CANCELLED），正常则 QUEUED。
        // reload 真实 status 仅当 terminal 才发布，不发 Assistant 或 FAILED。
        observeCancelWinsTerminal(run, now);
      }
    }

    private void overflowFailure(RunEventDraft assistantFailed) {
      Instant now = clock.instant();
      if (run.attempt() >= config.maxAttempts()) {
        terminate(
            run,
            RunStatus.FAILED,
            List.of(
                assistantFailed,
                attemptDraft(
                    run, RunEventType.RUN_FAILED, "reason", "compaction_attempts_exhausted")),
            now);
        return;
      }
      RunEventDraft compactionStarted = attemptDraft(run, RunEventType.COMPACTION_STARTED);
      Optional<CompactionEntryPayload> compaction;
      try {
        compaction = compactionService.compact(run.sessionId(), context);
      } catch (RuntimeException error) {
        terminate(
            run,
            RunStatus.FAILED,
            List.of(
                assistantFailed,
                compactionStarted,
                attemptDraft(
                    run,
                    RunEventType.RUN_FAILED,
                    "reason",
                    "compaction_failed",
                    "message",
                    message(error))),
            now);
        return;
      }
      if (compaction.isEmpty()) {
        terminate(
            run,
            RunStatus.FAILED,
            List.of(
                assistantFailed,
                compactionStarted,
                attemptDraft(run, RunEventType.RUN_FAILED, "reason", "no_compactable_context")),
            now);
        return;
      }
      CompactionEntryPayload completedCompaction = compaction.orElseThrow();
      if (transactions.compactAndRequeue(
          run,
          completedCompaction,
          now,
          List.of(
              assistantFailed,
              compactionStarted,
              attemptDraft(
                  run,
                  RunEventType.COMPACTION_COMPLETED,
                  "firstKeptEntryId",
                  completedCompaction.firstKeptEntryId()),
              attemptDraft(run, RunEventType.RETRY_SCHEDULED, "nextAttemptAt", now)),
          now)) {
        publishCompactionObservation(run, completedCompaction, now);
      }
    }

    private void cancelledFailure(RunEventDraft assistantFailed) {
      Instant now = clock.instant();
      terminate(
          run,
          RunStatus.CANCELLED,
          List.of(
              assistantFailed,
              attemptDraft(run, RunEventType.RUN_CANCELLED, "reason", "cancelled")),
          now);
    }

    private void permanentFailure(ProviderException error, RunEventDraft assistantFailed) {
      Instant now = clock.instant();
      terminate(
          run,
          RunStatus.FAILED,
          List.of(
              assistantFailed,
              attemptDraft(
                  run,
                  RunEventType.RUN_FAILED,
                  "kind",
                  error.kind().name(),
                  "message",
                  message(error))),
          now);
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
}
