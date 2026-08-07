package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.fail;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
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
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
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
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.work.ClaimedWork;
import fun.fengwk.kkstudio.harness.runtime.work.Work;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTarget;
import fun.fengwk.kkstudio.harness.runtime.work.WorkTargetType;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
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
 * transition 逐级推进； ToolInvocation 只能以 READY/attempt=0/approval=null 插入且其
 * modelInvocation.resultEntryId 必须等于 assistantEntryId。
 */
final class ThreadProcessorTestSupport {

  static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");
  static final EnvironmentName ENV_ID = new EnvironmentName("env-1");
  static final ProcessorLeaseConfig LEASE_CONFIG =
      new ProcessorLeaseConfig(Duration.ofSeconds(30), Duration.ofSeconds(5));
  static final Duration RESOLVE_FAILURE_DELAY = Duration.ofSeconds(7);
  static final int STEP_LIMIT = 8;

  private ThreadProcessorTestSupport() {}

  // -----------------------------------------------------------------------------------------------
  // ids / fixtures（标识 / 测试基座）
  // -----------------------------------------------------------------------------------------------

  record Baseline(long sessionId, long rootEntryId, long threadId) {}

  record OpenTurnBaseline(
      long sessionId, long rootEntryId, long turnStartEntryId, long userEntryId, long threadId) {}

  record ClosedTurnBaseline(
      long sessionId,
      long rootEntryId,
      long turnStartEntryId,
      long userEntryId,
      long assistantEntryId,
      long turnEndEntryId,
      long threadId) {}

  record HistoricalBaseline(
      long sessionId,
      long rootEntryId,
      long turnStartEntryId,
      long userEntryId,
      long assistantEntryId,
      long headEntryId,
      long threadId) {}

  /** Session + ROOT + Thread（head 指向 ROOT）。 */
  static Baseline seedBaseline(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          long sessionId = tx.nextId();
          long rootEntryId = tx.nextId();
          long threadId = tx.nextId();
          tx.insertSession(new Session(sessionId, "session-" + sessionId, NOW));
          tx.insertEntry(
              new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), NOW));
          tx.insertThread(new ThreadState(threadId, rootEntryId, false, 1, 0, NOW, NOW));
          return new Baseline(sessionId, rootEntryId, threadId);
        });
  }

  /** Session + ROOT + TURN_START(INPUT) + USER；Thread head 指向 USER。 */
  static OpenTurnBaseline seedOpenInputTurn(InMemoryHarnessStore store) {
    return store.transaction(
        tx -> {
          long sessionId = tx.nextId();
          long rootEntryId = tx.nextId();
          long turnStartEntryId = tx.nextId();
          long userEntryId = tx.nextId();
          long threadId = tx.nextId();
          tx.insertSession(new Session(sessionId, "session-" + sessionId, NOW));
          tx.insertEntry(
              new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), NOW));
          tx.insertEntry(
              new Entry(
                  turnStartEntryId,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(TurnStartReason.INPUT, branchSettings()),
                  NOW));
          tx.insertEntry(
              new Entry(
                  userEntryId, sessionId, turnStartEntryId, userMessagePayload("hello"), NOW));
          tx.insertThread(new ThreadState(threadId, userEntryId, false, 1, 0, NOW, NOW));
          return new OpenTurnBaseline(
              sessionId, rootEntryId, turnStartEntryId, userEntryId, threadId);
        });
  }

  /** Session + ROOT + 完整关闭 Turn（ASSISTANT 无 tool call + TURN_END COMPLETED）。 */
  static ClosedTurnBaseline seedClosedTurn(InMemoryHarnessStore store, boolean continueModel) {
    return store.transaction(
        tx -> {
          long sessionId = tx.nextId();
          long rootEntryId = tx.nextId();
          long turnStartEntryId = tx.nextId();
          long userEntryId = tx.nextId();
          long assistantEntryId = tx.nextId();
          long turnEndEntryId = tx.nextId();
          long threadId = tx.nextId();
          tx.insertSession(new Session(sessionId, "session-" + sessionId, NOW));
          tx.insertEntry(
              new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), NOW));
          tx.insertEntry(
              new Entry(
                  turnStartEntryId,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(TurnStartReason.INPUT, branchSettings()),
                  NOW));
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
          tx.insertThread(new ThreadState(threadId, turnEndEntryId, false, 1, 0, NOW, NOW));
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
          long sessionId = tx.nextId();
          long rootEntryId = tx.nextId();
          long turnStartEntryId = tx.nextId();
          long userEntryId = tx.nextId();
          long assistantEntryId = tx.nextId();
          long threadId = tx.nextId();
          tx.insertSession(new Session(sessionId, "session-" + sessionId, NOW));
          tx.insertEntry(
              new Entry(rootEntryId, sessionId, null, new RootPayload(branchSettings()), NOW));
          tx.insertEntry(
              new Entry(
                  turnStartEntryId,
                  sessionId,
                  rootEntryId,
                  new TurnStartPayload(TurnStartReason.INPUT, branchSettings()),
                  NOW));
          tx.insertEntry(
              new Entry(
                  userEntryId, sessionId, turnStartEntryId, userMessagePayload("hello"), NOW));
          List<String> callIds = new ArrayList<>();
          for (int i = 0; i < toolCalls; i++) {
            callIds.add("call-" + i);
          }
          tx.insertEntry(
              new Entry(assistantEntryId, sessionId, userEntryId, assistantPayload(callIds), NOW));
          long headEntryId = assistantEntryId;
          for (int ordinal = 0; ordinal < presentResults; ordinal++) {
            long resultId = tx.nextId();
            tx.insertEntry(
                new Entry(
                    resultId,
                    sessionId,
                    headEntryId,
                    realToolResultPayload(assistantEntryId, ordinal, "call-" + ordinal),
                    NOW));
            headEntryId = resultId;
          }
          tx.insertThread(new ThreadState(threadId, headEntryId, false, 1, 0, NOW, NOW));
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
      InMemoryHarnessStore store, long sessionId, long assistantEntryId) {
    return store.transaction(
        tx -> {
          long threadId = tx.nextId();
          tx.insertThread(new ThreadState(threadId, assistantEntryId, false, 1, 0, NOW, NOW));
          return new HistoricalBaseline(
              sessionId, -1L, -1L, -1L, assistantEntryId, assistantEntryId, threadId);
        });
  }

  /** 插入一条 QUEUED Command（sequence 取自 Thread.nextCommandSequence）并推进 Thread 序列。 */
  static long seedCommand(InMemoryHarnessStore store, long threadId, ThreadCommandPayload payload) {
    return store.transaction(
        tx -> {
          ThreadState thread = tx.lockThread(threadId).orElseThrow();
          long id = tx.nextId();
          long sequence = thread.nextCommandSequence();
          tx.insertCommands(
              List.of(
                  new ThreadCommand(
                      id, threadId, sequence, payload, "client-" + id, null, null, NOW)));
          tx.updateThread(thread.reserveCommandSequences(1, NOW));
          return id;
        });
  }

  /** 在一条已存在 Thread 上请求 THREAD Work（模拟 enqueue 侧）。 */
  static void requestThreadWork(InMemoryHarnessStore store, long threadId) {
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
      long assistantEntryId,
      long modelInvocationId,
      List<Long> toolInvocationIds) {}

  static ToolChain seedToolChain(
      InMemoryHarnessStore store,
      List<String> callIds,
      ModelInvocationStatus modelStatus,
      List<ToolInvocationStatus> toolStatuses) {
    if (toolStatuses.size() != callIds.size()) {
      throw new IllegalArgumentException("toolStatuses must match callIds");
    }
    OpenTurnBaseline turn = seedOpenInputTurn(store);
    long modelId =
        seedModelInvocation(
            store,
            turn.threadId(),
            turn.turnStartEntryId(),
            turn.userEntryId(),
            modelStatus,
            tooledRequest(List.of("bash")),
            successResponse(callIds, "bash"),
            null);
    long assistantEntryId = insertAssistantWithCalls(store, turn, callIds);
    if (modelStatus == ModelInvocationStatus.SUCCEEDED) {
      transitionModel(store, modelId, m -> m.attachResultEntry(assistantEntryId, NOW));
    }
    List<Long> toolIds = new ArrayList<>();
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
  static long insertAssistantWithCalls(
      InMemoryHarnessStore store, OpenTurnBaseline turn, List<String> callIds) {
    return store.transaction(
        tx -> {
          tx.lockThread(turn.threadId());
          long id = tx.nextId();
          tx.insertEntry(
              new Entry(id, turn.sessionId(), turn.userEntryId(), assistantPayload(callIds), NOW));
          tx.updateThread(tx.findThread(turn.threadId()).orElseThrow().advanceHead(id, false, NOW));
          return id;
        });
  }

  /**
   * 插入 READY ModelInvocation（basis 必须等于 Thread 当前 head）并把状态推进到 {@code status}（terminal 且
   * resultEntryId 仍 null）。
   */
  static long seedModelInvocation(
      InMemoryHarnessStore store,
      long threadId,
      long turnStartEntryId,
      long basisEntryId,
      ModelInvocationStatus status,
      ModelInvocationRequest request,
      ProviderResponse response,
      ModelInvocationError error) {
    long modelId =
        store.transaction(
            tx -> {
              tx.lockThread(threadId);
              long id = tx.nextId();
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

  /** 插入 READY ToolInvocation 并把状态推进到 {@code status}（terminal 且 resultEntryId 仍 null）。 */
  static long seedToolInvocation(
      InMemoryHarnessStore store,
      long modelInvocationId,
      long assistantEntryId,
      int ordinal,
      String callId,
      ToolInvocationStatus status) {
    long toolId =
        store.transaction(
            tx -> {
              long id = tx.nextId();
              tx.insertToolInvocations(
                  List.of(
                      new ToolInvocation(
                          id,
                          modelInvocationId,
                          assistantEntryId,
                          ordinal,
                          toolRequest(callId),
                          ToolInvocationStatus.READY,
                          0,
                          null,
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

  static ClaimedWork claimThreadWork(InMemoryHarnessStore store, long threadId) {
    return claimThreadWork(store, threadId, NOW);
  }

  static ClaimedWork claimThreadWork(InMemoryHarnessStore store, long threadId, Instant now) {
    return claimThreadWork(store, threadId, now, now.plusSeconds(60));
  }

  static ClaimedWork claimThreadWork(
      InMemoryHarnessStore store, long threadId, Instant now, Instant leaseUntil) {
    return store
        .transaction(
            tx -> tx.claimNextWork(WorkTargetType.THREAD, now, "token-" + threadId, leaseUntil))
        .orElseThrow();
  }

  // -----------------------------------------------------------------------------------------------
  // transitions / reads（状态转换 / 读取）
  // -----------------------------------------------------------------------------------------------

  static void transitionModel(
      InMemoryHarnessStore store, long modelId, Function<ModelInvocation, ModelInvocation> f) {
    store.transaction(
        tx -> {
          ModelInvocation model = tx.lockModelInvocation(modelId).orElseThrow();
          tx.updateModelInvocation(f.apply(model));
          return null;
        });
  }

  static void transitionTool(
      InMemoryHarnessStore store, long toolId, Function<ToolInvocation, ToolInvocation> f) {
    store.transaction(
        tx -> {
          ToolInvocation tool = tx.lockToolInvocation(toolId).orElseThrow();
          tx.updateToolInvocations(List.of(f.apply(tool)));
          return null;
        });
  }

  /** 把 READY ToolInvocation 逐级推进到 SUCCEEDED 并挂载自定义结果（用于 mapper 回滚类测试）。 */
  static void succeedToolWith(InMemoryHarnessStore store, long toolId, ToolResult result) {
    transitionTool(store, toolId, t -> t.markApprovalNotRequired(NOW));
    transitionTool(store, toolId, t -> t.beginDispatch(NOW));
    transitionTool(store, toolId, t -> t.markRunning(NOW));
    transitionTool(store, toolId, t -> t.succeed(result, NOW));
  }

  static ThreadState thread(InMemoryHarnessStore store, long threadId) {
    return store.transaction(tx -> tx.findThread(threadId)).orElseThrow();
  }

  static EntryPath path(InMemoryHarnessStore store, long threadId) {
    return store.transaction(
        tx -> tx.loadEntryPath(tx.findThread(threadId).orElseThrow().headEntryId()));
  }

  static Entry entry(InMemoryHarnessStore store, long entryId) {
    return store.transaction(tx -> tx.findEntry(entryId)).orElseThrow();
  }

  static ModelInvocation model(InMemoryHarnessStore store, long modelId) {
    return store.transaction(tx -> tx.findModelInvocation(modelId)).orElseThrow();
  }

  static ToolInvocation tool(InMemoryHarnessStore store, long toolId) {
    return store.transaction(tx -> tx.findToolInvocation(toolId)).orElseThrow();
  }

  static List<ToolInvocation> toolsByAssistant(InMemoryHarnessStore store, long assistantEntryId) {
    return store.transaction(tx -> tx.loadToolInvocationsByAssistantEntryId(assistantEntryId));
  }

  static ThreadCommand command(InMemoryHarnessStore store, long threadId, long commandId) {
    return store
        .transaction(tx -> tx.findCommandByClientId(threadId, "client-" + commandId))
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
        ENV_ID, "agent", new ModelSelection("provider", "model", "v1"), "low", List.of());
  }

  static EntryPayload userMessagePayload(String text) {
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent(text))), null, null);
  }

  /** ASSISTANT MESSAGE payload；{@code callIds} 按 ordinal 生成 bash tool call。 */
  static EntryPayload assistantPayload(List<String> callIds) {
    List<AgentMessageContent> contents = new ArrayList<>();
    for (int i = 0; i < callIds.size(); i++) {
      contents.add(new ToolCallMessageContent(callIds.get(i), "bash", "{}"));
    }
    contents.add(new TextMessageContent("assistant reply"));
    ProviderStopReason stopReason =
        callIds.isEmpty() ? ProviderStopReason.COMPLETED : ProviderStopReason.TOOL_CALLS;
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, contents),
        assistantMetadata(stopReason),
        null);
  }

  /** 真实 ToolResult MESSAGE payload（非 synthetic，status SUCCEEDED）。 */
  static EntryPayload realToolResultPayload(long assistantEntryId, int ordinal, String callId) {
    ToolResultMessageContent content =
        new ToolResultMessageContent(
            callId, "bash", List.of(new TextMessageContent("ok")), false, "{}");
    ToolResultMetadata metadata =
        new ToolResultMetadata(
            assistantEntryId, callId, ordinal, ToolResultStatus.SUCCEEDED, false, null);
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.TOOL, List.of(content)), null, metadata);
  }

  static ModelInvocationRequest tooledRequest(List<String> toolNames) {
    List<ToolBinding> bindings = new ArrayList<>();
    List<ProviderToolDefinition> definitions = new ArrayList<>();
    for (String name : toolNames) {
      bindings.add(platformBinding(name));
      definitions.add(new ProviderToolDefinition(name, "description of " + name, "{}"));
    }
    return new ModelInvocationRequest(
        ENV_ID, providerRequest(definitions), bindings, List.of(), false);
  }

  static ModelInvocationRequest plainRequest() {
    return tooledRequest(List.of());
  }

  /** 按 candidate BranchSettings 构造机械一致的 Resolved 请求（env / model / variant / activeTools / yolo）。 */
  static ModelInvocationRequest requestFor(BranchSettings settings, boolean yoloEnabled) {
    List<ToolBinding> bindings = new ArrayList<>();
    List<ProviderToolDefinition> definitions = new ArrayList<>();
    for (String name : settings.activeTools()) {
      bindings.add(platformBinding(name));
      definitions.add(new ProviderToolDefinition(name, "description of " + name, "{}"));
    }
    return new ModelInvocationRequest(
        settings.environmentName(),
        new ProviderRequest(
            modelDescriptor(settings.model().providerName(), settings.model().modelName()),
            new ModelVariant(
                settings.model().variant(), null, null, null, null, null, null, List.of(), null),
            List.of(),
            definitions,
            ProviderCacheControl.none()),
        bindings,
        List.of(),
        yoloEnabled);
  }

  static ToolInvocationRequest toolRequest(String callId) {
    return new ToolInvocationRequest(new ToolCall(callId, "bash", "{}"), platformBinding("bash"));
  }

  /** SUCCEEDED response：{@code callIds} 个 tool call（name 均为 {@code toolName}）。 */
  static ProviderResponse successResponse(List<String> callIds, String toolName) {
    List<ProviderToolCall> calls = new ArrayList<>();
    for (String callId : callIds) {
      calls.add(new ProviderToolCall(callId, toolName, "{}"));
    }
    ProviderStopReason stopReason =
        callIds.isEmpty() ? ProviderStopReason.COMPLETED : ProviderStopReason.TOOL_CALLS;
    return new ProviderResponse(
        "response text", "", calls, stopReason, usage(), cost(), "req-1", null, "{}");
  }

  static ToolResult successToolResult(String callId) {
    return new ToolResult(callId, List.of(new TextToolContent("tool ok")), false, "{}", false);
  }

  static AssistantMessageMetadata assistantMetadata(ProviderStopReason stopReason) {
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

  // -----------------------------------------------------------------------------------------------
  // fakes（测试替身）
  // -----------------------------------------------------------------------------------------------

  /** Scripted fake resolver：队列结果 / 固定异常 / resolve 前 hook；记录最近一次入参。 */
  static final class FakeTurnResolver implements TurnResolver {
    final Deque<Result> results = new ArrayDeque<>();
    RuntimeException failure;
    Runnable onResolve;
    boolean autoConsistent;
    long lastThreadId;
    EntryPath lastPath;
    boolean lastYoloEnabled;
    int calls;

    @Override
    public Result resolve(long threadId, EntryPath path, boolean yoloEnabled) {
      calls++;
      lastThreadId = threadId;
      lastPath = path;
      lastYoloEnabled = yoloEnabled;
      if (onResolve != null) {
        onResolve.run();
      }
      if (failure != null) {
        throw failure;
      }
      if (autoConsistent) {
        // 按 candidate path 的最终 branch 事实自动构造一致请求（settings/yolo 与校验完全同源）。
        return new TurnResolver.Resolved(requestFor(path.baseSettings(), yoloEnabled));
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

    Fixture(int stepLimit) {
      this(stepLimit, null);
    }

    /** {@code processorStore} 非空时 processor 使用包装 store（seed/断言仍用 {@link #store}）。 */
    Fixture(int stepLimit, HarnessStore processorStore) {
      this.scheduler = newScheduler();
      this.processor =
          new ThreadProcessor(
              processorStore == null ? store : processorStore,
              resolver,
              new ThreadProcessorConfig(LEASE_CONFIG, stepLimit, RESOLVE_FAILURE_DELAY),
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
