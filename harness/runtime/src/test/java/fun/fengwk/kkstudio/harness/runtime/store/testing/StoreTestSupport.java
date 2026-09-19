package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultReason;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelRequestSpec;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
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
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 面向 store 测试的小型独立 fixture builder 与 baseline 种子。
 *
 * <p>每个 builder 只构造一个独立 record；baseline 方法按事务语义原子写入最小合法组合（Session + ROOT + Thread，或再带一个 open
 * TURN_START）。测试通过 {@link #inTransaction} 复用 void 事务体，避免每个 lambda 手写 {@code return null}。
 */
final class StoreTestSupport {

  static final EnvironmentId ENV_ID = EnvironmentId.parse("11111111-1111-1111-1111-111111111111");
  static final UUID OWNER_THREAD_ID = TestIds.id(10L);
  static final int CONTEXT_WINDOW = 100_000;
  static final int MAX_OUTPUT_TOKENS = 16_384;
  static final Instant T0 = Instant.ofEpochMilli(1000);
  static final Instant T1 = Instant.ofEpochMilli(2000);
  static final Instant T2 = Instant.ofEpochMilli(3000);
  static final Instant T3 = Instant.ofEpochMilli(4000);
  static final Instant T4 = Instant.ofEpochMilli(5000);
  static final Instant T5 = Instant.ofEpochMilli(6000);

  /** 测试种子线程的合法 64 位小写 SHA-256 creation request hash（非 accept 路径的固定身份键）。 */
  static final String CREATION_REQUEST_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  private StoreTestSupport() {}

  /** 运行 void transaction 体，便于测试使用 statement lambda。 */
  static void inTransaction(HarnessStore store, Consumer<HarnessStore.Transaction> body) {
    store.transaction(
        tx -> {
          body.accept(tx);
          return null;
        });
  }

  /** 最小已提交 baseline：包含 ROOT 的一个 session，以及指向该 ROOT 的一个 thread。 */
  record Baseline(UUID sessionId, UUID rootEntryId, UUID threadId) {}

  /** 包含一条开放的 TURN_START chain 的 baseline；thread head 指向该 TURN_START。 */
  record TurnBaseline(UUID sessionId, UUID rootEntryId, UUID turnStartEntryId, UUID threadId) {}

  static Baseline seedThreadBaseline(HarnessStore store) {
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

  static TurnBaseline seedTurnBaseline(HarnessStore store) {
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
                  resolvedTurnStartPayload(threadId),
                  T1));
          tx.insertThread(thread(threadId, sessionId, turnStartEntryId));
          return new TurnBaseline(sessionId, rootEntryId, turnStartEntryId, threadId);
        });
  }

  /** 在 {@code parentEntryId} 下插入一个带新 id 的子 Entry，并返回该 id。 */
  static UUID insertChildEntry(
      HarnessStore store, UUID sessionId, UUID parentEntryId, EntryPayload payload) {
    return store.transaction(
        tx -> {
          UUID id = tx.nextId();
          tx.insertEntry(new Entry(id, sessionId, parentEntryId, payload, T1));
          return id;
        });
  }

  static Session session(UUID id) {
    return new Session(id, "session-" + id, T0);
  }

  static Entry rootEntry(UUID id, UUID sessionId) {
    return new Entry(id, sessionId, null, rootPayload(), T0);
  }

  static Entry turnStartEntry(UUID id, UUID sessionId, UUID parentId, Instant createdAt) {
    return new Entry(id, sessionId, parentId, turnStartPayload(), createdAt);
  }

  /**
   * 带 owner Thread 的 TURN_START，用于满足「consumed turn start ownerThreadId 必须等于 command threadId」的新契约。
   */
  static Entry turnStartEntry(
      UUID id, UUID sessionId, UUID parentId, UUID ownerThreadId, Instant createdAt) {
    return new Entry(id, sessionId, parentId, resolvedTurnStartPayload(ownerThreadId), createdAt);
  }

  static Entry assistantEntry(
      UUID id, UUID sessionId, UUID parentId, Instant createdAt, String... toolCallIds) {
    return new Entry(id, sessionId, parentId, assistantPayload(toolCallIds), createdAt);
  }

  static Entry toolResultEntry(
      UUID id,
      UUID sessionId,
      UUID parentId,
      Instant createdAt,
      UUID assistantEntryId,
      int callIndex,
      String toolCallId) {
    return new Entry(
        id,
        sessionId,
        parentId,
        toolResultPayload(assistantEntryId, callIndex, toolCallId),
        createdAt);
  }

  static EntryPayload rootPayload() {
    return new RootPayload(branchSettings());
  }

  static EntryPayload turnStartPayload() {
    return new TurnStartPayload(TurnStartReason.INPUT, branchSettings(), OWNER_THREAD_ID);
  }

  static EntryPayload resolvedTurnStartPayload(UUID ownerThreadId) {
    return new TurnStartPayload(
        TurnStartReason.INPUT,
        branchSettings(),
        ownerThreadId,
        CONTEXT_WINDOW,
        MAX_OUTPUT_TOKENS,
        null);
  }

  /** ASSISTANT MESSAGE payload；{@code toolCallIds} 会按顺序变成 ToolCall 内容。 */
  static EntryPayload assistantPayload(String... toolCallIds) {
    List<AgentMessageContent> contents = new ArrayList<>();
    for (String toolCallId : toolCallIds) {
      contents.add(new ToolCallMessageContent(toolCallId, "bash", "bash", "{}"));
    }
    contents.add(new TextMessageContent("assistant reply"));
    GenerationStopReason stopReason =
        toolCallIds.length > 0 ? GenerationStopReason.COMPLETE : GenerationStopReason.COMPLETE;
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, contents),
        assistantMetadata(stopReason),
        null);
  }

  static EntryPayload assistantPayloadWithArguments(String toolCallId, String argumentsJson) {
    return new MessagePayload(
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(
                new ToolCallMessageContent(toolCallId, "bash", "bash", argumentsJson),
                new TextMessageContent("assistant reply"))),
        assistantMetadata(GenerationStopReason.COMPLETE),
        null);
  }

  static ProviderResponse assistantResponse(String... toolCallIds) {
    List<ProviderToolCall> calls = new ArrayList<>();
    for (String toolCallId : toolCallIds) {
      calls.add(new ProviderToolCall(toolCallId, "bash", "{}"));
    }
    return new ProviderResponse(
        "assistant reply",
        "",
        calls,
        calls.isEmpty() ? GenerationStopReason.COMPLETE : GenerationStopReason.COMPLETE,
        new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L),
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO),
        "req-1",
        null,
        "{}");
  }

  static EntryPayload assistantErrorPayload() {
    return new AssistantErrorPayload(
        new AssistantError(ProviderErrorKind.INVALID_REQUEST.name(), "model failed"), null);
  }

  static EntryPayload assistantAbortedPayload() {
    return new AssistantAbortedPayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("partial"))));
  }

  static EntryPayload userMessagePayload() {
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
        null,
        null);
  }

  /** TOOL MESSAGE payload，其 metadata 与给定的 assistant entry / callIndex / call id 匹配。 */
  static EntryPayload toolResultPayload(UUID assistantEntryId, int callIndex, String toolCallId) {
    return toolResultPayload(assistantEntryId, callIndex, toolCallId, ToolResultStatus.SUCCEEDED);
  }

  /** TOOL MESSAGE payload，带显式 terminal status（必须精确映射所关联 invocation 的 status）。 */
  static EntryPayload toolResultPayload(
      UUID assistantEntryId, int callIndex, String toolCallId, ToolResultStatus status) {
    return toolResultPayload(assistantEntryId, callIndex, toolCallId, status, "bash");
  }

  static EntryPayload toolResultPayload(
      UUID assistantEntryId,
      int callIndex,
      String toolCallId,
      ToolResultStatus status,
      String rendererKey) {
    ToolResultMessageContent content =
        new ToolResultMessageContent(
            toolCallId, "bash", rendererKey, List.of(new TextMessageContent("ok")), false, "{}");
    ToolResultMetadata metadata =
        new ToolResultMetadata(assistantEntryId, toolCallId, callIndex, status, false, null);
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.TOOL, List.of(content)), null, metadata);
  }

  /** 用于 history-normalization 的合成 ToolResult entry；禁止关联真实 ToolInvocation。 */
  static EntryPayload syntheticToolResultPayload(
      UUID assistantEntryId, int callIndex, String toolCallId) {
    ToolResultMessageContent content =
        new ToolResultMessageContent(
            toolCallId, "bash", "bash", List.of(new TextMessageContent("ok")), false, "{}");
    ToolResultMetadata metadata =
        new ToolResultMetadata(
            assistantEntryId,
            toolCallId,
            callIndex,
            ToolResultStatus.UNKNOWN,
            true,
            ToolResultReason.HISTORY_CUT);
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.TOOL, List.of(content)), null, metadata);
  }

  static ThreadState thread(UUID id, UUID sessionId, UUID headEntryId) {
    return threadState(id, sessionId, headEntryId, 1, 0, T0, T0);
  }

  /** 允许显式指定 next sequence / version 与时间的 ThreadState 构造（契约测试模拟已推进的 Thread）。 */
  static ThreadState threadState(
      UUID id,
      UUID sessionId,
      UUID headEntryId,
      long nextCommandSequence,
      long version,
      Instant createdAt,
      Instant updatedAt) {
    return threadState(
        id,
        sessionId,
        headEntryId,
        "main",
        false,
        nextCommandSequence,
        version,
        createdAt,
        updatedAt);
  }

  /** 允许显式指定 YOLO runtime policy、next sequence / version 与时间的 ThreadState 构造。 */
  static ThreadState threadState(
      UUID id,
      UUID sessionId,
      UUID headEntryId,
      boolean yoloEnabled,
      long nextCommandSequence,
      long version,
      Instant createdAt,
      Instant updatedAt) {
    return threadState(
        id,
        sessionId,
        headEntryId,
        "main",
        yoloEnabled,
        nextCommandSequence,
        version,
        createdAt,
        updatedAt);
  }

  /** 允许显式指定名称、YOLO runtime policy、next sequence / version 与时间的 ThreadState 构造。 */
  static ThreadState threadState(
      UUID id,
      UUID sessionId,
      UUID headEntryId,
      String name,
      boolean yoloEnabled,
      long nextCommandSequence,
      long version,
      Instant createdAt,
      Instant updatedAt) {
    return new ThreadState(
        id,
        sessionId,
        headEntryId,
        CREATION_REQUEST_HASH,
        name,
        yoloEnabled,
        nextCommandSequence,
        version,
        createdAt,
        updatedAt);
  }

  /**
   * 命令 helper：ThreadCommand 不再携带代理 id，签名仅为 {@code (threadId, sequence, idempotencyKey)}。
   * idempotencyKey 必须为非空 UUID。
   */
  static ThreadCommand command(UUID threadId, long sequence, UUID idempotencyKey) {
    UserMessageCommandPayload payload =
        new UserMessageCommandPayload(
            new AgentMessage(
                AgentMessageRole.USER, List.of(new TextMessageContent("message " + sequence))));
    return new ThreadCommand(
        threadId,
        sequence,
        payload,
        idempotencyKey,
        ThreadCommandPayloadJsonCodec.requestHash(payload),
        null,
        null,
        null,
        T0);
  }

  /** 返回仅设置了 consumed marker 的 command；其余身份信息保持不变。 */
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

  /** 返回仅设置了 cancelled marker（stopRequestId + cancelledAt 成对）的 command；其余身份信息保持不变。 */
  static ThreadCommand withCancelledAt(
      ThreadCommand command, UUID stopRequestId, Instant cancelledAt) {
    return new ThreadCommand(
        command.threadId(),
        command.sequence(),
        command.payload(),
        command.idempotencyKey(),
        command.requestHash(),
        null,
        stopRequestId,
        cancelledAt,
        command.createdAt());
  }

  /** READY（非 terminal）或 CANCELLED（terminal，可携带 resultEntryId）的 model invocation。 */
  static ModelInvocation modelInvocation(
      UUID id,
      UUID threadId,
      UUID turnStartEntryId,
      UUID requestHeadEntryId,
      ModelInvocationStatus status,
      UUID resultEntryId,
      Instant createdAt) {
    if (status != ModelInvocationStatus.READY && status != ModelInvocationStatus.CANCELLED) {
      throw new IllegalArgumentException("fixture supports READY and CANCELLED only");
    }
    ModelInvocationError error = status == ModelInvocationStatus.CANCELLED ? modelError() : null;
    return new ModelInvocation(
        id,
        threadId,
        turnStartEntryId,
        requestHeadEntryId,
        modelRequest(),
        status,
        0,
        null,
        null,
        error,
        resultEntryId,
        List.of(),
        createdAt,
        createdAt);
  }

  /**
   * READY（非 terminal）或 CANCELLED（terminal）的 tool invocation；不再持有 resultEntryId（batch apply 后行物理删除）。
   */
  static ToolInvocation toolInvocation(
      UUID id,
      UUID modelInvocationId,
      UUID assistantEntryId,
      int callIndex,
      String toolCallId,
      ToolInvocationStatus status,
      Instant createdAt) {
    if (status != ToolInvocationStatus.READY && status != ToolInvocationStatus.CANCELLED) {
      throw new IllegalArgumentException("fixture supports READY and CANCELLED only");
    }
    ToolInvocationError error = status == ToolInvocationStatus.CANCELLED ? toolError() : null;
    return new ToolInvocation(
        id,
        modelInvocationId,
        assistantEntryId,
        callIndex,
        toolCall(toolCallId),
        hostBinding(),
        status,
        0,
        null,
        null,
        error,
        createdAt,
        createdAt);
  }

  /** 复制 invocation，仅替换冻结 binding 的 rendererKey。 */
  static ToolInvocation withRendererKey(ToolInvocation invocation, String rendererKey) {
    ToolDescriptor descriptor = invocation.binding().descriptor();
    AgentToolDefinition definition = invocation.binding().definition();
    ToolBinding binding =
        new ToolBinding(
            new AgentToolDefinition(
                new ToolDescriptor(
                    descriptor.name(),
                    descriptor.description(),
                    rendererKey,
                    descriptor.inputSchema(),
                    descriptor.sideEffect(),
                    descriptor.timeout()),
                definition.visibility()),
            invocation.binding().contributor(),
            invocation.binding().environmentRequired(),
            invocation.binding().environmentId());
    return new ToolInvocation(
        invocation.id(),
        invocation.modelInvocationId(),
        invocation.assistantEntryId(),
        invocation.callIndex(),
        invocation.call(),
        binding,
        invocation.status(),
        invocation.attempt(),
        invocation.approval(),
        invocation.result(),
        invocation.effects(),
        invocation.error(),
        invocation.createdAt(),
        invocation.updatedAt());
  }

  static BranchSettings branchSettings() {
    return new BranchSettings("agent", new ModelSelection("provider", "model", "v1"), null);
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

  /** SUCCEEDED live-attach 的机械请求：单一 bash binding（renderer 由 binding 派生，与 assistant ToolCall 全等）。 */
  static ModelRequestSpec succeededRequest() {
    ModelRequestSpec base = modelRequest();
    return new ModelRequestSpec(
        base.providerType(),
        base.providerConnectionGenerationId(),
        base.model(),
        base.variant(),
        1024,
        base.preambleMessages(),
        List.of(hostBinding()),
        List.of(),
        List.of(),
        base.cacheControl());
  }

  /** SUCCEEDED assistant payload：由同一 request/response 经 mapper 机械派生（strict attach 校验要求全等）。 */
  static MessagePayload mappedAssistant(ModelRequestSpec requestSpec, ProviderResponse response) {
    return new HistoryPayloadMapper().assistantPayload(response, requestSpec.toolBindings());
  }

  static ToolCall toolCall(String toolCallId) {
    return toolCall(toolCallId, "{}");
  }

  static ToolCall toolCall(String toolCallId, String argumentsJson) {
    return new ToolCall(toolCallId, "bash", argumentsJson);
  }

  private static AssistantMessageMetadata assistantMetadata(GenerationStopReason stopReason) {
    return new AssistantMessageMetadata(
        stopReason,
        new ModelUsage(1L, 2L, 0L, 0L, 0L, 0L, 3L),
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO));
  }

  private static ModelInvocationError modelError() {
    return new ModelInvocationError(ProviderErrorKind.TRANSIENT, "model boom");
  }

  private static ToolInvocationError toolError() {
    return new ToolInvocationError("CANCELLED", "cancelled");
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

  static ToolBinding hostBinding() {
    return new ToolBinding(
        new AgentToolDefinition(toolDescriptor("bash"), ToolVisibility.SELECTABLE),
        new ContributorBinding("core", "bash", List.of()),
        false,
        null);
  }

  private static ToolDescriptor toolDescriptor(String name) {
    return new ToolDescriptor(
        name,
        "description of " + name,
        name,
        new InputSchema("arguments", Map.of(), Set.of(), false),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
  }
}
