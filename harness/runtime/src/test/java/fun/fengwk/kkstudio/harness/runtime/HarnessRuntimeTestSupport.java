package fun.fengwk.kkstudio.harness.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fun.fengwk.kkstudio.harness.common.result.TextResultContent;
import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfigProvider;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEffectBatch;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.port.ToolResultHistoryMaterializer;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
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
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * HarnessRuntime 控制面测试用的包内 fixture 构建器：基于 {@link InMemoryHarnessStore} 提供原子的
 * Session/Entry/Thread/Invocation/Work 种子，以及小型 value 构建器。
 */
final class HarnessRuntimeTestSupport {

  static final TurnResolver UNUSED_RESOLVER = (threadId, candidatePath, preparation) -> null;
  static final CompactionConfigProvider DEFAULT_COMPACTION_PROVIDER =
      () -> CompactionConfig.DEFAULT;

  static final UUID OWNER_THREAD_ID = new UUID(0L, 1L);
  static final Instant T0 = Instant.ofEpochMilli(1_000);
  static final Instant T1 = Instant.ofEpochMilli(2_000);
  static final Instant T2 = Instant.ofEpochMilli(3_000);
  static final Instant T3 = Instant.ofEpochMilli(4_000);
  static final Instant T5 = Instant.ofEpochMilli(6_000);
  static final Instant T6 = Instant.ofEpochMilli(7_000);

  /** 测试种子用的合法 64 位小写 SHA-256 creation request hash（非 NEW_SESSION/ENTRY 路径的任意固定值）。 */
  static final String CREATION_REQUEST_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  static final EnvironmentId ENV = EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  static final EnvironmentId ENV2 = EnvironmentId.parse("22222222-2222-2222-2222-222222222222");

  private HarnessRuntimeTestSupport() {}

  /** 创建仅带默认/未使用依赖的测试 Runtime 控制面。 */
  static HarnessRuntime runtime(HarnessStore store, Clock clock) {
    return new HarnessRuntime(store, clock, UNUSED_RESOLVER, DEFAULT_COMPACTION_PROVIDER);
  }

  /** 创建带指定 resolver 与 compaction provider 的测试 Runtime。 */
  static HarnessRuntime runtime(
      HarnessStore store,
      Clock clock,
      TurnResolver resolver,
      CompactionConfigProvider compactionConfigProvider) {
    return new HarnessRuntime(store, clock, resolver, compactionConfigProvider);
  }

  /** 创建带 local execution canceller 的测试 Runtime。 */
  static HarnessRuntime runtime(
      HarnessStore store,
      Clock clock,
      Consumer<UUID> modelCanceller,
      Consumer<UUID> toolCanceller) {
    return new HarnessRuntime(
        store,
        clock,
        UNUSED_RESOLVER,
        DEFAULT_COMPACTION_PROVIDER,
        null,
        modelCanceller,
        toolCanceller);
  }

  /** 创建带 materializer 与 local execution canceller 的测试 Runtime。 */
  static HarnessRuntime runtime(
      HarnessStore store,
      Clock clock,
      ToolResultHistoryMaterializer materializer,
      Consumer<UUID> modelCanceller,
      Consumer<UUID> toolCanceller) {
    return new HarnessRuntime(
        store,
        clock,
        UNUSED_RESOLVER,
        DEFAULT_COMPACTION_PROVIDER,
        materializer,
        modelCanceller,
        toolCanceller);
  }

  /** 固定 UTC 的测试时钟，可推进 instant 用于 replay/竞态测试。 */
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

  record Baseline(UUID sessionId, UUID rootEntryId, UUID threadId) {}

  record TurnBaseline(UUID sessionId, UUID rootEntryId, UUID turnStartEntryId, UUID threadId) {}

  record ModelBaseline(
      UUID sessionId, UUID rootEntryId, UUID turnStartEntryId, UUID threadId, UUID modelId) {}

  record ToolBaseline(
      UUID sessionId,
      UUID rootEntryId,
      UUID turnStartEntryId,
      UUID threadId,
      UUID assistantEntryId,
      UUID modelId,
      UUID toolId) {}

  record MultiToolBaseline(
      UUID sessionId,
      UUID rootEntryId,
      UUID turnStartEntryId,
      UUID threadId,
      UUID assistantEntryId,
      UUID modelId,
      List<UUID> toolIds) {}

  record ContinuationBaseline(
      UUID sessionId, UUID rootEntryId, UUID turnEndEntryId, UUID threadId) {}

  /** Session + ROOT + Thread(head 指向 ROOT)；静默的 IDLE_OR_HISTORICAL baseline。 */
  static Baseline seedBaseline(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));
          tx.insertThread(thread(threadId, sessionId, rootEntryId));
          return new Baseline(sessionId, rootEntryId, threadId);
        });
  }

  /** Session + ROOT + 打开的 TURN_START(INPUT) + Thread(head 指向 TURN_START)。 */
  static TurnBaseline seedOpenTurn(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turnStartEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));
          tx.insertEntry(turnStartEntry(turnStartEntryId, sessionId, rootEntryId, T1, threadId));
          tx.insertThread(thread(threadId, sessionId, turnStartEntryId));
          return new TurnBaseline(sessionId, rootEntryId, turnStartEntryId, threadId);
        });
  }

  /** MODEL_ACTIVE baseline：打开的 turn，在 TURN_START requestHead 上挂一个 RUNNING 的 Model。 */
  static ModelBaseline seedRunningModel(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turnStartEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));
          tx.insertEntry(turnStartEntry(turnStartEntryId, sessionId, rootEntryId, T1, threadId));
          tx.insertThread(thread(threadId, sessionId, turnStartEntryId));
          UUID modelId = tx.nextId();
          ModelInvocation model =
              modelInvocation(modelId, threadId, turnStartEntryId, turnStartEntryId, T1);
          tx.insertModelInvocation(model);
          tx.updateModelInvocation(model.beginDispatch(T2));
          tx.updateModelInvocation(model.beginDispatch(T2).markRunning(T2));
          return new ModelBaseline(sessionId, rootEntryId, turnStartEntryId, threadId, modelId);
        });
  }

  /**
   * MODEL_ACTIVE baseline，按指定的活动状态（READY / DISPATCHING / RUNNING）： ROOT -&gt; TURN_START(INPUT)
   * -&gt; USER，thread head 指向 USER entry，model requestHead = USER entry。
   */
  static ModelBaseline seedModel(InMemoryHarnessStore store, ModelInvocationStatus status) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turnStartEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));
          tx.insertEntry(turnStartEntry(turnStartEntryId, sessionId, rootEntryId, T1, threadId));
          UUID userEntryId = tx.nextId();
          tx.insertEntry(userMessageEntry(userEntryId, sessionId, turnStartEntryId, T1));
          ThreadState thread = thread(threadId, sessionId, userEntryId);
          tx.insertThread(thread);
          UUID modelId = tx.nextId();
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
   * CONTINUATION turn 上的 MODEL_ACTIVE：裸的 TURN_START(CONTINUATION) head（真实 plan 形态 没有 input
   * entry），并在该 TURN_START requestHead 上挂一个 RUNNING 的 Model。
   */
  static ModelBaseline seedRunningContinuationModel(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turnStartEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));
          tx.insertEntry(
              new Entry(
                  turnStartEntryId,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(
                      TurnStartReason.CONTINUATION, settings(), threadId, 100_000, 16_384, null),
                  T1));
          ThreadState thread = thread(threadId, sessionId, turnStartEntryId);
          tx.insertThread(thread);
          UUID modelId = tx.nextId();
          ModelInvocation model =
              modelInvocation(modelId, threadId, turnStartEntryId, turnStartEntryId, T1);
          tx.insertModelInvocation(model);
          tx.updateModelInvocation(model.beginDispatch(T2));
          tx.updateModelInvocation(model.beginDispatch(T2).markRunning(T2));
          return new ModelBaseline(sessionId, rootEntryId, turnStartEntryId, threadId, modelId);
        });
  }

  /** MODEL_TERMINAL_PENDING baseline：打开的 turn，挂一个尚未 apply 的 terminal Model。 */
  static ModelBaseline seedTerminalModel(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turnStartEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));
          tx.insertEntry(turnStartEntry(turnStartEntryId, sessionId, rootEntryId, T1, threadId));
          tx.insertThread(thread(threadId, sessionId, turnStartEntryId));
          UUID modelId = tx.nextId();
          ModelInvocation model =
              modelInvocation(modelId, threadId, turnStartEntryId, turnStartEntryId, T1);
          tx.insertModelInvocation(model);
          tx.updateModelInvocation(model.cancel(modelError(), T2));
          return new ModelBaseline(sessionId, rootEntryId, turnStartEntryId, threadId, modelId);
        });
  }

  /**
   * 完整 TOOL baseline：ROOT -&gt; TURN_START(INPUT) -&gt; USER -&gt; ASSISTANT(call-1)， 在 assistant
   * 上挂一个 SUCCEEDED 的 Model 与一个 READY 的 ToolInvocation， thread head 指向 assistant entry。
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
   * 多 sibling 的 TOOL baseline：assistant 上携带 {@code toolCount} 个 tool call（"call-0" …）， 每个 call 对应一个
   * READY invocation，因此 Stop 可以在一条路径上收敛完整的 sibling 状态矩阵。
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
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turnStartEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));
          tx.insertEntry(turnStartEntry(turnStartEntryId, sessionId, rootEntryId, T1, threadId));
          ThreadState thread = thread(threadId, sessionId, turnStartEntryId);
          tx.insertThread(thread);
          UUID userEntryId = tx.nextId();
          tx.insertEntry(userMessageEntry(userEntryId, sessionId, turnStartEntryId, T1));
          ModelRequestSpec requestSpec = tooledModelRequest(List.of("bash"));
          ProviderResponse response = responseWithToolCalls(callIds);
          UUID assistantEntryId = tx.nextId();
          // live attached fixture：assistant Entry 由同一 frozen request + ProviderResponse 经 mapper
          // 生成，
          // 与 validateAttached 的完整 payload 校验保持一致。
          tx.insertEntry(
              mappedAssistantEntry(
                  assistantEntryId, sessionId, userEntryId, T1, requestSpec, response));
          UUID modelId = tx.nextId();
          ModelInvocation model =
              modelInvocationWithRequest(
                  modelId, threadId, turnStartEntryId, turnStartEntryId, requestSpec, T1);
          tx.insertModelInvocation(model);
          ModelInvocation succeeded = model.beginDispatch(T2).markRunning(T2).succeed(response, T2);
          tx.updateModelInvocation(model.beginDispatch(T2));
          tx.updateModelInvocation(model.beginDispatch(T2).markRunning(T2));
          tx.updateModelInvocation(succeeded);
          tx.updateModelInvocation(succeeded.attachResultEntry(assistantEntryId, T2));
          List<ToolInvocation> invocations = new ArrayList<>(toolCount);
          List<UUID> toolIds = new ArrayList<>(toolCount);
          for (int callIndex = 0; callIndex < toolCount; callIndex++) {
            UUID toolId = tx.nextId();
            toolIds.add(toolId);
            invocations.add(
                toolInvocation(
                    toolId, modelId, assistantEntryId, callIndex, callIds[callIndex], T1));
          }
          tx.insertToolInvocations(invocations);
          tx.updateThread(thread.advanceHead(assistantEntryId, T2));
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

  /** 将 TOOL baseline 上的 ToolInvocation 推进到 WAITING_APPROVAL（TOOL_ACTIVE 上下文）。 */
  static ToolInvocation setWaitingApproval(InMemoryHarnessStore store, ToolBaseline baseline) {
    return store.transaction(
        tx -> {
          ToolInvocation tool = tx.lockToolInvocation(baseline.toolId()).orElseThrow();
          ToolInvocation waiting = tool.requestApproval("tool approval requested", T3);
          tx.updateToolInvocations(List.of(waiting));
          return waiting;
        });
  }

  /** 将 ToolInvocation 标记为 terminal 但未应用结果（TOOL_TERMINAL_PENDING）。 */
  static ToolInvocation cancelTool(InMemoryHarnessStore store, ToolBaseline baseline) {
    return cancelTool(store, baseline.toolId());
  }

  /** 将指定 id 的 ToolInvocation 标记为 terminal CANCELLED 但未应用结果。 */
  static ToolInvocation cancelTool(InMemoryHarnessStore store, UUID toolId) {
    return store.transaction(
        tx -> {
          ToolInvocation tool = tx.lockToolInvocation(toolId).orElseThrow();
          ToolInvocation cancelled = tool.cancel(toolError(), T3);
          tx.updateToolInvocations(List.of(cancelled));
          return cancelled;
        });
  }

  /** 将指定 tool 从 READY 推进到 DISPATCHING（approval preflight 已完成，attempt 保持为 0）。 */
  static ToolInvocation beginDispatchTool(InMemoryHarnessStore store, UUID toolId) {
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

  /** 将指定 tool 从 DISPATCHING 推进到 RUNNING（attempt 推进到 1）。 */
  static ToolInvocation markRunningTool(InMemoryHarnessStore store, UUID toolId) {
    return store.transaction(
        tx -> {
          ToolInvocation tool = tx.lockToolInvocation(toolId).orElseThrow();
          ToolInvocation updated = tool.markRunning(T3);
          tx.updateToolInvocations(List.of(updated));
          return updated;
        });
  }

  /** 在一次可重试尝试后将 RUNNING 转回 READY；已确认的 attempt 保持为正值。 */
  static ToolInvocation retryReadyTool(InMemoryHarnessStore store, UUID toolId) {
    return store.transaction(
        tx -> {
          ToolInvocation tool = tx.lockToolInvocation(toolId).orElseThrow();
          ToolInvocation updated = tool.retryReady(T3);
          tx.updateToolInvocations(List.of(updated));
          return updated;
        });
  }

  /** 将指定 tool 从 RUNNING 推进到 SUCCEEDED 并携带真实 result，但 result 尚未挂载。 */
  static ToolInvocation succeedTool(InMemoryHarnessStore store, UUID toolId) {
    return succeedTool(store, toolId, ToolEffectBatch.EMPTY);
  }

  /** 将指定 tool 从 RUNNING 推进到 SUCCEEDED，并原子携带真实 result 与 branch effects。 */
  static ToolInvocation succeedTool(
      InMemoryHarnessStore store, UUID toolId, ToolEffectBatch effects) {
    return store.transaction(
        tx -> {
          ToolInvocation tool = tx.lockToolInvocation(toolId).orElseThrow();
          ToolInvocation updated =
              tool.succeed(
                  new ToolResult(
                      tool.call().id(), List.of(new TextResultContent("real result")), false, "{}"),
                  effects,
                  T3);
          tx.updateToolInvocations(List.of(updated));
          return updated;
        });
  }

  /** 将 tool approval 标记为不需要（READY 配已决定的 neutral approval）。 */
  static ToolInvocation markApprovalNotRequired(InMemoryHarnessStore store, ToolBaseline baseline) {
    return store.transaction(
        tx -> {
          ToolInvocation tool = tx.lockToolInvocation(baseline.toolId()).orElseThrow();
          ToolInvocation updated = tool.markApprovalNotRequired(T3);
          tx.updateToolInvocations(List.of(updated));
          return updated;
        });
  }

  /** ROOT -&gt; TURN_START(INPUT) -&gt; USER -&gt; ASSISTANT -&gt; TURN_END(continueModel=true)。 */
  static ContinuationBaseline seedContinuationChain(
      InMemoryHarnessStore store, boolean headAtTurnEnd) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turnStartEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));
          tx.insertEntry(turnStartEntry(turnStartEntryId, sessionId, rootEntryId, T1, threadId));
          ThreadState thread =
              thread(threadId, sessionId, headAtTurnEnd ? turnStartEntryId : rootEntryId);
          tx.insertThread(thread);
          UUID userEntryId = tx.nextId();
          tx.insertEntry(userMessageEntry(userEntryId, sessionId, turnStartEntryId, T1));
          UUID assistantEntryId = tx.nextId();
          tx.insertEntry(assistantEntry(assistantEntryId, sessionId, userEntryId, T1));
          UUID turnEndEntryId = tx.nextId();
          tx.insertEntry(
              turnEndEntry(
                  turnEndEntryId, sessionId, assistantEntryId, T1, turnStartEntryId, true));
          if (headAtTurnEnd) {
            tx.updateThread(thread.advanceHead(turnEndEntryId, T1));
          }
          return new ContinuationBaseline(sessionId, rootEntryId, turnEndEntryId, threadId);
        });
  }

  /** 另一个 Session，自带 ROOT（跨 Session 所有权校验 fixture）。 */
  static UUID seedForeignRoot(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          tx.insertSession(session(sessionId));
          tx.insertEntry(rootEntry(rootEntryId, sessionId));
          return rootEntryId;
        });
  }

  /** 在同一 Session 内增加一条 Thread，指向现有 head Entry。 */
  static UUID seedThreadAt(InMemoryHarnessStore store, UUID headEntryId) {
    return store.transaction(
        tx -> {
          UUID id = tx.nextId();
          UUID sessionId = tx.loadEntryPath(headEntryId).root().sessionId();
          tx.insertThread(thread(id, sessionId, headEntryId));
          return id;
        });
  }

  /** 在同一 Session 内增加一条启用 YOLO 的 Thread，指向现有 head Entry。 */
  static UUID seedYoloThreadAt(InMemoryHarnessStore store, UUID headEntryId) {
    return store.transaction(
        tx -> {
          UUID id = tx.nextId();
          UUID sessionId = tx.loadEntryPath(headEntryId).root().sessionId();
          tx.insertThread(thread(id, sessionId, headEntryId, true));
          return id;
        });
  }

  /** 在 {@code parentEntryId} 下插入一个 TURN_START 子节点，并返回其 id。 */
  static UUID seedChildTurnStart(InMemoryHarnessStore store, UUID sessionId, UUID parentEntryId) {
    return store.transaction(
        tx -> {
          UUID id = tx.nextId();
          tx.insertEntry(turnStartEntry(id, sessionId, parentEntryId, T1, OWNER_THREAD_ID));
          return id;
        });
  }

  /**
   * 仅测试用的委托 store：其事务在 Thread 行被锁定的那一刻推进可变 {@code clock}， 因此测试可证明控制面在锁等待后才读取时间戳；使用锁前的 instant 会让行的
   * updatedAt 倒退。
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
   * 仅测试用的委托 store：其事务在任意 Work 行被锁定的那一刻推进可变 {@code clock}， 因此测试可证明 Stop 仅在整个 Work 预锁之后才读取时间戳（若使用锁前
   * instant 会让 Stop 事务写入的 updatedAt 倒退）。
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

  /** 在 thread 上插入一条 QUEUED command（必须是尚无 command 的全新 thread）。 */
  static void seedQueuedCommand(
      InMemoryHarnessStore store,
      UUID threadId,
      long sequence,
      ThreadCommandPayload payload,
      UUID idempotencyKey) {
    store.transaction(
        tx -> {
          tx.lockThread(threadId);
          tx.insertCommands(
              List.of(
                  new ThreadCommand(
                      threadId,
                      sequence,
                      payload,
                      idempotencyKey,
                      ThreadCommandPayloadJsonCodec.requestHash(payload),
                      null,
                      null,
                      null,
                      T1)));
          return null;
        });
  }

  /** 请求一条无 lease 的 THREAD Work 行（Resolver 邮箱的推测式围栏）。 */
  static void seedThreadWork(InMemoryHarnessStore store, UUID threadId) {
    store.transaction(
        tx -> {
          tx.lockThread(threadId).orElseThrow();
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, threadId), T0);
          return null;
        });
  }

  /** 请求一条无 lease 的 MODEL Work 行（在 Model 活跃期间被围栏）。 */
  static void seedModelWork(InMemoryHarnessStore store, UUID modelId) {
    store.transaction(
        tx -> {
          ModelInvocation model = tx.findModelInvocation(modelId).orElseThrow();
          tx.lockThread(model.threadId()).orElseThrow();
          tx.requestWork(new WorkTarget(WorkTargetType.MODEL, modelId), T0);
          return null;
        });
  }

  /** 为指定 invocation 请求一条无 lease 的 TOOL Work 行。 */
  static void seedToolWork(InMemoryHarnessStore store, UUID toolId) {
    store.transaction(
        tx -> {
          ToolInvocation tool = tx.findToolInvocation(toolId).orElseThrow();
          ModelInvocation model = tx.findModelInvocation(tool.modelInvocationId()).orElseThrow();
          tx.lockThread(model.threadId()).orElseThrow();
          tx.requestWork(new WorkTarget(WorkTargetType.TOOL, toolId), T0);
          return null;
        });
  }

  /** Claim THREAD Work 行，使其带有 active lease 而存在。 */
  static void seedClaimedThreadWork(InMemoryHarnessStore store, UUID threadId) {
    seedThreadWork(store, threadId);
    store.transaction(
        tx -> {
          tx.claimNextWork(WorkTargetType.THREAD, T0, "lease-1", T3);
          return null;
        });
  }

  static Session session(UUID id) {
    return new Session(id, "session-" + id, T0);
  }

  static Entry rootEntry(UUID id, UUID sessionId) {
    return new Entry(id, sessionId, null, new RootPayload(settings()), T0);
  }

  static Entry turnStartEntry(UUID id, UUID sessionId, UUID parentId, Instant createdAt) {
    return turnStartEntry(id, sessionId, parentId, createdAt, OWNER_THREAD_ID);
  }

  static Entry turnStartEntry(
      UUID id, UUID sessionId, UUID parentId, Instant createdAt, UUID ownerThreadId) {
    return new Entry(
        id,
        sessionId,
        parentId,
        new TurnStartPayload(
            TurnStartReason.INPUT, settings(), ownerThreadId, 100_000, 16_384, null),
        createdAt);
  }

  static Entry userMessageEntry(UUID id, UUID sessionId, UUID parentId, Instant createdAt) {
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
      UUID id, UUID sessionId, UUID parentId, Instant createdAt, String... toolCallIds) {
    List<AgentMessageContent> contents = new ArrayList<>();
    for (String toolCallId : toolCallIds) {
      contents.add(new ToolCallMessageContent(toolCallId, "bash", "bash", "{}"));
    }
    contents.add(new TextMessageContent("assistant reply"));
    GenerationStopReason stopReason =
        toolCallIds.length > 0 ? GenerationStopReason.COMPLETE : GenerationStopReason.COMPLETE;
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
      UUID id,
      UUID sessionId,
      UUID parentId,
      Instant createdAt,
      UUID turnStartEntryId,
      boolean continueModel) {
    return new Entry(
        id,
        sessionId,
        parentId,
        new TurnEndPayload(turnStartEntryId, TurnEndOutcome.COMPLETED, continueModel, null, null),
        createdAt);
  }

  static ThreadState thread(UUID id, UUID sessionId, UUID headEntryId) {
    return thread(id, sessionId, headEntryId, "main", false);
  }

  static ThreadState thread(UUID id, UUID sessionId, UUID headEntryId, boolean yoloEnabled) {
    return thread(id, sessionId, headEntryId, "main", yoloEnabled);
  }

  static ThreadState thread(
      UUID id, UUID sessionId, UUID headEntryId, String name, boolean yoloEnabled) {
    return new ThreadState(
        id, sessionId, headEntryId, CREATION_REQUEST_HASH, name, yoloEnabled, 1, 0, T0, T0);
  }

  static ThreadCommand withConsumedTurnStart(ThreadCommand command, UUID turnStartEntryId) {
    return new ThreadCommand(
        command.threadId(),
        command.sequence(),
        command.payload(),
        command.idempotencyKey(),
        command.requestHash(),
        turnStartEntryId,
        null,
        null,
        command.createdAt());
  }

  /** 使用给定的稳定 client id 与文本构造一条 USER_MESSAGE command。 */
  static NewThreadCommand userMessageCommand(UUID idempotencyKey, String text) {
    UserMessageCommandPayload payload = userMessagePayload(text);
    return new NewThreadCommand(payload, idempotencyKey);
  }

  /** 使用给定的稳定 client id 与文本构造一条 USER role 的 CUSTOM_MESSAGE command。 */
  static NewThreadCommand userCustomMessageCommand(UUID idempotencyKey, String text) {
    return new NewThreadCommand(
        new CustomMessageCommandPayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text)))),
        idempotencyKey);
  }

  /** 使用给定的稳定 client id 与文本构造一条 SYSTEM role 的 CUSTOM_MESSAGE command。 */
  static NewThreadCommand systemCustomMessageCommand(UUID idempotencyKey, String text) {
    return new NewThreadCommand(
        new CustomMessageCommandPayload(
            new AgentMessage(AgentMessageRole.SYSTEM, List.of(new TextMessageContent(text)))),
        idempotencyKey);
  }

  static UserMessageCommandPayload userMessagePayload(String text) {
    return new UserMessageCommandPayload(
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text))));
  }

  static ModelInvocation modelInvocation(
      UUID id, UUID threadId, UUID turnStartEntryId, UUID requestHeadEntryId, Instant createdAt) {
    return new ModelInvocation(
        id,
        threadId,
        turnStartEntryId,
        requestHeadEntryId,
        modelRequest(),
        ModelInvocationStatus.READY,
        0,
        null,
        null,
        null,
        null,
        List.of(),
        createdAt,
        createdAt);
  }

  /**
   * READY model invocation with an explicit frozen request（live tool baseline 需要 tooled binding）。
   */
  static ModelInvocation modelInvocationWithRequest(
      UUID id,
      UUID threadId,
      UUID turnStartEntryId,
      UUID requestHeadEntryId,
      ModelRequestSpec requestSpec,
      Instant createdAt) {
    return new ModelInvocation(
        id,
        threadId,
        turnStartEntryId,
        requestHeadEntryId,
        requestSpec,
        ModelInvocationStatus.READY,
        0,
        null,
        null,
        null,
        null,
        List.of(),
        createdAt,
        createdAt);
  }

  /** live tool baseline 的 tooled 冻结请求：按 {@code toolNames} 构造 host bindings。 */
  static ModelRequestSpec tooledModelRequest(List<String> toolNames) {
    List<ToolBinding> bindings = new ArrayList<>();
    for (String name : toolNames) {
      bindings.add(
          new ToolBinding(
              toolDefinition(name), new ContributorBinding("core", name, List.of()), false, null));
    }
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 1L),
        modelDescriptor(),
        new ModelVariant("v1"),
        1024,
        List.of(),
        bindings,
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  /** 由同一 frozen request + ProviderResponse 经 HistoryPayloadMapper 精确生成 live assistant Entry。 */
  static Entry mappedAssistantEntry(
      UUID id,
      UUID sessionId,
      UUID parentId,
      Instant createdAt,
      ModelRequestSpec requestSpec,
      ProviderResponse response) {
    return new Entry(
        id,
        sessionId,
        parentId,
        new HistoryPayloadMapper().assistantPayload(response, requestSpec.toolBindings()),
        createdAt);
  }

  static ToolInvocation toolInvocation(
      UUID id,
      UUID modelInvocationId,
      UUID assistantEntryId,
      int callIndex,
      String toolCallId,
      Instant createdAt) {
    return new ToolInvocation(
        id,
        modelInvocationId,
        assistantEntryId,
        callIndex,
        new ToolCall(toolCallId, "bash", "{}"),
        hostBinding(),
        ToolInvocationStatus.READY,
        0,
        null,
        null,
        null,
        createdAt,
        createdAt);
  }

  static BranchSettings settings() {
    return new BranchSettings("agent", new ModelSelection("provider", "model", "v1"));
  }

  static ModelRequestSpec modelRequest() {
    ProviderRequest provider = providerRequest();
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        new UUID(0L, 1L),
        provider.model(),
        provider.variant(),
        1024,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        provider.cacheControl());
  }

  static ProviderResponse responseWithToolCalls(String... toolCallIds) {
    List<ProviderToolCall> calls = new ArrayList<>(toolCallIds.length);
    for (String toolCallId : toolCallIds) {
      calls.add(new ProviderToolCall(toolCallId, "bash", "{}"));
    }
    return new ProviderResponse(
        "", "", calls, GenerationStopReason.COMPLETE, usage(), cost(), null, null, null);
  }

  static ModelInvocationError modelError() {
    return new ModelInvocationError(ProviderErrorKind.TRANSIENT, "model boom");
  }

  static ToolInvocationError toolError() {
    return new ToolInvocationError("CANCELLED", "cancelled");
  }

  private static AssistantMessageMetadata assistantMetadata(GenerationStopReason stopReason) {
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
        new ModelVariant("v1"),
        1024,
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  private static ModelDescriptor modelDescriptor() {
    return new ModelDescriptor(
        "provider",
        "model",
        "model",
        Set.of(ModelInputModality.TEXT),
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
            BigDecimal.ZERO));
  }

  private static ToolBinding hostBinding() {
    return new ToolBinding(
        toolDefinition("bash"), new ContributorBinding("core", "bash", List.of()), false, null);
  }

  private static ToolDescriptor toolDescriptor(String name) {
    return new ToolDescriptor(
        name,
        "1.0",
        "description of " + name,
        name,
        new InputSchema("arguments", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
  }

  private static AgentToolDefinition toolDefinition(String name) {
    return new AgentToolDefinition(
        new AgentToolId("test." + name.replace('_', '-').toLowerCase(Locale.ROOT)),
        toolDescriptor(name),
        ToolVisibility.SELECTABLE);
  }

  /** 在 in-memory store 上运行 void 事务体的辅助方法。 */
  static void inTransaction(InMemoryHarnessStore store, Consumer<HarnessStore.Transaction> body) {
    store.transaction(
        tx -> {
          body.accept(tx);
          return null;
        });
  }

  /** Stop 结果为 STOPPED（非 replay，且落盘了 STOPPED TURN_END）。 */
  static void assertStopped(StopResult result) {
    assertFalse(result.replayed());
    assertNotNull(result.stoppedTurnEndEntryId());
  }

  /** Stop 结果为 IDLE（非 replay，未落盘任何 TURN_END）。 */
  static void assertIdle(StopResult result) {
    assertFalse(result.replayed());
    assertNull(result.stoppedTurnEndEntryId());
  }

  /** Stop 结果为 replay（幂等重放既有 STOPPED 事实）。 */
  static void assertReplayed(StopResult result) {
    assertTrue(result.replayed());
  }
}
