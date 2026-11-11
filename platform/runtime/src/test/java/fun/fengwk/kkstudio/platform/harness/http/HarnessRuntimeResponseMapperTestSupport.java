package fun.fengwk.kkstudio.platform.harness.http;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fun.fengwk.kkstudio.common.result.TextResultContent;
import fun.fengwk.kkstudio.common.schema.InputSchema;
import fun.fengwk.kkstudio.harness.api.StoppedThreadReceipt;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.api.execution.ExecutionExitFact;
import fun.fengwk.kkstudio.harness.api.generation.FailureProjection;
import fun.fengwk.kkstudio.harness.api.generation.GenerationProjection;
import fun.fengwk.kkstudio.harness.api.generation.GenerationStatus;
import fun.fengwk.kkstudio.harness.api.history.AssistantError;
import fun.fengwk.kkstudio.harness.api.history.Entry;
import fun.fengwk.kkstudio.harness.api.history.EntryPath;
import fun.fengwk.kkstudio.harness.api.history.RootPayload;
import fun.fengwk.kkstudio.harness.api.session.AgentMessageContent;
import fun.fengwk.kkstudio.harness.api.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.api.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadLiveState;
import fun.fengwk.kkstudio.harness.api.thread.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.api.tool.ContributorBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolApproval;
import fun.fengwk.kkstudio.harness.api.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.api.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.contributor.api.EnvironmentSupport;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.tool.AgentToolDefinition;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platformtest.harness.HarnessRuntimeTestFixtures;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class HarnessRuntimeResponseMapperTestSupport {

  static final Instant NOW = HarnessRuntimeTestFixtures.NOW;

  private HarnessRuntimeResponseMapperTestSupport() {}

  static UUID id(long value) {
    return new UUID(0L, value);
  }

  static String idText(long value) {
    return id(value).toString();
  }

  static ThreadSnapshot idleSnapshot(BranchSettings settings) {
    Entry root = new Entry(id(1), id(1), null, new RootPayload(settings), NOW);
    return snapshot(
        HarnessRuntimeTestFixtures.thread(id(1)),
        new EntryPath(List.of(root)),
        List.of(),
        null,
        List.of(),
        ThreadLiveState.IDLE_OR_HISTORICAL,
        List.of(),
        0,
        false,
        List.of(),
        false);
  }

  static ThreadSnapshot modelSnapshot(GenerationStatus status) {
    return modelSnapshot(status, List.of(), List.of());
  }

  static ThreadSnapshot modelSnapshot(
      GenerationStatus status, List<ThreadCommand> queued, List<FailureProjection> failures) {
    return modelSnapshot(
        generation(
            status,
            attempt(status),
            4L,
            List.of(new TextMessageContent("partial")),
            terminalError(status),
            null,
            failures),
        queued);
  }

  static ThreadSnapshot modelSnapshot(GenerationProjection generation, List<ThreadCommand> queued) {
    EntryPath path =
        new EntryPath(
            List.of(
                HarnessRuntimeTestFixtures.rootEntry(),
                turnStartEntryForThread(id(1)),
                HarnessRuntimeTestFixtures.userMessageEntry()));
    return snapshot(
        HarnessRuntimeTestFixtures.activeThread(id(1), id(3)),
        path,
        queued,
        generation,
        List.of(),
        ThreadLiveState.DRIVER_ACTIVE,
        List.of(),
        0,
        false,
        List.of(),
        false);
  }

  static ThreadSnapshot toolSnapshot(ToolInvocationStatus status) {
    GenerationProjection generation =
        generation(
            GenerationStatus.SUCCEEDED,
            1,
            5L,
            List.of(new TextMessageContent("answer")),
            null,
            id(4),
            List.of());
    ToolInvocation tool =
        toolInvocation(
            status,
            hostToolBinding(),
            approval(status),
            result(status),
            error(status),
            attempt(status));
    EntryPath path =
        new EntryPath(
            List.of(
                HarnessRuntimeTestFixtures.rootEntry(),
                turnStartEntryForThread(id(1)),
                HarnessRuntimeTestFixtures.userMessageEntry(),
                HarnessRuntimeTestFixtures.assistantEntry()));
    return snapshot(
        HarnessRuntimeTestFixtures.activeThread(id(1), id(4)),
        path,
        List.of(),
        generation,
        List.of(tool),
        ThreadLiveState.TOOL_ACTIVE,
        List.of(),
        0,
        false,
        List.of(),
        false);
  }

  static GenerationProjection generation(
      GenerationStatus status,
      int attempt,
      long sequence,
      List<AgentMessageContent> outputContents,
      AssistantError error,
      UUID resultEntryId,
      List<FailureProjection> failures) {
    return new GenerationProjection(
        id(10),
        id(2),
        id(3),
        status,
        attempt,
        sequence,
        outputContents,
        error,
        resultEntryId,
        NOW,
        NOW.plusSeconds(1),
        failures);
  }

  /** TURN_START 必须归属本次快照的 Thread；generation 归属校验要求二者一致。 */
  static Entry turnStartEntryForThread(UUID threadId) {
    return HarnessRuntimeTestFixtures.turnStartEntry(threadId);
  }

  static ToolInvocation toolInvocation(
      ToolInvocationStatus status,
      ToolBinding binding,
      ToolApproval approval,
      ToolResult result,
      ToolInvocationError error,
      int attempt) {
    ToolInvocation invocation = mock(ToolInvocation.class);
    when(invocation.id()).thenReturn(id(100));
    when(invocation.threadId()).thenReturn(id(10));
    when(invocation.turnStartEntryId()).thenReturn(id(11));
    when(invocation.assistantEntryId()).thenReturn(id(4));
    when(invocation.callIndex()).thenReturn(0);
    when(invocation.status()).thenReturn(status);
    when(invocation.attempt()).thenReturn(attempt);
    when(invocation.call()).thenReturn(new ToolCall("call-1", "bash", "{\"x\":1}"));
    when(invocation.binding()).thenReturn(binding);
    when(invocation.approval()).thenReturn(approval);
    when(invocation.result()).thenReturn(result);
    when(invocation.error()).thenReturn(error);
    when(invocation.createdAt()).thenReturn(NOW);
    when(invocation.updatedAt()).thenReturn(NOW.plusSeconds(1));
    return invocation;
  }

  static ToolBinding environmentToolBinding() {
    return new ToolBinding(
        new AgentToolDefinition(descriptor(), ToolVisibility.SELECTABLE),
        new ContributorBinding("test", "bash"),
        EnvironmentSupport.REQUIRED,
        EnvironmentId.parse("11111111-1111-1111-1111-111111111111"),
        "test-env");
  }

  static ToolBinding hostToolBinding() {
    return new ToolBinding(
        new AgentToolDefinition(descriptor(), ToolVisibility.SELECTABLE),
        new ContributorBinding("test", "bash"),
        EnvironmentSupport.NONE,
        null,
        null);
  }

  static ToolDescriptor descriptor() {
    return new ToolDescriptor(
        "bash",
        "execute a command",
        "bash",
        new InputSchema("command arguments", Map.of(), Set.of(), true),
        ToolSideEffect.READ_ONLY,
        Duration.ofSeconds(30));
  }

  static AssistantError terminalError(GenerationStatus status) {
    return status == GenerationStatus.FAILED
        ? new AssistantError(ProviderErrorKind.TRANSIENT.name(), "failed")
        : null;
  }

  static ToolApproval approval(ToolInvocationStatus status) {
    return switch (status) {
      case WAITING_APPROVAL -> ToolApproval.request(NOW, null);
      case RUNNING, SUCCEEDED -> ToolApproval.notRequired();
      default -> null;
    };
  }

  static ToolResult result(ToolInvocationStatus status) {
    return status == ToolInvocationStatus.SUCCEEDED
        ? new ToolResult("call-1", List.of(new TextResultContent("ok")), false, "{}")
        : null;
  }

  static ToolInvocationError error(ToolInvocationStatus status) {
    return status == ToolInvocationStatus.FAILED
        ? new ToolInvocationError("FAILED", "failed")
        : null;
  }

  static int attempt(GenerationStatus status) {
    return status == GenerationStatus.READY ? 0 : 1;
  }

  static int attempt(ToolInvocationStatus status) {
    return status == ToolInvocationStatus.READY
            || status == ToolInvocationStatus.WAITING_APPROVAL
            || status == ToolInvocationStatus.FAILED
        ? 0
        : 1;
  }

  static ThreadSnapshot withVersion(ThreadSnapshot snapshot, long version) {
    ThreadState thread = snapshot.thread();
    ThreadState changed =
        new ThreadState(
            thread.id(),
            thread.sessionId(),
            thread.parentThreadId(),
            thread.headEntryId(),
            thread.creationRequestHash(),
            thread.name(),
            thread.rootThreadId(),
            thread.yoloEnabled(),
            SpawnTrigger.MODEL_TOOL,
            ThreadConstraints.NONE,
            thread.executionControl(),
            thread.inputThroughSequence(),
            thread.nextCommandSequence(),
            version,
            thread.createdAt(),
            thread.updatedAt());
    return new ThreadSnapshot(
        changed,
        snapshot.entryState(),
        snapshot.entrySlice(),
        snapshot.queuedCommands(),
        snapshot.generation(),
        snapshot.toolSiblings(),
        snapshot.liveState(),
        snapshot.stopReceipts(),
        snapshot.incompleteChildJoins(),
        snapshot.unconsumedAppliedNotifications(),
        snapshot.unconfirmedExecutions(),
        snapshot.hasIncompleteOwnerChildJoin());
  }

  static ThreadSnapshot snapshot(
      ThreadState thread,
      EntryPath path,
      List<ThreadCommand> commands,
      GenerationProjection generation,
      List<ToolInvocation> toolSiblings,
      ThreadLiveState liveState,
      List<StoppedThreadReceipt> stopReceipts,
      int incompleteChildJoins,
      boolean unconsumedAppliedNotifications,
      List<ExecutionExitFact> unconfirmedExecutions,
      boolean hasIncompleteOwnerChildJoin) {
    return HarnessRuntimeTestFixtures.snapshot(
        thread,
        path,
        commands,
        generation,
        toolSiblings,
        liveState,
        stopReceipts,
        incompleteChildJoins,
        unconsumedAppliedNotifications,
        unconfirmedExecutions,
        hasIncompleteOwnerChildJoin);
  }
}
