package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Package-private fixture builders for HarnessRuntime control-plane tests: atomic Session/Entry/
 * Thread/Invocation/Work seeds over {@link InMemoryHarnessStore} plus small value builders.
 */
final class HarnessRuntimeTestSupport {

  static final Instant T0 = Instant.ofEpochMilli(1_000);
  static final Instant T1 = Instant.ofEpochMilli(2_000);
  static final Instant T2 = Instant.ofEpochMilli(3_000);
  static final Instant T3 = Instant.ofEpochMilli(4_000);
  static final Instant T5 = Instant.ofEpochMilli(6_000);
  static final Instant T6 = Instant.ofEpochMilli(7_000);
  static final EnvironmentId ENV = new EnvironmentId(UUID.randomUUID().toString());
  static final EnvironmentId ENV2 = new EnvironmentId(UUID.randomUUID().toString());

  private HarnessRuntimeTestSupport() {}

  /** Fixed-UTC test clock whose instant can be advanced for replay/races. */
  static final class TestClock extends Clock {
    private Instant instant;

    TestClock(Instant instant) {
      this.instant = instant;
    }

    void advance(Instant value) {
      this.instant = value;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }

  record Baseline(long sessionId, long rootEntryId, long threadId) {}

  record TurnBaseline(long sessionId, long rootEntryId, long turnStartEntryId, long threadId) {}

  record ModelBaseline(
      long sessionId, long rootEntryId, long turnStartEntryId, long threadId, long modelId) {}

  record ToolBaseline(
      long sessionId,
      long rootEntryId,
      long turnStartEntryId,
      long threadId,
      long assistantEntryId,
      long modelId,
      long toolId) {}

  record MultiToolBaseline(
      long sessionId,
      long rootEntryId,
      long turnStartEntryId,
      long threadId,
      long assistantEntryId,
      long modelId,
      List<Long> toolIds) {}

  record ContinuationBaseline(
      long sessionId, long rootEntryId, long turnEndEntryId, long threadId) {}

  /** Session + ROOT + Thread(head ROOT); the quiescent IDLE_OR_HISTORICAL baseline. */
  static Baseline seedBaseline(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          long sessionId = tx.nextId();
          long rootEntryId = tx.nextId();
          long threadId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));
          tx.insertThread(thread(threadId, rootEntryId));
          return new Baseline(sessionId, rootEntryId, threadId);
        });
  }

  /** Session + ROOT + open TURN_START(INPUT) + Thread(head TURN_START). */
  static TurnBaseline seedOpenTurn(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          long sessionId = tx.nextId();
          long rootEntryId = tx.nextId();
          long turnStartEntryId = tx.nextId();
          long threadId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));
          tx.insertEntry(turnStartEntry(turnStartEntryId, sessionId, rootEntryId, T1));
          tx.insertThread(thread(threadId, turnStartEntryId));
          return new TurnBaseline(sessionId, rootEntryId, turnStartEntryId, threadId);
        });
  }

  /**
   * MODEL_ACTIVE compatibility baseline: open turn with a RUNNING model at its TURN_START basis.
   */
  static ModelBaseline seedRunningModel(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          long sessionId = tx.nextId();
          long rootEntryId = tx.nextId();
          long turnStartEntryId = tx.nextId();
          long threadId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));
          tx.insertEntry(turnStartEntry(turnStartEntryId, sessionId, rootEntryId, T1));
          tx.insertThread(thread(threadId, turnStartEntryId));
          long modelId = tx.nextId();
          ModelInvocation model =
              modelInvocation(modelId, threadId, turnStartEntryId, turnStartEntryId, T1);
          tx.insertModelInvocation(model);
          tx.updateModelInvocation(model.beginDispatch(T2));
          tx.updateModelInvocation(model.beginDispatch(T2).markRunning(T2));
          return new ModelBaseline(sessionId, rootEntryId, turnStartEntryId, threadId, modelId);
        });
  }

  /**
   * MODEL_ACTIVE baseline at the given live status (READY / DISPATCHING / RUNNING): ROOT -&gt;
   * TURN_START(INPUT) -&gt; USER, thread head at the USER entry, model basis = the USER entry.
   */
  static ModelBaseline seedModel(InMemoryHarnessStore store, ModelInvocationStatus status) {
    return store.transaction(
        tx -> {
          long sessionId = tx.nextId();
          long rootEntryId = tx.nextId();
          long turnStartEntryId = tx.nextId();
          long threadId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));
          tx.insertEntry(turnStartEntry(turnStartEntryId, sessionId, rootEntryId, T1));
          long userEntryId = tx.nextId();
          tx.insertEntry(userMessageEntry(userEntryId, sessionId, turnStartEntryId, T1));
          ThreadState thread = thread(threadId, userEntryId);
          tx.insertThread(thread);
          long modelId = tx.nextId();
          ModelInvocation model =
              modelInvocation(modelId, threadId, turnStartEntryId, userEntryId, T1);
          tx.insertModelInvocation(model);
          if (status == ModelInvocationStatus.DISPATCHING) {
            tx.updateModelInvocation(model.beginDispatch(T2));
          } else if (status == ModelInvocationStatus.RUNNING) {
            tx.updateModelInvocation(model.beginDispatch(T2));
            tx.updateModelInvocation(model.beginDispatch(T2).markRunning(T2));
          }
          return new ModelBaseline(sessionId, rootEntryId, turnStartEntryId, threadId, modelId);
        });
  }

  /**
   * MODEL_ACTIVE on a CONTINUATION turn: bare TURN_START(CONTINUATION) head (the real plan shape
   * has no input entries) with a RUNNING model at that TURN_START basis.
   */
  static ModelBaseline seedRunningContinuationModel(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          long sessionId = tx.nextId();
          long rootEntryId = tx.nextId();
          long turnStartEntryId = tx.nextId();
          long threadId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));
          tx.insertEntry(
              new Entry(
                  turnStartEntryId,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(TurnStartReason.CONTINUATION, settings()),
                  T1));
          ThreadState thread = thread(threadId, turnStartEntryId);
          tx.insertThread(thread);
          long modelId = tx.nextId();
          ModelInvocation model =
              modelInvocation(modelId, threadId, turnStartEntryId, turnStartEntryId, T1);
          tx.insertModelInvocation(model);
          tx.updateModelInvocation(model.beginDispatch(T2));
          tx.updateModelInvocation(model.beginDispatch(T2).markRunning(T2));
          return new ModelBaseline(sessionId, rootEntryId, turnStartEntryId, threadId, modelId);
        });
  }

  /** MODEL_TERMINAL_PENDING baseline: open turn with a terminal model not yet applied. */
  static ModelBaseline seedTerminalModel(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          long sessionId = tx.nextId();
          long rootEntryId = tx.nextId();
          long turnStartEntryId = tx.nextId();
          long threadId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));
          tx.insertEntry(turnStartEntry(turnStartEntryId, sessionId, rootEntryId, T1));
          tx.insertThread(thread(threadId, turnStartEntryId));
          long modelId = tx.nextId();
          ModelInvocation model =
              modelInvocation(modelId, threadId, turnStartEntryId, turnStartEntryId, T1);
          tx.insertModelInvocation(model);
          tx.updateModelInvocation(model.cancel(modelError(), T2));
          return new ModelBaseline(sessionId, rootEntryId, turnStartEntryId, threadId, modelId);
        });
  }

  /**
   * Full TOOL baseline: ROOT -&gt; TURN_START(INPUT) -&gt; USER -&gt; ASSISTANT(call-1), SUCCEEDED
   * model attached to the assistant, one READY tool invocation, thread head at the assistant entry.
   */
  static ToolBaseline seedToolBaseline(InMemoryHarnessStore store) {
    MultiToolBaseline multi = seedToolBaseline(store, 1);
    return new ToolBaseline(
        multi.sessionId(),
        multi.rootEntryId(),
        multi.turnStartEntryId(),
        multi.threadId(),
        multi.assistantEntryId(),
        multi.modelId(),
        multi.toolIds().get(0));
  }

  /**
   * Multi-sibling TOOL baseline: the assistant carries {@code toolCount} calls ("call-0" ...) and
   * one READY invocation per call, so Stop can converge a full sibling status matrix in one path.
   */
  static MultiToolBaseline seedToolBaseline(InMemoryHarnessStore store, int toolCount) {
    if (toolCount <= 0) {
      throw new IllegalArgumentException("toolCount must be positive");
    }
    String[] callIds = new String[toolCount];
    for (int i = 0; i < toolCount; i++) {
      callIds[i] = toolCount == 1 ? "call-1" : "call-" + i;
    }
    return store.transaction(
        tx -> {
          long sessionId = tx.nextId();
          long rootEntryId = tx.nextId();
          long turnStartEntryId = tx.nextId();
          long threadId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));
          tx.insertEntry(turnStartEntry(turnStartEntryId, sessionId, rootEntryId, T1));
          ThreadState thread = thread(threadId, turnStartEntryId);
          tx.insertThread(thread);
          long userEntryId = tx.nextId();
          tx.insertEntry(userMessageEntry(userEntryId, sessionId, turnStartEntryId, T1));
          long assistantEntryId = tx.nextId();
          tx.insertEntry(assistantEntry(assistantEntryId, sessionId, userEntryId, T1, callIds));
          long modelId = tx.nextId();
          ModelInvocation model =
              modelInvocation(modelId, threadId, turnStartEntryId, turnStartEntryId, T1);
          tx.insertModelInvocation(model);
          ModelInvocation succeeded =
              model.beginDispatch(T2).markRunning(T2).succeed(responseWithToolCalls(callIds), T2);
          tx.updateModelInvocation(model.beginDispatch(T2));
          tx.updateModelInvocation(model.beginDispatch(T2).markRunning(T2));
          tx.updateModelInvocation(succeeded);
          tx.updateModelInvocation(succeeded.attachResultEntry(assistantEntryId, T2));
          List<ToolInvocation> invocations = new ArrayList<>(toolCount);
          List<Long> toolIds = new ArrayList<>(toolCount);
          for (int ordinal = 0; ordinal < toolCount; ordinal++) {
            long toolId = tx.nextId();
            toolIds.add(toolId);
            invocations.add(
                toolInvocation(toolId, modelId, assistantEntryId, ordinal, callIds[ordinal], T1));
          }
          tx.insertToolInvocations(invocations);
          tx.updateThread(thread.advanceHead(assistantEntryId, thread.yoloEnabled(), T2));
          return new MultiToolBaseline(
              sessionId,
              rootEntryId,
              turnStartEntryId,
              threadId,
              assistantEntryId,
              modelId,
              List.copyOf(toolIds));
        });
  }

  /** Moves the tool invocation of a TOOL baseline into WAITING_APPROVAL (TOOL_ACTIVE context). */
  static ToolInvocation setWaitingApproval(InMemoryHarnessStore store, ToolBaseline baseline) {
    return store.transaction(
        tx -> {
          ToolInvocation tool = tx.lockToolInvocation(baseline.toolId()).orElseThrow();
          ToolInvocation waiting = tool.requestApproval("tool approval requested", T3);
          tx.updateToolInvocations(List.of(waiting));
          return waiting;
        });
  }

  /** Marks the tool invocation terminal without an applied result (TOOL_TERMINAL_PENDING). */
  static ToolInvocation cancelTool(InMemoryHarnessStore store, ToolBaseline baseline) {
    return cancelTool(store, baseline.toolId());
  }

  /** Marks the tool invocation of the given id terminal CANCELLED without an applied result. */
  static ToolInvocation cancelTool(InMemoryHarnessStore store, long toolId) {
    return store.transaction(
        tx -> {
          ToolInvocation tool = tx.lockToolInvocation(toolId).orElseThrow();
          ToolInvocation cancelled = tool.cancel(toolError(), T3);
          tx.updateToolInvocations(List.of(cancelled));
          return cancelled;
        });
  }

  /** READY -&gt; DISPATCHING of the given tool (approval preflight completed, attempt stays 0). */
  static ToolInvocation beginDispatchTool(InMemoryHarnessStore store, long toolId) {
    return store.transaction(
        tx -> {
          ToolInvocation tool = tx.lockToolInvocation(toolId).orElseThrow();
          ToolInvocation preflighted = tool.markApprovalNotRequired(T3);
          tx.updateToolInvocations(List.of(preflighted));
          ToolInvocation updated = preflighted.beginDispatch(T3);
          tx.updateToolInvocations(List.of(updated));
          return updated;
        });
  }

  /** DISPATCHING -&gt; RUNNING of the given tool (attempt advances to 1). */
  static ToolInvocation markRunningTool(InMemoryHarnessStore store, long toolId) {
    return store.transaction(
        tx -> {
          ToolInvocation tool = tx.lockToolInvocation(toolId).orElseThrow();
          ToolInvocation updated = tool.markRunning(T3);
          tx.updateToolInvocations(List.of(updated));
          return updated;
        });
  }

  /** RUNNING -&gt; READY after a retryable attempt; the confirmed attempt remains positive. */
  static ToolInvocation retryReadyTool(InMemoryHarnessStore store, long toolId) {
    return store.transaction(
        tx -> {
          ToolInvocation tool = tx.lockToolInvocation(toolId).orElseThrow();
          ToolInvocation updated = tool.retryReady(T3);
          tx.updateToolInvocations(List.of(updated));
          return updated;
        });
  }

  /** RUNNING -&gt; SUCCEEDED of the given tool with a real result, result not yet attached. */
  static ToolInvocation succeedTool(InMemoryHarnessStore store, long toolId) {
    return store.transaction(
        tx -> {
          ToolInvocation tool = tx.lockToolInvocation(toolId).orElseThrow();
          ToolInvocation updated =
              tool.succeed(
                  new ToolResult(
                      tool.request().call().id(),
                      List.of(new TextToolContent("real result")),
                      false,
                      "{}",
                      false),
                  T3);
          tx.updateToolInvocations(List.of(updated));
          return updated;
        });
  }

  /** Marks the tool approval as not required (READY with a decided-neutral approval). */
  static ToolInvocation markApprovalNotRequired(InMemoryHarnessStore store, ToolBaseline baseline) {
    return store.transaction(
        tx -> {
          ToolInvocation tool = tx.lockToolInvocation(baseline.toolId()).orElseThrow();
          ToolInvocation updated = tool.markApprovalNotRequired(T3);
          tx.updateToolInvocations(List.of(updated));
          return updated;
        });
  }

  /** ROOT -&gt; TURN_START(INPUT) -&gt; USER -&gt; ASSISTANT -&gt; TURN_END(continueModel=true). */
  static ContinuationBaseline seedContinuationChain(
      InMemoryHarnessStore store, boolean headAtTurnEnd) {
    return store.transaction(
        tx -> {
          long sessionId = tx.nextId();
          long rootEntryId = tx.nextId();
          long turnStartEntryId = tx.nextId();
          long threadId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));
          tx.insertEntry(turnStartEntry(turnStartEntryId, sessionId, rootEntryId, T1));
          ThreadState thread = thread(threadId, headAtTurnEnd ? turnStartEntryId : rootEntryId);
          tx.insertThread(thread);
          long userEntryId = tx.nextId();
          tx.insertEntry(userMessageEntry(userEntryId, sessionId, turnStartEntryId, T1));
          long assistantEntryId = tx.nextId();
          tx.insertEntry(assistantEntry(assistantEntryId, sessionId, userEntryId, T1));
          long turnEndEntryId = tx.nextId();
          tx.insertEntry(
              turnEndEntry(
                  turnEndEntryId, sessionId, assistantEntryId, T1, turnStartEntryId, true));
          if (headAtTurnEnd) {
            tx.updateThread(thread.advanceHead(turnEndEntryId, thread.yoloEnabled(), T1));
          }
          return new ContinuationBaseline(sessionId, rootEntryId, turnEndEntryId, threadId);
        });
  }

  /** Second Session with its own ROOT (cross-session MOVE_HEAD target source). */
  static long seedForeignRoot(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          long sessionId = tx.nextId();
          long rootEntryId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));
          return rootEntryId;
        });
  }

  /** Extra Thread in the same Session pointing at an existing head Entry. */
  static long seedThreadAt(InMemoryHarnessStore store, long headEntryId) {
    return store.transaction(
        tx -> {
          long id = tx.nextId();
          tx.insertThread(thread(id, headEntryId));
          return id;
        });
  }

  /** Extra Thread with YOLO enabled pointing at an existing head Entry. */
  static long seedYoloThreadAt(InMemoryHarnessStore store, long headEntryId) {
    return store.transaction(
        tx -> {
          long id = tx.nextId();
          tx.insertThread(thread(id, headEntryId, true));
          return id;
        });
  }

  /** Inserts a TURN_START child under {@code parentEntryId} and returns its id. */
  static long seedChildTurnStart(InMemoryHarnessStore store, long sessionId, long parentEntryId) {
    return store.transaction(
        tx -> {
          long id = tx.nextId();
          tx.insertEntry(turnStartEntry(id, sessionId, parentEntryId, T1));
          return id;
        });
  }

  /**
   * Test-only delegating store whose transactions advance the mutable {@code clock} the moment the
   * Thread row is locked, so tests prove the control plane captures its timestamp after the lock
   * wait (an old pre-lock instant would regress the row's updatedAt).
   */
  static HarnessStore storeAdvancingClockOnThreadLock(
      InMemoryHarnessStore delegate, TestClock clock, Instant advanceTo) {
    return (HarnessStore)
        Proxy.newProxyInstance(
            HarnessStore.class.getClassLoader(),
            new Class<?>[] {HarnessStore.class},
            (proxy, method, args) -> {
              if (method.getName().equals("transaction")) {
                @SuppressWarnings("unchecked")
                Function<HarnessStore.Transaction, Object> callback =
                    (Function<HarnessStore.Transaction, Object>) args[0];
                return delegate.transaction(
                    tx -> {
                      HarnessStore.Transaction wrapped =
                          (HarnessStore.Transaction)
                              Proxy.newProxyInstance(
                                  HarnessStore.Transaction.class.getClassLoader(),
                                  new Class<?>[] {HarnessStore.Transaction.class},
                                  (transactionProxy, transactionMethod, transactionArgs) -> {
                                    if (transactionMethod.getName().equals("lockThread")) {
                                      clock.advance(advanceTo);
                                    }
                                    return transactionMethod.invoke(tx, transactionArgs);
                                  });
                      return callback.apply(wrapped);
                    });
              }
              return method.invoke(delegate, args);
            });
  }

  /**
   * Test-only delegating store whose transactions advance the mutable {@code clock} the moment any
   * Work row is locked, so tests prove Stop reads its timestamp only after the whole Work pre-lock
   * (a pre-lock instant would regress the updatedAt written by the Stop transaction).
   */
  static HarnessStore storeAdvancingClockOnWorkLock(
      InMemoryHarnessStore delegate, TestClock clock, Instant advanceTo) {
    return (HarnessStore)
        Proxy.newProxyInstance(
            HarnessStore.class.getClassLoader(),
            new Class<?>[] {HarnessStore.class},
            (proxy, method, args) -> {
              if (method.getName().equals("transaction")) {
                @SuppressWarnings("unchecked")
                Function<HarnessStore.Transaction, Object> callback =
                    (Function<HarnessStore.Transaction, Object>) args[0];
                return delegate.transaction(
                    tx -> {
                      HarnessStore.Transaction wrapped =
                          (HarnessStore.Transaction)
                              Proxy.newProxyInstance(
                                  HarnessStore.Transaction.class.getClassLoader(),
                                  new Class<?>[] {HarnessStore.Transaction.class},
                                  (transactionProxy, transactionMethod, transactionArgs) -> {
                                    if (transactionMethod.getName().equals("lockWork")) {
                                      clock.advance(advanceTo);
                                    }
                                    return transactionMethod.invoke(tx, transactionArgs);
                                  });
                      return callback.apply(wrapped);
                    });
              }
              return method.invoke(delegate, args);
            });
  }

  /** Inserts one QUEUED command on the thread (must be a fresh thread without commands). */
  static void seedQueuedCommand(
      InMemoryHarnessStore store,
      long threadId,
      long commandId,
      long sequence,
      ThreadCommandPayload payload,
      String clientCommandId) {
    store.transaction(
        tx -> {
          tx.lockThread(threadId);
          tx.insertCommands(
              List.of(
                  new ThreadCommand(
                      commandId, threadId, sequence, payload, clientCommandId, null, null, T1)));
          return null;
        });
  }

  /** Requests an unleased THREAD Work row (speculative Resolver mailbox fence). */
  static void seedThreadWork(InMemoryHarnessStore store, long threadId) {
    store.transaction(
        tx -> {
          tx.lockThread(threadId).orElseThrow();
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, threadId), T0);
          return null;
        });
  }

  /** Requests an unleased MODEL Work row (fenced while a Model is live). */
  static void seedModelWork(InMemoryHarnessStore store, long modelId) {
    store.transaction(
        tx -> {
          ModelInvocation model = tx.findModelInvocation(modelId).orElseThrow();
          tx.lockThread(model.threadId()).orElseThrow();
          tx.requestWork(new WorkTarget(WorkTargetType.MODEL, modelId), T0);
          return null;
        });
  }

  /** Requests an unleased TOOL Work row for the given invocation. */
  static void seedToolWork(InMemoryHarnessStore store, long toolId) {
    store.transaction(
        tx -> {
          ToolInvocation tool = tx.findToolInvocation(toolId).orElseThrow();
          ModelInvocation model = tx.findModelInvocation(tool.modelInvocationId()).orElseThrow();
          tx.lockThread(model.threadId()).orElseThrow();
          tx.requestWork(new WorkTarget(WorkTargetType.TOOL, toolId), T0);
          return null;
        });
  }

  /** Claims the THREAD Work row so it exists with an active lease. */
  static void seedClaimedThreadWork(InMemoryHarnessStore store, long threadId) {
    store.transaction(
        tx -> {
          tx.lockThread(threadId).orElseThrow();
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, threadId), T0);
          tx.claimNextWork(WorkTargetType.THREAD, T0, "lease-1", T3);
          return null;
        });
  }

  static Session session(long id) {
    return new Session(id, "session-" + id, T0);
  }

  static Entry rootEntry(long id, long sessionId) {
    return new Entry(id, sessionId, null, new RootPayload(settings()), T0);
  }

  static Entry turnStartEntry(long id, long sessionId, long parentId, Instant createdAt) {
    return new Entry(
        id,
        sessionId,
        parentId,
        new TurnStartPayload(TurnStartReason.INPUT, settings()),
        createdAt);
  }

  static Entry userMessageEntry(long id, long sessionId, long parentId, Instant createdAt) {
    return new Entry(
        id,
        sessionId,
        parentId,
        new MessagePayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
            null,
            null),
        createdAt);
  }

  static Entry assistantEntry(
      long id, long sessionId, long parentId, Instant createdAt, String... toolCallIds) {
    List<AgentMessageContent> contents = new ArrayList<>();
    for (String toolCallId : toolCallIds) {
      contents.add(new ToolCallMessageContent(toolCallId, "bash", "{}"));
    }
    contents.add(new TextMessageContent("assistant reply"));
    ProviderStopReason stopReason =
        toolCallIds.length > 0 ? ProviderStopReason.TOOL_CALLS : ProviderStopReason.COMPLETED;
    return new Entry(
        id,
        sessionId,
        parentId,
        new MessagePayload(
            new AgentMessage(AgentMessageRole.ASSISTANT, contents),
            assistantMetadata(stopReason),
            null),
        createdAt);
  }

  static Entry turnEndEntry(
      long id,
      long sessionId,
      long parentId,
      Instant createdAt,
      long turnStartEntryId,
      boolean continueModel) {
    return new Entry(
        id,
        sessionId,
        parentId,
        new TurnEndPayload(turnStartEntryId, TurnEndOutcome.COMPLETED, continueModel, null, null),
        createdAt);
  }

  static ThreadState thread(long id, long headEntryId) {
    return thread(id, headEntryId, false);
  }

  static ThreadState thread(long id, long headEntryId, boolean yoloEnabled) {
    return new ThreadState(id, headEntryId, yoloEnabled, 1, 0, T0, T0);
  }

  static ThreadCommand withConsumedTurnStart(ThreadCommand command, long turnStartEntryId) {
    return new ThreadCommand(
        command.id(),
        command.threadId(),
        command.sequence(),
        command.payload(),
        command.clientCommandId(),
        turnStartEntryId,
        null,
        command.createdAt());
  }

  /** New USER_MESSAGE command with the given stable client id and text. */
  static NewThreadCommand userMessageCommand(String clientCommandId, String text) {
    return new NewThreadCommand(userMessagePayload(text), clientCommandId);
  }

  static UserMessageCommandPayload userMessagePayload(String text) {
    return new UserMessageCommandPayload(
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text))));
  }

  static ModelInvocation modelInvocation(
      long id, long threadId, long turnStartEntryId, long basisHeadEntryId, Instant createdAt) {
    return new ModelInvocation(
        id,
        threadId,
        turnStartEntryId,
        basisHeadEntryId,
        modelRequest(),
        ModelInvocationStatus.READY,
        0,
        null,
        null,
        null,
        null,
        createdAt,
        createdAt);
  }

  static ToolInvocation toolInvocation(
      long id,
      long modelInvocationId,
      long assistantEntryId,
      int ordinal,
      String toolCallId,
      Instant createdAt) {
    return new ToolInvocation(
        id,
        modelInvocationId,
        assistantEntryId,
        ordinal,
        toolRequest(toolCallId),
        ToolInvocationStatus.READY,
        0,
        null,
        null,
        null,
        null,
        createdAt,
        createdAt);
  }

  static BranchSettings settings() {
    return new BranchSettings(
        ENV, "agent", new ModelSelection("provider", "model", "v1"), "low", List.of());
  }

  static ModelInvocationRequest modelRequest() {
    return new ModelInvocationRequest(ENV, providerRequest(), List.of(), List.of(), false);
  }

  static ToolInvocationRequest toolRequest(String toolCallId) {
    return new ToolInvocationRequest(new ToolCall(toolCallId, "bash", "{}"), platformBinding());
  }

  static ProviderResponse responseWithToolCalls(String... toolCallIds) {
    List<ProviderToolCall> calls = new ArrayList<>(toolCallIds.length);
    for (String toolCallId : toolCallIds) {
      calls.add(new ProviderToolCall(toolCallId, "bash", "{}"));
    }
    return new ProviderResponse(
        "", "", calls, ProviderStopReason.TOOL_CALLS, usage(), cost(), null, null, null);
  }

  static ModelInvocationError modelError() {
    return new ModelInvocationError(ProviderErrorKind.TRANSIENT, "model boom");
  }

  static ToolInvocationError toolError() {
    return new ToolInvocationError("CANCELLED", "cancelled");
  }

  private static AssistantMessageMetadata assistantMetadata(ProviderStopReason stopReason) {
    return new AssistantMessageMetadata(stopReason, usage(), cost());
  }

  private static ModelUsage usage() {
    return new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L);
  }

  private static ModelCost cost() {
    return new ModelCost(
        "USD",
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO);
  }

  private static ProviderRequest providerRequest() {
    return new ProviderRequest(
        modelDescriptor(),
        new ModelVariant("v1", null, null, null, null, null, null, List.of(), null),
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  private static ModelDescriptor modelDescriptor() {
    return new ModelDescriptor(
        "provider",
        1L,
        "model",
        ProviderType.OPENAI,
        true,
        true,
        new ModelPricing(
            "USD",
            "standard",
            "standard",
            BigDecimal.ONE,
            "1",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO),
        PromptCachePolicy.disabled());
  }

  private static ToolBinding platformBinding() {
    return new ToolBinding(toolDescriptor("bash"), ToolType.PLATFORM, null);
  }

  private static ToolDescriptor toolDescriptor(String name) {
    return new ToolDescriptor(
        name,
        "1.0",
        ToolType.PLATFORM,
        "description of " + name,
        null,
        new ToolParamsSchema("arguments", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
  }

  /** Helper for a void transaction body over the in-memory store. */
  static void inTransaction(InMemoryHarnessStore store, Consumer<HarnessStore.Transaction> body) {
    store.transaction(
        tx -> {
          body.accept(tx);
          return null;
        });
  }
}
