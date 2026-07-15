package fun.fengwk.kkstudio.harness.runtime.run;

import fun.fengwk.kkstudio.harness.agent.AgentAssistantMessage;
import fun.fengwk.kkstudio.harness.agent.AgentTurnEngine;
import fun.fengwk.kkstudio.harness.agent.AgentTurnEventHandler;
import fun.fengwk.kkstudio.harness.agent.AgentTurnHandle;
import fun.fengwk.kkstudio.harness.agent.AgentTurnRequest;
import fun.fengwk.kkstudio.harness.agent.AgentTurnResult;
import fun.fengwk.kkstudio.harness.agent.DefaultAgentTurnEngine;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.context.SessionContext;
import fun.fengwk.kkstudio.harness.runtime.context.SessionContextBuilder;
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
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** 数据库 claim 驱动的单 Turn Worker。实例内不保存调度器或可恢复事实，只让当前 Provider stream 及其 Delta buffer 存活到本次回调终态。 */
public final class AgentTurnWorker {
  private static final AgentTurnHandle TERMINAL_HANDLE =
      new AgentTurnHandle() {
        @Override
        public void cancel() {}

        @Override
        public boolean isCancelled() {
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
    attemptEvent(run, RunEventType.TURN_STARTED);
    if (run.cancelRequestedAt() != null) {
      cancelBeforeStart(run);
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
    AgentTurnEngine engine = new DefaultAgentTurnEngine(resources.provider());
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
    return runStore.heartbeat(
        run.id(), run.leaseOwner(), run.attempt(), clock.instant(), config.leaseDuration());
  }

  private void cancelBeforeStart(AgentRun run) {
    Instant now = clock.instant();
    transactions.terminate(
        run,
        RunStatus.CANCELLED,
        List.of(
            attemptDraft(
                run, RunEventType.RUN_CANCELLED, "cancelRequestedAt", run.cancelRequestedAt())),
        now);
  }

  private void failBeforeStream(AgentRun run, RuntimeException error) {
    Instant now = clock.instant();
    transactions.terminate(
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
        transactions.complete(
            run,
            assistant,
            List.of(
                assistantCompleted,
                attemptDraft(run, RunEventType.RUN_COMPLETED, "status", "SUCCEEDED")),
            now);
        return;
      }
      try {
        toolPreparationPort.prepare(
            run,
            assistant,
            toolCalls,
            resources.toolBindings(),
            resources.workdir(),
            resources.environmentRoot(),
            List.of(assistantCompleted),
            now);
      } catch (IllegalArgumentException | ToolInterceptorException error) {
        failToolPreparation(error, now);
      }
    }

    private void failToolPreparation(RuntimeException error, Instant now) {
      transactions.terminate(
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
      transactions.requeue(
          run,
          nextAttemptAt,
          List.of(
              assistantFailed,
              attemptDraft(run, RunEventType.RETRY_SCHEDULED, "nextAttemptAt", nextAttemptAt)),
          now);
    }

    private void overflowFailure(RunEventDraft assistantFailed) {
      Instant now = clock.instant();
      if (run.attempt() >= config.maxAttempts()) {
        transactions.terminate(
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
      Optional<CompactionEntryPayload> compaction =
          compactionService.compact(run.sessionId(), context);
      if (compaction.isEmpty()) {
        transactions.terminate(
            run,
            RunStatus.FAILED,
            List.of(
                assistantFailed,
                compactionStarted,
                attemptDraft(run, RunEventType.RUN_FAILED, "reason", "no_compactable_context")),
            now);
        return;
      }
      transactions.compactAndRequeue(
          run,
          compaction.orElseThrow(),
          now,
          List.of(
              assistantFailed,
              compactionStarted,
              attemptDraft(
                  run,
                  RunEventType.COMPACTION_COMPLETED,
                  "firstKeptEntryId",
                  compaction.orElseThrow().firstKeptEntryId()),
              attemptDraft(run, RunEventType.RETRY_SCHEDULED, "nextAttemptAt", now)),
          now);
    }

    private void cancelledFailure(RunEventDraft assistantFailed) {
      Instant now = clock.instant();
      transactions.terminate(
          run,
          RunStatus.CANCELLED,
          List.of(
              assistantFailed,
              attemptDraft(run, RunEventType.RUN_CANCELLED, "reason", "cancelled")),
          now);
    }

    private void permanentFailure(ProviderException error, RunEventDraft assistantFailed) {
      Instant now = clock.instant();
      transactions.terminate(
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
