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
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.context.SessionContext;
import fun.fengwk.kkstudio.harness.runtime.context.SessionContextBuilder;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.CompactionEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.MessageEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
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
    if (run.attempt() == 1) {
      event(run, RunEventType.RUN_STARTED, RunEventPayloads.of("attempt", run.attempt()));
    }
    event(
        run,
        RunEventType.TURN_STARTED,
        RunEventPayloads.of("attempt", run.attempt(), "turnIndex", run.turnIndex()));
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
            eventStore,
            clock,
            deltaFlushScheduler,
            config.deltaFlushInterval(),
            config.deltaBatchBytes(),
            claimedAt);
    TurnHandler handler = new TurnHandler(run, context, batcher);
    AgentTurnEngine engine = new DefaultAgentTurnEngine(resources.provider());
    AgentTurnHandle handle;
    try {
      handle =
          engine.execute(
              new AgentTurnRequest(
                  resources.model(),
                  resources.variant(),
                  messageProjector.project(context.messages()),
                  resources.tools()),
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
    if (transactions.terminate(run, RunStatus.CANCELLED, now)) {
      event(
          run,
          RunEventType.RUN_CANCELLED,
          RunEventPayloads.of("cancelRequestedAt", run.cancelRequestedAt()));
    }
  }

  private void failBeforeStream(AgentRun run, RuntimeException error) {
    Instant now = clock.instant();
    event(
        run,
        RunEventType.ASSISTANT_FAILED,
        RunEventPayloads.of(
            "kind", ProviderErrorKind.INVALID_REQUEST.name(), "message", message(error)));
    if (transactions.terminate(run, RunStatus.FAILED, now)) {
      event(
          run,
          RunEventType.RUN_FAILED,
          RunEventPayloads.of("reason", "turn_setup_failed", "message", message(error)));
    }
  }

  private void event(AgentRun run, RunEventType type, String payload) {
    eventStore.append(run.id(), type, payload, clock.instant());
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
    private final DeltaBatcher batcher;
    private final AtomicBoolean terminal = new AtomicBoolean();

    private TurnHandler(AgentRun run, SessionContext context, DeltaBatcher batcher) {
      this.run = run;
      this.context = context;
      this.batcher = batcher;
    }

    @Override
    public void onStarted() {
      event(run, RunEventType.ASSISTANT_STARTED, RunEventPayloads.of("attempt", run.attempt()));
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
      MessageEntryPayload assistant = assistantPayload(result.assistantMessage());
      List<ToolCall> toolCalls = result.toolCalls();
      Instant now = clock.instant();
      boolean committed =
          toolCalls.isEmpty()
              ? transactions.complete(run, assistant, now)
              : toolPreparationPort.prepare(run, assistant, toolCalls, now);
      if (!committed) {
        return;
      }
      event(
          run,
          RunEventType.ASSISTANT_COMPLETED,
          RunEventPayloads.of("toolCallCount", toolCalls.size()));
      if (toolCalls.isEmpty()) {
        event(run, RunEventType.RUN_COMPLETED, RunEventPayloads.of("status", "SUCCEEDED"));
      } else {
        event(
            run,
            RunEventType.TOOL_PREPARED,
            RunEventPayloads.of("toolCallCount", toolCalls.size()));
        event(run, RunEventType.RUN_WAITING, RunEventPayloads.of("status", "WAITING_TOOLS"));
      }
    }

    @Override
    public void onFailed(ProviderException error) {
      if (!terminal.compareAndSet(false, true)) {
        return;
      }
      batcher.flush();
      event(
          run,
          RunEventType.ASSISTANT_FAILED,
          RunEventPayloads.of("kind", error.kind().name(), "message", message(error)));
      switch (error.kind()) {
        case TRANSIENT -> transientFailure(error);
        case OVERFLOW -> overflowFailure();
        case CANCELLED -> cancelledFailure();
        case AUTHENTICATION, BILLING, INVALID_REQUEST -> permanentFailure(error);
      }
    }

    private void transientFailure(ProviderException error) {
      Instant now = clock.instant();
      if (run.attempt() >= config.maxAttempts()) {
        permanentFailure(error);
        return;
      }
      Instant nextAttemptAt = now.plus(config.backoffForAttempt(run.attempt()));
      if (transactions.requeue(run, nextAttemptAt, now)) {
        event(
            run,
            RunEventType.RETRY_SCHEDULED,
            RunEventPayloads.of("attempt", run.attempt(), "nextAttemptAt", nextAttemptAt));
      }
    }

    private void overflowFailure() {
      event(run, RunEventType.COMPACTION_STARTED, RunEventPayloads.of("attempt", run.attempt()));
      Optional<CompactionEntryPayload> compaction =
          compactionService.compact(run.sessionId(), context);
      Instant now = clock.instant();
      if (compaction.isEmpty()) {
        if (transactions.terminate(run, RunStatus.FAILED, now)) {
          event(
              run,
              RunEventType.RUN_FAILED,
              RunEventPayloads.of("reason", "no_compactable_context"));
        }
        return;
      }
      if (transactions.compactAndRequeue(run, compaction.orElseThrow(), now, now)) {
        event(
            run,
            RunEventType.COMPACTION_COMPLETED,
            RunEventPayloads.of("firstKeptEntryId", compaction.orElseThrow().firstKeptEntryId()));
        event(
            run,
            RunEventType.RETRY_SCHEDULED,
            RunEventPayloads.of("attempt", run.attempt(), "nextAttemptAt", now));
      }
    }

    private void cancelledFailure() {
      Instant now = clock.instant();
      if (transactions.terminate(run, RunStatus.CANCELLED, now)) {
        event(run, RunEventType.RUN_CANCELLED, RunEventPayloads.of("reason", "cancelled"));
      }
    }

    private void permanentFailure(ProviderException error) {
      Instant now = clock.instant();
      if (transactions.terminate(run, RunStatus.FAILED, now)) {
        event(
            run,
            RunEventType.RUN_FAILED,
            RunEventPayloads.of("kind", error.kind().name(), "message", message(error)));
      }
    }
  }

  private static MessageEntryPayload assistantPayload(AgentAssistantMessage message) {
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
    return new MessageEntryPayload(new AgentMessage(AgentMessageRole.ASSISTANT, contents));
  }
}
