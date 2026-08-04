package fun.fengwk.kkstudio.harness.runtime.store.testing;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultMetadata;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultReason;
import fun.fengwk.kkstudio.harness.runtime.history.ToolResultStatus;
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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
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
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

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
 * Small standalone fixture builders and baseline seeds for store tests.
 *
 * <p>每个 builder 只构造一个独立 record；baseline 方法按事务语义原子写入最小合法组合（Session + ROOT + Thread，或再带一个 open
 * TURN_START）。测试通过 {@link #inTransaction} 复用 void 事务体，避免每个 lambda 手写 {@code return null}。
 */
final class StoreTestSupport {

  static final EnvironmentId ENV_ID = new EnvironmentId(UUID.randomUUID().toString());
  static final Instant T0 = Instant.ofEpochMilli(1000);
  static final Instant T1 = Instant.ofEpochMilli(2000);
  static final Instant T2 = Instant.ofEpochMilli(3000);
  static final Instant T3 = Instant.ofEpochMilli(4000);
  static final Instant T4 = Instant.ofEpochMilli(5000);
  static final Instant T5 = Instant.ofEpochMilli(6000);

  private StoreTestSupport() {}

  /** Runs a void transaction body, so tests can use statement lambdas. */
  static void inTransaction(InMemoryHarnessStore store, Consumer<HarnessStore.Transaction> body) {
    store.transaction(
        tx -> {
          body.accept(tx);
          return null;
        });
  }

  /** Minimal committed baseline: one session with ROOT and one thread pointing at the ROOT. */
  record Baseline(long sessionId, long rootEntryId, long threadId) {}

  /** Baseline with one open TURN_START chain; the thread head points at the TURN_START. */
  record TurnBaseline(long sessionId, long rootEntryId, long turnStartEntryId, long threadId) {}

  static Baseline seedThreadBaseline(InMemoryHarnessStore store) {
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

  static TurnBaseline seedTurnBaseline(InMemoryHarnessStore store) {
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

  /** Inserts a child Entry under {@code parentEntryId} with a fresh id and returns that id. */
  static long insertChildEntry(
      InMemoryHarnessStore store, long sessionId, long parentEntryId, EntryPayload payload) {
    return store.transaction(
        tx -> {
          long id = tx.nextId();
          tx.insertEntry(new Entry(id, sessionId, parentEntryId, payload, T1));
          return id;
        });
  }

  static Session session(long id) {
    return new Session(id, "session-" + id, T0);
  }

  static Entry rootEntry(long id, long sessionId) {
    return new Entry(id, sessionId, null, rootPayload(), T0);
  }

  static Entry turnStartEntry(long id, long sessionId, long parentId, Instant createdAt) {
    return new Entry(id, sessionId, parentId, turnStartPayload(), createdAt);
  }

  static Entry assistantEntry(
      long id, long sessionId, long parentId, Instant createdAt, String... toolCallIds) {
    return new Entry(id, sessionId, parentId, assistantPayload(toolCallIds), createdAt);
  }

  static Entry toolResultEntry(
      long id,
      long sessionId,
      long parentId,
      Instant createdAt,
      long assistantEntryId,
      int ordinal,
      String toolCallId) {
    return new Entry(
        id,
        sessionId,
        parentId,
        toolResultPayload(assistantEntryId, ordinal, toolCallId),
        createdAt);
  }

  static EntryPayload rootPayload() {
    return new RootPayload(branchSettings());
  }

  static EntryPayload turnStartPayload() {
    return new TurnStartPayload(TurnStartReason.INPUT, branchSettings());
  }

  /** ASSISTANT MESSAGE payload; {@code toolCallIds} become ordered ToolCall contents. */
  static EntryPayload assistantPayload(String... toolCallIds) {
    List<AgentMessageContent> contents = new ArrayList<>();
    for (String toolCallId : toolCallIds) {
      contents.add(new ToolCallMessageContent(toolCallId, "bash", "{}"));
    }
    contents.add(new TextMessageContent("assistant reply"));
    ProviderStopReason stopReason =
        toolCallIds.length > 0 ? ProviderStopReason.TOOL_CALLS : ProviderStopReason.COMPLETED;
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.ASSISTANT, contents),
        assistantMetadata(stopReason),
        null);
  }

  static EntryPayload assistantErrorPayload() {
    return new AssistantErrorPayload(new AssistantError("MODEL_ERROR", "model failed"));
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

  /** TOOL MESSAGE payload whose metadata matches the given assistant entry / ordinal / call id. */
  static EntryPayload toolResultPayload(long assistantEntryId, int ordinal, String toolCallId) {
    return toolResultPayload(assistantEntryId, ordinal, toolCallId, ToolResultStatus.SUCCEEDED);
  }

  /**
   * TOOL MESSAGE payload with an explicit terminal status (must map the linking invocation status).
   */
  static EntryPayload toolResultPayload(
      long assistantEntryId, int ordinal, String toolCallId, ToolResultStatus status) {
    ToolResultMessageContent content =
        new ToolResultMessageContent(
            toolCallId, "bash", List.of(new TextMessageContent("ok")), false, "{}");
    ToolResultMetadata metadata =
        new ToolResultMetadata(assistantEntryId, toolCallId, ordinal, status, false, null);
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.TOOL, List.of(content)), null, metadata);
  }

  /** Synthetic history-normalization ToolResult entry; must never link a real ToolInvocation. */
  static EntryPayload syntheticToolResultPayload(
      long assistantEntryId, int ordinal, String toolCallId) {
    ToolResultMessageContent content =
        new ToolResultMessageContent(
            toolCallId, "bash", List.of(new TextMessageContent("ok")), false, "{}");
    ToolResultMetadata metadata =
        new ToolResultMetadata(
            assistantEntryId,
            toolCallId,
            ordinal,
            ToolResultStatus.UNKNOWN,
            true,
            ToolResultReason.HISTORY_CUT);
    return new MessagePayload(
        new AgentMessage(AgentMessageRole.TOOL, List.of(content)), null, metadata);
  }

  static ThreadState thread(long id, long headEntryId) {
    return new ThreadState(id, headEntryId, false, 1, 0, T0, T0);
  }

  static ThreadCommand command(long id, long threadId, long sequence, String clientCommandId) {
    return new ThreadCommand(
        id,
        threadId,
        sequence,
        new UserMessageCommandPayload(
            new AgentMessage(
                AgentMessageRole.USER, List.of(new TextMessageContent("message " + sequence)))),
        clientCommandId,
        null,
        null,
        T0);
  }

  /**
   * Returns the command with only the consumed marker set; the rest of the identity is unchanged.
   */
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

  /**
   * Returns the command with only the cancelled marker set; the rest of the identity is unchanged.
   */
  static ThreadCommand withCancelledAt(ThreadCommand command, Instant cancelledAt) {
    return new ThreadCommand(
        command.id(),
        command.threadId(),
        command.sequence(),
        command.payload(),
        command.clientCommandId(),
        null,
        cancelledAt,
        command.createdAt());
  }

  /** READY (non-terminal) or CANCELLED (terminal, may carry resultEntryId) model invocation. */
  static ModelInvocation modelInvocation(
      long id,
      long threadId,
      long turnStartEntryId,
      long basisHeadEntryId,
      ModelInvocationStatus status,
      Long resultEntryId,
      Instant createdAt) {
    if (status != ModelInvocationStatus.READY && status != ModelInvocationStatus.CANCELLED) {
      throw new IllegalArgumentException("fixture supports READY and CANCELLED only");
    }
    ModelInvocationError error = status == ModelInvocationStatus.CANCELLED ? modelError() : null;
    return new ModelInvocation(
        id,
        threadId,
        turnStartEntryId,
        basisHeadEntryId,
        modelRequest(),
        status,
        0,
        null,
        null,
        error,
        resultEntryId,
        createdAt,
        createdAt);
  }

  /** READY (non-terminal) or CANCELLED (terminal, may carry resultEntryId) tool invocation. */
  static ToolInvocation toolInvocation(
      long id,
      long modelInvocationId,
      long assistantEntryId,
      int ordinal,
      String toolCallId,
      ToolInvocationStatus status,
      Long resultEntryId,
      Instant createdAt) {
    if (status != ToolInvocationStatus.READY && status != ToolInvocationStatus.CANCELLED) {
      throw new IllegalArgumentException("fixture supports READY and CANCELLED only");
    }
    ToolInvocationError error = status == ToolInvocationStatus.CANCELLED ? toolError() : null;
    return new ToolInvocation(
        id,
        modelInvocationId,
        assistantEntryId,
        ordinal,
        toolRequest(toolCallId),
        status,
        0,
        null,
        null,
        error,
        resultEntryId,
        createdAt,
        createdAt);
  }

  static BranchSettings branchSettings() {
    return new BranchSettings(
        ENV_ID, "agent", new ModelSelection("provider", "model", "v1"), "low", List.of());
  }

  static ModelInvocationRequest modelRequest() {
    return new ModelInvocationRequest(ENV_ID, providerRequest(), List.of(), List.of(), false);
  }

  static ToolInvocationRequest toolRequest(String toolCallId) {
    return new ToolInvocationRequest(new ToolCall(toolCallId, "bash", "{}"), platformBinding());
  }

  private static AssistantMessageMetadata assistantMetadata(ProviderStopReason stopReason) {
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
}
