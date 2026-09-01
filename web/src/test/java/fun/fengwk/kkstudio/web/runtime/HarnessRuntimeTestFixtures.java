package fun.fengwk.kkstudio.web.runtime;

import fun.fengwk.kkstudio.harness.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.environment.EnvironmentBinding;
import fun.fengwk.kkstudio.harness.environment.EnvironmentName;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationRequest;
import fun.fengwk.kkstudio.harness.runtime.invocation.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.AgentToolId;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 测试专用最小合法 Harness Runtime domain facts（web 层映射测试基座）。 */
public final class HarnessRuntimeTestFixtures {

  public static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final UUID OWNER_THREAD_ID = id(10);
  private static final String CREATION_REQUEST_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private HarnessRuntimeTestFixtures() {}

  public static BranchSettings settings() {
    return new BranchSettings(
        new EnvironmentBinding(new EnvironmentName("env-1"), "."),
        "default-assistant",
        new ModelSelection("openai", "gpt-5", "default"));
  }

  public static Entry rootEntry() {
    return new Entry(id(1), id(1), null, new RootPayload(settings()), NOW);
  }

  public static Entry turnStartEntry() {
    return new Entry(
        id(2),
        id(1),
        id(1),
        new TurnStartPayload(TurnStartReason.INPUT, settings(), OWNER_THREAD_ID),
        NOW);
  }

  public static Entry userMessageEntry() {
    MessagePayload payload =
        new MessagePayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))),
            null,
            null);
    return new Entry(id(3), id(1), id(2), payload, NOW);
  }

  /** 无 tool call 的 ASSISTANT 结果（用于 COMPLETED TURN_END 前置）。 */
  public static Entry plainAssistantEntry() {
    MessagePayload payload =
        new MessagePayload(
            new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("ok"))),
            new AssistantMessageMetadata(GenerationStopReason.COMPLETE, usage(), cost()),
            null);
    return new Entry(id(4), id(1), id(3), payload, NOW);
  }

  public static ThreadState thread(UUID headEntryId) {
    return new ThreadState(id(1), id(1), headEntryId, CREATION_REQUEST_HASH, true, 4, 3, NOW, NOW);
  }

  public static ThreadState thread(UUID id, UUID headEntryId) {
    return new ThreadState(id, id(1), headEntryId, CREATION_REQUEST_HASH, true, 4, 3, NOW, NOW);
  }

  public static Session session() {
    return new Session(id(1), NOW);
  }

  /** IDLE 快照：仅 ROOT，无 open Turn、无 Invocation。 */
  public static ThreadSnapshot idleSnapshot() {
    EntryPath path = new EntryPath(List.of(rootEntry()));
    return new ThreadSnapshot(thread(id(1)), path, List.of(), null, List.of(), List.of());
  }

  /** IDLE 快照（指定 thread id）。 */
  public static ThreadSnapshot idleSnapshot(UUID threadId) {
    EntryPath path = new EntryPath(List.of(rootEntry()));
    return new ThreadSnapshot(thread(threadId, id(1)), path, List.of(), null, List.of(), List.of());
  }

  /** CONTINUATION_DUE 快照：ROOT -> TURN_START -> USER -> ASSISTANT -> continueModel TURN_END。 */
  public static ThreadSnapshot continuationDueSnapshot() {
    return continuationDueSnapshot(id(1));
  }

  /** CONTINUATION_DUE 快照（指定 thread id）。 */
  public static ThreadSnapshot continuationDueSnapshot(UUID threadId) {
    Entry turnEnd =
        new Entry(
            id(5),
            id(1),
            id(4),
            new TurnEndPayload(id(2), TurnEndOutcome.COMPLETED, true, null, null),
            NOW);
    EntryPath path =
        new EntryPath(
            List.of(
                rootEntry(), turnStartEntry(), userMessageEntry(), plainAssistantEntry(), turnEnd));
    return new ThreadSnapshot(thread(threadId, id(5)), path, List.of(), null, List.of(), List.of());
  }

  public static ModelUsage usage() {
    return new ModelUsage(11, 7, 5, 3, 2, 13, 47);
  }

  public static ModelCost cost() {
    BigDecimal zero = BigDecimal.ZERO;
    return new ModelCost("USD", zero, zero, zero, zero, zero, zero, zero);
  }

  public static ProviderResponse toolCallResponse() {
    return new ProviderResponse(
        "",
        "",
        List.of(new ProviderToolCall("call-1", "web_search", "{}")),
        GenerationStopReason.COMPLETE,
        usage(),
        cost(),
        null,
        null,
        null);
  }

  /** 带 ToolCall 的 ASSISTANT 结果（TOOL_ACTIVE 快照 head）。 */
  public static Entry assistantEntry() {
    AgentMessage message =
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(new ToolCallMessageContent("call-1", "web_search", "web_search", "{}")));
    MessagePayload payload =
        new MessagePayload(
            message,
            new AssistantMessageMetadata(GenerationStopReason.COMPLETE, usage(), cost()),
            null);
    return new Entry(id(4), id(1), id(3), payload, NOW);
  }

  public static ToolInvocation waitingApprovalTool() {
    ToolDescriptor descriptor =
        new ToolDescriptor(
            "web_search",
            "1.0",
            "search the web",
            "web_search",
            new InputSchema("search the web", Map.of(), Set.of(), true),
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(30));
    ToolInvocationRequest request =
        new ToolInvocationRequest(
            new ToolCall("call-1", "web_search", "{}"),
            new ToolBinding(
                new AgentToolDefinition(
                    new AgentToolId("test.web-search"), descriptor, ToolVisibility.SELECTABLE),
                new ContributorBinding("test", "web-search", List.of()),
                false,
                null));
    return new ToolInvocation(
        id(100),
        id(10),
        id(4),
        0,
        request.call(),
        request.binding(),
        ToolInvocationStatus.WAITING_APPROVAL,
        0,
        ToolApproval.request(NOW, null),
        null,
        null,
        NOW,
        NOW);
  }

  /** unknown tool immediate FAILED 槽位：binding 为 null，但 durable ToolCall 身份仍必须完整投射。 */
  public static ToolInvocation unknownToolFailedInvocation() {
    return new ToolInvocation(
        id(101),
        id(10),
        id(4),
        0,
        new ToolCall("call-77", "unknown_tool", "{\"x\":1}"),
        null,
        ToolInvocationStatus.FAILED,
        0,
        null,
        null,
        new ToolInvocationError("FAILED", "unknown tool"),
        NOW,
        NOW);
  }

  public static ThreadCommand queuedUserMessageCommand() {
    UserMessageCommandPayload payload =
        new UserMessageCommandPayload(
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("hello"))));
    return new ThreadCommand(
        id(1),
        4,
        payload,
        id(50),
        ThreadCommandPayloadJsonCodec.requestHash(payload),
        null,
        null,
        null,
        NOW);
  }
}
