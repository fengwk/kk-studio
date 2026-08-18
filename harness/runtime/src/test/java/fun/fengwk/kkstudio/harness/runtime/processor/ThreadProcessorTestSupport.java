package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.fail;

import fun.fengwk.kkstudio.harness.runtime.EnvironmentBindings;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionConfig;
import fun.fengwk.kkstudio.harness.runtime.compaction.CompactionPreparation;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.CompactionRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.PluginStateAccess;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.PluginToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolEffectBatch;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.port.TurnResolver;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * ThreadProcessor 测试共享基座：InMemoryHarnessStore + fake TurnResolver + 可变时钟 + 种子与断言 helper。
 *
 * <p>每条链按 store 约束原子种子：Session + ROOT +（TURN_START + USER/ASSISTANT/TURN_END）+
 * Thread；ModelInvocation 只能以 READY/attempt=0 插入且 basis 必须等于 Thread 当前 head，terminal 状态通过共享
 * transition 逐级推进； ToolInvocation 只能以 READY/attempt=0/approval=null 插入，call/binding 由基座固定绑定 bash
 * tool。
 */
final class ThreadProcessorTestSupport {

  static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  static final UUID OWNER_THREAD_ID = new UUID(0L, 1L);
  static final EnvironmentBinding ENV_ID = EnvironmentBindings.binding("env-1");
  static final ProcessorLeaseConfig LEASE_CONFIG =
      new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(5));
  static final Duration RESOLVE_FAILURE_DELAY = Duration.ofSeconds(7);

  /** 测试请求统一的冻结上下文窗口。 */
  static final int CONTEXT_WINDOW = 100_000;

  /** 测试用默认压缩配置（开启、16_384 预留、20_000 保留）。 */
  static final CompactionConfig COMPACTION_CONFIG = new CompactionConfig(true, 16_384, 20_000);

  private ThreadProcessorTestSupport() {}

  // -----------------------------------------------------------------------------------------------
  // ids / fixtures（标识 / 测试基座）
  // -----------------------------------------------------------------------------------------------

  record Baseline(UUID sessionId, UUID rootEntryId, UUID threadId) {}

  record OpenTurnBaseline(
      UUID sessionId, UUID rootEntryId, UUID turnStartEntryId, UUID userEntryId, UUID threadId) {}

  record ClosedTurnBaseline(
      UUID sessionId,
      UUID rootEntryId,
      UUID turnStartEntryId,
      UUID userEntryId,
      UUID assistantEntryId,
      UUID turnEndEntryId,
      UUID threadId) {}

  record HistoricalBaseline(
      UUID sessionId,
      UUID rootEntryId,
      UUID turnStartEntryId,
      UUID userEntryId,
      UUID assistantEntryId,
      UUID headEntryId,
      UUID threadId) {}

  /** Session + ROOT + Thread（head 指向 ROOT）。 */
  static Baseline seedBaseline(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.insertSession(new Session(sessionId, NOW));
          tx.insertEntry(
              new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), NOW));
          tx.insertThread(new ThreadState(threadId, rootEntryId, false, 1L, 0L, NOW, NOW));
          return new Baseline(sessionId, rootEntryId, threadId);
        });
  }

  /** Session + ROOT + TURN_START(INPUT) + USER；Thread head 指向 USER。 */
  static OpenTurnBaseline seedOpenInputTurn(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turnStartEntryId = tx.nextId();
          UUID userEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.insertSession(new Session(sessionId, NOW));
          tx.insertEntry(
              new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), NOW));
          tx.insertEntry(
              new Entry(
                  turnStartEntryId, sessionId, rootEntryId, resolvedInputTurnStart(threadId), NOW));
          tx.insertEntry(
              new Entry(
                  userEntryId, sessionId, turnStartEntryId, userMessagePayload("hello"), NOW));
          tx.insertThread(new ThreadState(threadId, userEntryId, false, 1L, 0L, NOW, NOW));
          return new OpenTurnBaseline(
              sessionId, rootEntryId, turnStartEntryId, userEntryId, threadId);
        });
  }

  /** Session + ROOT + 完整关闭 Turn（ASSISTANT 无 tool call + TURN_END COMPLETED）。 */
  static ClosedTurnBaseline seedClosedTurn(InMemoryHarnessStore store, boolean continueModel) {
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turnStartEntryId = tx.nextId();
          UUID userEntryId = tx.nextId();
          UUID assistantEntryId = tx.nextId();
          UUID turnEndEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.insertSession(new Session(sessionId, NOW));
          tx.insertEntry(
              new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), NOW));
          tx.insertEntry(
              new Entry(
                  turnStartEntryId, sessionId, rootEntryId, resolvedInputTurnStart(threadId), NOW));
          tx.insertEntry(
              new Entry(
                  userEntryId, sessionId, turnStartEntryId, userMessagePayload("hello"), NOW));
          tx.insertEntry(
              new Entry(
                  assistantEntryId, sessionId, userEntryId, assistantPayload(List.of()), NOW));
          tx.insertEntry(
              new Entry(
                  turnEndEntryId,
                  sessionId,
                  assistantEntryId,
                  new TurnEndPayload(
                      turnStartEntryId, TurnEndOutcome.COMPLETED, continueModel, null, null),
                  NOW));
          tx.insertThread(new ThreadState(threadId, turnEndEntryId, false, 1L, 0L, NOW, NOW));
          return new ClosedTurnBaseline(
              sessionId,
              rootEntryId,
              turnStartEntryId,
              userEntryId,
              assistantEntryId,
              turnEndEntryId,
              threadId);
        });
  }

  /**
   * 历史 open Turn：TURN_START(INPUT) + USER + ASSISTANT（{@code toolCalls} 个 bash tool call）；head 指向
   * {@code presentResults} 个已有 ToolResult 之后的位置（0 = assistant，&gt;0 = 最后一个真实结果）。
   */
  static HistoricalBaseline seedHistoricalOpenTurn(
      InMemoryHarnessStore store, int toolCalls, int presentResults) {
    if (presentResults < 0 || presentResults > toolCalls) {
      throw new IllegalArgumentException("presentResults must be within toolCalls");
    }
    return store.transaction(
        tx -> {
          UUID sessionId = tx.nextId();
          UUID rootEntryId = tx.nextId();
          UUID turnStartEntryId = tx.nextId();
          UUID userEntryId = tx.nextId();
          UUID assistantEntryId = tx.nextId();
          UUID threadId = tx.nextId();
          tx.insertSession(new Session(sessionId, NOW));
          tx.insertEntry(
              new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), NOW));
          tx.insertEntry(
              new Entry(
                  turnStartEntryId, sessionId, rootEntryId, resolvedInputTurnStart(threadId), NOW));
          tx.insertEntry(
              new Entry(
                  userEntryId, sessionId, turnStartEntryId, userMessagePayload("hello"), NOW));
          List<String> callIds = new ArrayList<>();
          for (int i = 0; i < toolCalls; i++) {
            callIds.add("call-" + i);
          }
          tx.insertEntry(
              new Entry(assistantEntryId, sessionId, userEntryId, assistantPayload(callIds), NOW));
          UUID headEntryId = assistantEntryId;
          for (int ordinal = 0; ordinal < presentResults; ordinal++) {
            UUID resultId = tx.nextId();
            tx.insertEntry(
                new Entry(
                    resultId,
                    sessionId,
                    headEntryId,
                    realToolResultPayload(assistantEntryId, ordinal, "call-" + ordinal),
                    NOW));
            headEntryId = resultId;
          }
          tx.insertThread(new ThreadState(threadId, headEntryId, false, 1L, 0L, NOW, NOW));
          return new HistoricalBaseline(
              sessionId,
              rootEntryId,
              turnStartEntryId,
              userEntryId,
              assistantEntryId,
              headEntryId,
              threadId);
        });
  }

  /** 在已有 Session 上新建第二个 Thread，head 指向共享的历史 assistant Entry。 */
  static HistoricalBaseline seedSecondThreadAtHistoricalAssistant(
      InMemoryHarnessStore store, UUID sessionId, UUID assistantEntryId) {
    return store.transaction(
        tx -> {
          UUID threadId = tx.nextId();
          tx.insertThread(new ThreadState(threadId, assistantEntryId, false, 1L, 0L, NOW, NOW));
          return new HistoricalBaseline(
              sessionId,
              TestIds.id(1_000_000L),
              TestIds.id(1_000_001L),
              TestIds.id(1_000_002L),
              assistantEntryId,
              assistantEntryId,
              threadId);
        });
  }

  /** 插入一条 QUEUED Command（sequence 取自 Thread.nextCommandSequence）并推进 Thread 序列。 */
  static UUID seedCommand(InMemoryHarnessStore store, UUID threadId, ThreadCommandPayload payload) {
    return store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(threadId).orElseThrow();
          UUID clientCommandId = tx.nextId();
          long sequence = thread.nextCommandSequence();
          tx.insertCommands(
              List.of(
                  new ThreadCommand(
                      threadId,
                      sequence,
                      payload,
                      clientCommandId,
                      ThreadCommandPayloadJsonCodec.requestHash(payload),
                      null,
                      null,
                      NOW)));
          tx.updateThread(thread.reserveCommandSequences(1, NOW));
          return clientCommandId;
        });
  }

  /** 在一条已存在 Thread 上请求 THREAD Work（模拟 enqueue 侧）。 */
  static void requestThreadWork(InMemoryHarnessStore store, UUID threadId) {
    store.transaction(
        tx -> {
          tx.lockThread(threadId);
          tx.requestWork(new WorkTarget(WorkTargetType.THREAD, threadId), NOW);
          return null;
        });
  }

  /** 原子种子一条完整 Tool 链：open Turn + ASSISTANT(calls) + terminal ModelInvocation + ToolInvocation。 */
  record ToolChain(
      OpenTurnBaseline turn,
      UUID assistantEntryId,
      UUID modelInvocationId,
      List<UUID> toolInvocationIds) {}

  static ToolChain seedToolChain(
      InMemoryHarnessStore store,
      List<String> callIds,
      ModelInvocationStatus modelStatus,
      List<ToolInvocationStatus> toolStatuses) {
    if (toolStatuses.size() != callIds.size()) {
      throw new IllegalArgumentException("toolStatuses must match callIds");
    }
    OpenTurnBaseline turn = seedOpenInputTurn(store);
    ModelRequestSpec request = tooledRequest(List.of("bash"));
    ProviderResponse response = successResponse(callIds, "bash");
    UUID modelId =
        seedModelInvocation(
            store,
            turn.threadId(),
            turn.turnStartEntryId(),
            turn.userEntryId(),
            modelStatus,
            request,
            response,
            null);
    // live attached fixture：assistant Entry 必须由同一 frozen request + ProviderResponse 经 mapper 生成，
    // 与 validateAttached 的完整 payload 校验保持一致。
    UUID assistantEntryId =
        insertAssistantPayload(
            store,
            turn,
            new HistoryPayloadMapper().assistantPayload(response, request.toolBindings()));
    if (modelStatus == ModelInvocationStatus.SUCCEEDED) {
      transitionModel(store, modelId, m -> m.attachResultEntry(assistantEntryId, NOW));
    }
    List<UUID> toolIds = new ArrayList<>();
    for (int ordinal = 0; ordinal < callIds.size(); ordinal++) {
      toolIds.add(
          seedToolInvocation(
              store,
              modelId,
              assistantEntryId,
              ordinal,
              callIds.get(ordinal),
              toolStatuses.get(ordinal)));
    }
    return new ToolChain(turn, assistantEntryId, modelId, toolIds);
  }

  /** 在 open Turn 的 head 后插入 ASSISTANT(calls) Entry 并推进 Thread head（seedToolChain 的构成片段）。 */
  static UUID insertAssistantWithCalls(
      InMemoryHarnessStore store, OpenTurnBaseline turn, List<String> callIds) {
    return store.transaction(
        tx -> {
          tx.lockThread(turn.threadId());
          UUID id = tx.nextId();
          tx.insertEntry(
              new Entry(id, turn.sessionId(), turn.userEntryId(), assistantPayload(callIds), NOW));
          tx.updateThread(tx.findThread(turn.threadId()).orElseThrow().advanceHead(id, NOW));
          return id;
        });
  }

  /**
   * 在 open Turn 的 head 后插入指定 payload 的 ASSISTANT Entry 并推进 Thread head（live attached fixture 专用）。
   */
  static UUID insertAssistantPayload(
      InMemoryHarnessStore store, OpenTurnBaseline turn, EntryPayload payload) {
    return store.transaction(
        tx -> {
          tx.lockThread(turn.threadId());
          UUID id = tx.nextId();
          tx.insertEntry(new Entry(id, turn.sessionId(), turn.userEntryId(), payload, NOW));
          tx.updateThread(tx.findThread(turn.threadId()).orElseThrow().advanceHead(id, NOW));
          return id;
        });
  }

  /**
   * 插入 READY ModelInvocation（basis 必须等于 Thread 当前 head）并把状态推进到 {@code status}（terminal 且
   * resultEntryId 仍 null）。
   */
  static UUID seedModelInvocation(
      InMemoryHarnessStore store,
      UUID threadId,
      UUID turnStartEntryId,
      UUID basisEntryId,
      ModelInvocationStatus status,
      ModelRequestSpec request,
      ProviderResponse response,
      ModelInvocationError error) {
    UUID modelId =
        store.transaction(
            tx -> {
              tx.lockThread(threadId);
              UUID id = tx.nextId();
              tx.insertModelInvocation(
                  new ModelInvocation(
                      id,
                      threadId,
                      turnStartEntryId,
                      basisEntryId,
                      request,
                      ModelInvocationStatus.READY,
                      0,
                      null,
                      null,
                      null,
                      null,
                      List.of(),
                      NOW,
                      NOW));
              return id;
            });
    switch (status) {
      case READY -> {}
      case SUCCEEDED -> {
        transitionModel(store, modelId, m -> m.beginDispatch(NOW));
        transitionModel(store, modelId, m -> m.markRunning(NOW));
        transitionModel(store, modelId, m -> m.succeed(response, NOW));
      }
      case FAILED -> transitionModel(store, modelId, m -> m.fail(error, NOW));
      case CANCELLED -> transitionModel(store, modelId, m -> m.cancel(error, NOW));
      case UNKNOWN -> {
        transitionModel(store, modelId, m -> m.beginDispatch(NOW));
        transitionModel(store, modelId, m -> m.markRunning(NOW));
        transitionModel(store, modelId, m -> m.unknown(error, NOW));
      }
      default -> throw new IllegalArgumentException("unsupported seeded status " + status);
    }
    return modelId;
  }

  /** 插入 READY ToolInvocation 并把状态推进到 {@code status}。 */
  static UUID seedToolInvocation(
      InMemoryHarnessStore store,
      UUID modelInvocationId,
      UUID assistantEntryId,
      int ordinal,
      String callId,
      ToolInvocationStatus status) {
    UUID toolId =
        store.transaction(
            tx -> {
              UUID id = tx.nextId();
              ToolInvocationRequest request = toolRequest(callId);
              tx.insertToolInvocations(
                  List.of(
                      new ToolInvocation(
                          id,
                          modelInvocationId,
                          assistantEntryId,
                          ordinal,
                          request.call(),
                          request.binding(),
                          ToolInvocationStatus.READY,
                          0,
                          null,
                          null,
                          null,
                          NOW,
                          NOW)));
              return id;
            });
    switch (status) {
      case READY -> {}
      case SUCCEEDED -> {
        transitionTool(store, toolId, t -> t.markApprovalNotRequired(NOW));
        transitionTool(store, toolId, t -> t.beginDispatch(NOW));
        transitionTool(store, toolId, t -> t.markRunning(NOW));
        transitionTool(store, toolId, t -> t.succeed(successToolResult(callId), NOW));
      }
      case FAILED -> transitionTool(
          store, toolId, t -> t.fail(new ToolInvocationError("FAILED", "tool failed"), NOW));
      case CANCELLED -> transitionTool(
          store,
          toolId,
          t -> t.cancel(new ToolInvocationError("CANCELLED", "tool cancelled"), NOW));
      case UNKNOWN -> {
        transitionTool(store, toolId, t -> t.markApprovalNotRequired(NOW));
        transitionTool(store, toolId, t -> t.beginDispatch(NOW));
        transitionTool(store, toolId, t -> t.markRunning(NOW));
        transitionTool(
            store, toolId, t -> t.unknown(new ToolInvocationError("UNKNOWN", "tool unknown"), NOW));
      }
      default -> throw new IllegalArgumentException("unsupported seeded status " + status);
    }
    return toolId;
  }

  static ClaimedWork claimThreadWork(InMemoryHarnessStore store, UUID threadId) {
    return claimThreadWork(store, threadId, NOW);
  }

  static ClaimedWork claimThreadWork(InMemoryHarnessStore store, UUID threadId, Instant now) {
    return claimThreadWork(store, threadId, now, now.plusSeconds(60));
  }

  static ClaimedWork claimThreadWork(
      InMemoryHarnessStore store, UUID threadId, Instant now, Instant leaseUntil) {
    return store
        .transaction(
            tx -> tx.claimNextWork(WorkTargetType.THREAD, now, "token-" + threadId, leaseUntil))
        .orElseThrow();
  }

  // -----------------------------------------------------------------------------------------------
  // transitions / reads（状态转换 / 读取）
  // -----------------------------------------------------------------------------------------------

  static void transitionModel(
      InMemoryHarnessStore store, UUID modelId, Function<ModelInvocation, ModelInvocation> f) {
    store.transaction(
        tx -> {
          ModelInvocation model = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(f.apply(model));
          return null;
        });
  }

  static void transitionTool(
      InMemoryHarnessStore store, UUID toolId, Function<ToolInvocation, ToolInvocation> f) {
    store.transaction(
        tx -> {
          ToolInvocation tool = tx.lockToolInvocation(toolId).orElseThrow();
          tx.updateToolInvocations(List.of(f.apply(tool)));
          return null;
        });
  }

  /** 仅推进 Thread 的 durable 时间/revision，保持 head 与 policy 不变。 */
  static void touchThreadTimestamp(InMemoryHarnessStore store, UUID threadId, Instant updatedAt) {
    store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(threadId).orElseThrow();
          tx.updateThread(thread.touchRevision(updatedAt));
          return null;
        });
  }

  /** 通过 Store validator 合法推进 ModelInvocation.updatedAt，保持 invocation 事实不变。 */
  static void touchModelTimestamp(InMemoryHarnessStore store, UUID modelId, Instant updatedAt) {
    transitionModel(
        store,
        modelId,
        model ->
            new ModelInvocation(
                model.id(),
                model.threadId(),
                model.turnStartEntryId(),
                model.basisHeadEntryId(),
                model.request(),
                model.status(),
                model.attempt(),
                model.streamCheckpoint(),
                model.result(),
                model.error(),
                model.resultEntryId(),
                model.failedAttempts(),
                model.createdAt(),
                updatedAt));
  }

  /** 通过 Store validator 合法推进 ToolInvocation.updatedAt，保持 invocation 事实不变。 */
  static void touchToolTimestamp(InMemoryHarnessStore store, UUID toolId, Instant updatedAt) {
    transitionTool(
        store,
        toolId,
        tool ->
            new ToolInvocation(
                tool.id(),
                tool.modelInvocationId(),
                tool.assistantEntryId(),
                tool.ordinal(),
                tool.call(),
                tool.binding(),
                tool.status(),
                tool.attempt(),
                tool.approval(),
                tool.result(),
                tool.effects(),
                tool.error(),
                tool.createdAt(),
                updatedAt));
  }

  /** 把 READY ToolInvocation 逐级推进到 SUCCEEDED 并挂载自定义结果（用于 mapper 回滚类测试）。 */
  static void succeedToolWith(InMemoryHarnessStore store, UUID toolId, ToolResult result) {
    succeedToolWith(store, toolId, result, ToolEffectBatch.EMPTY);
  }

  /** 把 READY ToolInvocation 逐级推进到 SUCCEEDED，并原子附带自定义结果与 effects。 */
  static void succeedToolWith(
      InMemoryHarnessStore store, UUID toolId, ToolResult result, ToolEffectBatch effects) {
    transitionTool(store, toolId, t -> t.markApprovalNotRequired(NOW));
    transitionTool(store, toolId, t -> t.beginDispatch(NOW));
    transitionTool(store, toolId, t -> t.markRunning(NOW));
    transitionTool(store, toolId, t -> t.succeed(result, effects, NOW));
  }

  static ThreadState thread(InMemoryHarnessStore store, UUID threadId) {
    return store.transaction(tx -> tx.findThread(threadId)).orElseThrow();
  }

  static EntryPath path(InMemoryHarnessStore store, UUID threadId) {
    return store.transaction(
        tx -> tx.loadEntryPath(tx.findThread(threadId).orElseThrow().headEntryId()));
  }

  static Entry entry(InMemoryHarnessStore store, UUID entryId) {
    return store.transaction(tx -> tx.findEntry(entryId)).orElseThrow();
  }

  static ModelInvocation model(InMemoryHarnessStore store, UUID modelId) {
    return store.transaction(tx -> tx.findModelInvocation(modelId)).orElseThrow();
  }

  static ToolInvocation tool(InMemoryHarnessStore store, UUID toolId) {
    return store.transaction(tx -> tx.findToolInvocation(toolId)).orElseThrow();
  }

  static List<ToolInvocation> toolsByAssistant(InMemoryHarnessStore store, UUID assistantEntryId) {
    return store.transaction(tx -> tx.loadToolInvocationsByAssistantEntryId(assistantEntryId));
  }

  static ThreadCommand command(InMemoryHarnessStore store, UUID threadId, UUID clientCommandId) {
    return store
        .transaction(tx -> tx.findCommandByClientId(threadId, clientCommandId))
        .orElseThrow();
  }

  static Work work(InMemoryHarnessStore store, WorkTarget target) {
    return store.transaction(tx -> tx.findWork(target)).orElse(null);
  }

  // -----------------------------------------------------------------------------------------------
  // payload builders（payload 构造器）
  // -----------------------------------------------------------------------------------------------

  static BranchSettings branchSettings() {
    return new BranchSettings(
        ENV_ID, "agent", new ModelSelection("provider", "model", "v1"), List.of());
  }

  /** Resolver 成功后的 INPUT TurnStart：owner 为创建 Thread，contextWindow 已冻结。 */
  static TurnStartPayload resolvedInputTurnStart(UUID ownerThreadId) {
    return new TurnStartPayload(
        TurnStartReason.INPUT, branchSettings(), ownerThreadId, CONTEXT_WINDOW);
  }

  /** Resolver 成功后的 COMPACTION TurnStart：owner 为创建 Thread，contextWindow 已冻结。 */
  static TurnStartPayload resolvedCompactionTurnStart(UUID ownerThreadId) {
    return new TurnStartPayload(
        TurnStartReason.COMPACTION, branchSettings(), ownerThreadId, CONTEXT_WINDOW);
  }

  static EntryPayload userMessagePayload(String text) {
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text))), null, null);
  }

  /** ASSISTANT MESSAGE payload；{@code callIds} 按 ordinal 生成 bash tool call。 */
  static EntryPayload assistantPayload(List<String> callIds) {
    List<AgentMessageContent> contents = new ArrayList<>();
    for (int i = 0; i < callIds.size(); i++) {
      contents.add(new ToolCallMessageContent(callIds.get(i), "bash", "bash", "{}"));
    }
    contents.add(new TextMessageContent("assistant reply"));
    GenerationStopReason stopReason =
        callIds.isEmpty() ? GenerationStopReason.COMPLETE : GenerationStopReason.COMPLETE;
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, contents),
        assistantMetadata(stopReason),
        null);
  }

  /** 真实 ToolResult MESSAGE payload（非 synthetic，status SUCCEEDED）。 */
  static EntryPayload realToolResultPayload(UUID assistantEntryId, int ordinal, String callId) {
    ToolResultMessageContent content =
        new ToolResultMessageContent(
            callId, "bash", "bash", List.of(new TextMessageContent("ok")), false, "{}");
    ToolResultMetadata metadata =
        new ToolResultMetadata(
            assistantEntryId, callId, ordinal, ToolResultStatus.SUCCEEDED, false, null);
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.TOOL, List.of(content)), null, metadata);
  }

  static ModelRequestSpec tooledRequest(List<String> toolNames) {
    List<ToolBinding> bindings = new ArrayList<>();
    for (String name : toolNames) {
      bindings.add(platformBinding(name));
    }
    return requestWithBindings(bindings);
  }

  static ModelRequestSpec plainRequest() {
    return tooledRequest(List.of());
  }

  static ModelRequestSpec requestWithBindings(List<ToolBinding> bindings) {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        modelDescriptor("provider", "model"),
        new ModelVariant("v1", null, null, null, null, null, null, List.of(), null),
        List.of(),
        bindings,
        List.of(),
        List.of(),
        ProviderCacheControl.none(),
        null);
  }

  /** 按 candidate BranchSettings 构造机械一致的 Resolved spec（model / variant / tools）。 */
  static ModelRequestSpec requestFor(BranchSettings settings) {
    List<ToolBinding> bindings = new ArrayList<>();
    for (String name : settings.activeTools()) {
      bindings.add(platformBinding(name));
    }
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        modelDescriptor(settings.model().providerName(), settings.model().modelName()),
        new ModelVariant(
            settings.model().variant(), null, null, null, null, null, null, List.of(), null),
        List.of(),
        bindings,
        List.of(),
        List.of(),
        ProviderCacheControl.none(),
        null);
  }

  static ToolInvocationRequest toolRequest(String callId) {
    return new ToolInvocationRequest(new ToolCall(callId, "bash", "{}"), platformBinding("bash"));
  }

  /**
   * 按冻结 preparation 构造机械一致的压缩 Resolved 请求（零 tool/skill、零 provider tools、缓存 none、
   * contextWindow/tokensBefore/ids 与 preparation 逐字段一致）。
   */
  static ModelRequestSpec compactionRequest(CompactionPreparation preparation) {
    return new ModelRequestSpec(
        ProviderType.OPENAI,
        modelDescriptor("provider", "model"),
        new ModelVariant("v1", null, null, null, null, null, null, List.of(), null),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        ProviderCacheControl.none(),
        new CompactionRequest(
            preparation.phase(),
            preparation.trigger(),
            preparation.tokensBefore(),
            preparation.firstKeptEntryId(),
            preparation.cutEntryId(),
            preparation.turnPrefixStartEntryId()));
  }

  /**
   * 种子一条压缩可触发状态：关闭的 INPUT turn（ASSISTANT 携带 {@code usage}）+ SUCCEEDED invocation（resultEntryId 挂上）。
   */
  static ClosedTurnBaseline seedCompactionReadyClosedTurn(
      InMemoryHarnessStore store, ModelUsage usage) {
    return seedCompactionReadyClosedTurn(store, usage, false);
  }

  static ClosedTurnBaseline seedCompactionReadyClosedTurn(
      InMemoryHarnessStore store, ModelUsage usage, boolean continueModel) {
    // 形状：turn1（短消息，作为可摘要历史）+ turn2（长 USER 消息 + 带 usage 的短 ASSISTANT）。
    // planner 从尾部累计：turn2 的 ASSISTANT 远小于 keepRecentTokens，长 USER 处越过 -> cut 落在 turn2 USER（非切分
    // FULL）。
    UUID[] modelIdHolder = new UUID[1];
    ClosedTurnBaseline baseline =
        store.transaction(
            tx -> {
              UUID sessionId = tx.nextId();
              UUID rootEntryId = tx.nextId();
              UUID turnStartEntryId = tx.nextId(); // turn1 TURN_START
              UUID userEntryId = tx.nextId(); // turn1 USER
              UUID assistantEntryId = tx.nextId(); // turn1 ASSISTANT
              UUID turnEndEntryId = tx.nextId(); // turn1 TURN_END
              UUID secondTurnStartId = tx.nextId(); // turn2 TURN_START
              UUID secondUserEntryId = tx.nextId(); // turn2 USER（长消息）
              UUID secondAssistantEntryId = tx.nextId(); // turn2 ASSISTANT（携带 usage）
              UUID secondTurnEndId = tx.nextId(); // turn2 TURN_END
              UUID threadId = tx.nextId();
              tx.insertSession(new Session(sessionId, NOW));
              tx.insertEntry(
                  new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), NOW));
              // turn1：短消息。
              tx.insertEntry(
                  new Entry(
                      turnStartEntryId,
                      sessionId,
                      rootEntryId,
                      resolvedInputTurnStart(threadId),
                      NOW));
              tx.insertEntry(
                  new Entry(
                      userEntryId, sessionId, turnStartEntryId, userMessagePayload("hello"), NOW));
              tx.insertEntry(
                  new Entry(
                      assistantEntryId,
                      sessionId,
                      userEntryId,
                      new MessagePayload(
                          new AgentMessage(
                              AgentMessageRole.ASSISTANT,
                              List.of(new TextMessageContent("assistant reply"))),
                          new AssistantMessageMetadata(
                              GenerationStopReason.COMPLETE, usage(), cost()),
                          null),
                      NOW));
              tx.insertEntry(
                  new Entry(
                      turnEndEntryId,
                      sessionId,
                      assistantEntryId,
                      new TurnEndPayload(
                          turnStartEntryId, TurnEndOutcome.COMPLETED, false, null, null),
                      NOW));
              // turn2：长 USER + 短 ASSISTANT（usage 挂这里）。
              tx.insertEntry(
                  new Entry(
                      secondTurnStartId,
                      sessionId,
                      turnEndEntryId,
                      resolvedInputTurnStart(threadId),
                      NOW));
              tx.insertEntry(
                  new Entry(
                      secondUserEntryId,
                      sessionId,
                      secondTurnStartId,
                      new MessagePayload(
                          new AgentMessage(
                              AgentMessageRole.USER,
                              List.of(new TextMessageContent(compactionUserText()))),
                          null,
                          null),
                      NOW));
              // Thread head 停在 turn2 USER：invocation 的 basis 必须是插入时刻的 head（与真实流程一致）。
              tx.insertThread(
                  new ThreadState(threadId, secondUserEntryId, false, 1L, 0L, NOW, NOW));
              modelIdHolder[0] = tx.nextId();
              tx.insertModelInvocation(
                  new ModelInvocation(
                      modelIdHolder[0],
                      threadId,
                      secondTurnStartId,
                      secondUserEntryId,
                      plainRequest(),
                      ModelInvocationStatus.READY,
                      0,
                      null,
                      null,
                      null,
                      null,
                      List.of(),
                      NOW,
                      NOW));
              tx.insertEntry(
                  new Entry(
                      secondAssistantEntryId,
                      sessionId,
                      secondUserEntryId,
                      new MessagePayload(
                          new AgentMessage(
                              AgentMessageRole.ASSISTANT,
                              List.of(new TextMessageContent("assistant reply"))),
                          new AssistantMessageMetadata(
                              GenerationStopReason.COMPLETE, usage, cost()),
                          null),
                      NOW));
              tx.insertEntry(
                  new Entry(
                      secondTurnEndId,
                      sessionId,
                      secondAssistantEntryId,
                      new TurnEndPayload(
                          secondTurnStartId, TurnEndOutcome.COMPLETED, continueModel, null, null),
                      NOW));
              tx.updateThread(
                  tx.findThread(threadId).orElseThrow().advanceHead(secondTurnEndId, NOW));
              return new ClosedTurnBaseline(
                  sessionId,
                  rootEntryId,
                  secondTurnStartId,
                  secondUserEntryId,
                  secondAssistantEntryId,
                  secondTurnEndId,
                  threadId);
            });
    // 插入后推进到 SUCCEEDED 并挂上 assistant 结果（与 seedModelInvocation 相同的状态机路径）。
    UUID modelId = modelIdHolder[0];
    transitionModel(store, modelId, m -> m.beginDispatch(NOW));
    transitionModel(store, modelId, m -> m.markRunning(NOW));
    transitionModel(store, modelId, m -> m.succeed(successResponse(List.of(), "bash"), NOW));
    transitionModel(store, modelId, m -> m.attachResultEntry(baseline.assistantEntryId(), NOW));
    return baseline;
  }

  /** 长 USER 文本：估计 token（22_500）超过默认 keepRecentTokens（20_000），保证 planner 把 cut 选在 turn2 USER 上。 */
  static String compactionUserText() {
    return "user asks a very long question" + "x".repeat(90_000);
  }

  /** SUCCEEDED response：{@code callIds} 个 tool call（name 均为 {@code toolName}）。 */
  static ProviderResponse successResponse(List<String> callIds, String toolName) {
    List<ProviderToolCall> calls = new ArrayList<>();
    for (String callId : callIds) {
      calls.add(new ProviderToolCall(callId, toolName, "{}"));
    }
    GenerationStopReason stopReason =
        callIds.isEmpty() ? GenerationStopReason.COMPLETE : GenerationStopReason.COMPLETE;
    return new ProviderResponse(
        "response text", "", calls, stopReason, usage(), cost(), "req-1", null, "{}");
  }

  static ProviderResponse successResponse(List<ProviderToolCall> calls) {
    return new ProviderResponse(
        "response text",
        "",
        calls,
        GenerationStopReason.COMPLETE,
        usage(),
        cost(),
        "req-1",
        null,
        "{}");
  }

  /** 显式 stop reason 的 SUCCEEDED response（覆盖 LENGTH / FILTERED 等矩阵路径）。 */
  static ProviderResponse successResponse(
      String text, List<ProviderToolCall> calls, GenerationStopReason stopReason) {
    return new ProviderResponse(text, "", calls, stopReason, usage(), cost(), "req-1", null, "{}");
  }

  static ToolResult successToolResult(String callId) {
    return new ToolResult(callId, List.of(new TextToolContent("tool ok")), false, "{}");
  }

  static AssistantMessageMetadata assistantMetadata(GenerationStopReason stopReason) {
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

  private static ProviderRequest providerRequest(List<ProviderToolDefinition> tools) {
    return new ProviderRequest(
        modelDescriptor(),
        new ModelVariant("v1", null, null, null, null, null, null, List.of(), null),
        List.of(),
        tools,
        ProviderCacheControl.none());
  }

  private static ModelDescriptor modelDescriptor() {
    return modelDescriptor("provider", "model");
  }

  private static ModelDescriptor modelDescriptor(String providerName, String modelName) {
    return new ModelDescriptor(
        providerName,
        modelName,
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

  private static ToolBinding platformBinding(String name) {
    return new ToolBinding(toolDescriptor(name), ToolType.PLATFORM, null);
  }

  static ToolBinding pluginBinding(
      String name,
      String pluginId,
      String contributionLocalName,
      List<PluginStateAccess> accesses) {
    return new ToolBinding(
        toolDescriptor(name),
        ToolType.PLATFORM,
        null,
        new PluginToolBinding(pluginId, contributionLocalName, accesses));
  }

  private static ToolDescriptor toolDescriptor(String name) {
    return new ToolDescriptor(
        name,
        "1.0",
        ToolType.PLATFORM,
        "description of " + name,
        name,
        new ToolParamsSchema("arguments", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
  }

  // -----------------------------------------------------------------------------------------------
  // fakes（测试替身）
  // -----------------------------------------------------------------------------------------------

  /** Scripted fake resolver：队列结果 / 固定异常 / resolve 前 hook；记录最近一次入参。 */
  static final class FakeTurnResolver implements TurnResolver {
    final Deque<Result> results = new ArrayDeque<>();
    RuntimeException failure;
    Runnable onResolve;
    boolean autoConsistent;
    UUID lastThreadId;
    EntryPath lastPath;
    CompactionPreparation lastPreparation;
    int calls;

    @Override
    public Result resolve(UUID threadId, EntryPath path, CompactionPreparation preparation) {
      calls++;
      lastThreadId = threadId;
      lastPath = path;
      lastPreparation = preparation;
      if (onResolve != null) {
        onResolve.run();
      }
      if (failure != null) {
        throw failure;
      }
      if (autoConsistent) {
        // 按 candidate path 的最终 branch 事实自动构造一致 spec；压缩 turn 按冻结 preparation 构造。
        return new TurnResolver.Resolved(
            preparation == null ? requestFor(path.baseSettings()) : compactionRequest(preparation),
            CONTEXT_WINDOW);
      }
      if (results.isEmpty()) {
        return null;
      }
      return results.poll();
    }
  }

  static final class MutableClock extends Clock {
    private volatile Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }

  static ScheduledExecutorService newScheduler() {
    return Executors.newSingleThreadScheduledExecutor(
        runnable -> {
          Thread thread = new Thread(runnable, "thread-processor-test");
          thread.setDaemon(true);
          return thread;
        });
  }

  /** 每次测试一个 Fixture：InMemory store + fake resolver + 可变时钟 + ThreadProcessor。 */
  static final class Fixture implements AutoCloseable {
    final MutableClock clock = new MutableClock(NOW);
    final InMemoryHarnessStore store = new InMemoryHarnessStore();
    final FakeTurnResolver resolver = new FakeTurnResolver();
    final ScheduledExecutorService scheduler;
    final ThreadProcessor processor;
    ClaimedWork claim;

    Fixture() {
      this(null);
    }

    /** {@code processorStore} 非空时 processor 使用包装 store（seed/断言仍用 {@link #store}）。 */
    Fixture(HarnessStore processorStore) {
      this.scheduler = newScheduler();
      this.processor =
          new ThreadProcessor(
              processorStore == null ? store : processorStore,
              resolver,
              new ThreadProcessorConfig(LEASE_CONFIG, RESOLVE_FAILURE_DELAY, COMPACTION_CONFIG),
              clock,
              scheduler);
    }

    @Override
    public void close() {
      scheduler.shutdownNow();
    }
  }

  /**
   * 包装 InMemory store 的 test fake：第 {@code loseAtFenceCall} 次 {@code lockClaimedWork} 调用开始返回 empty
   * （模拟 claim 在最终 fence 处丢失）。用于证明 final fence 丢失时整事务回滚、零 durable mutation。
   */
  static HarnessStore claimLosingStore(InMemoryHarnessStore real, int loseAtFenceCall) {
    AtomicInteger fenceCalls = new AtomicInteger();
    InvocationHandler storeHandler =
        (storeProxy, method, args) -> {
          if (method.getName().equals("transaction")) {
            @SuppressWarnings("unchecked")
            Function<HarnessStore.Transaction, ?> callback =
                (Function<HarnessStore.Transaction, ?>) args[0];
            return real.transaction(
                tx -> {
                  HarnessStore.Transaction wrapped =
                      (HarnessStore.Transaction)
                          Proxy.newProxyInstance(
                              HarnessStore.Transaction.class.getClassLoader(),
                              new Class<?>[] {HarnessStore.Transaction.class},
                              (txProxy, txMethod, txArgs) -> {
                                if (txMethod.getName().equals("lockClaimedWork")
                                    && fenceCalls.incrementAndGet() >= loseAtFenceCall) {
                                  return Optional.empty();
                                }
                                return txMethod.invoke(tx, txArgs);
                              });
                  return callback.apply(wrapped);
                });
          }
          return method.invoke(real, args);
        };
    return (HarnessStore)
        Proxy.newProxyInstance(
            HarnessStore.class.getClassLoader(), new Class<?>[] {HarnessStore.class}, storeHandler);
  }

  static void awaitCondition(BooleanSupplier condition, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(interrupted);
      }
    }
    fail("condition not met within " + timeout);
  }
}
