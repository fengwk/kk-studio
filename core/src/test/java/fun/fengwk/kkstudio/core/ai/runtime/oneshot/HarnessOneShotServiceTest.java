package fun.fengwk.kkstudio.core.ai.runtime.oneshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import fun.fengwk.kkstudio.core.ai.runtime.task.AgentBranchSettingsMaterializer;
import fun.fengwk.kkstudio.core.ai.runtime.task.SubagentConfig;
import fun.fengwk.kkstudio.harness.runtime.CreateThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.CreatedThread;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntime.NewCommandPreflight;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeConflictException;
import fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeNotFoundException;
import fun.fengwk.kkstudio.harness.runtime.StopCommand;
import fun.fengwk.kkstudio.harness.runtime.ThreadSnapshot;
import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.ModelSelection;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnEndOutcome;
import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantAbortedPayload;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantError;
import fun.fengwk.kkstudio.harness.runtime.history.AssistantErrorPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPath;
import fun.fengwk.kkstudio.harness.runtime.history.EntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.history.RootPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndPayload;
import fun.fengwk.kkstudio.harness.runtime.history.TurnEndReason;
import fun.fengwk.kkstudio.harness.runtime.history.TurnStartPayload;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.AssistantMessageMetadata;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandBatch;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** one-shot service 证明 root 配置、同批消息、恢复观察、终态文本与 timeout stop。 */
class HarnessOneShotServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-10T00:00:00Z");

  private static UUID id(long value) {
    return new UUID(0L, value);
  }

  private static final EnvironmentName ENVIRONMENT = new EnvironmentName("h3-prompt");
  private static final BranchSettings SETTINGS =
      new BranchSettings(
          ENVIRONMENT,
          "h3-agent",
          new ModelSelection("provider", "model", "default"),
          List.of("web-search"));

  private HarnessRuntime runtime;
  private AgentBranchSettingsMaterializer materializer;
  private HarnessOneShotService service;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    runtime = mock(HarnessRuntime.class);
    materializer = mock(AgentBranchSettingsMaterializer.class);
    ObjectProvider<HarnessRuntime> runtimes = mock(ObjectProvider.class);
    when(runtimes.getIfAvailable()).thenReturn(runtime);
    when(materializer.materialize(any(), any(), any(Integer.class), any())).thenReturn(SETTINGS);
    service =
        new HarnessOneShotService(
            runtimes,
            materializer,
            new SubagentConfig(2, 2, null, Duration.ZERO, 10, Duration.ofMillis(10)),
            Duration.ofMillis(1));
  }

  @Test
  void submitsSystemAndStructuredUserInOneBatchWithNoTools() {
    Entry root = new Entry(id(2), id(1), null, new RootPayload(SETTINGS, null), NOW);
    ThreadState thread = new ThreadState(id(3), root.id(), false, 1L, 0L, NOW, NOW);
    when(runtime.createThread(any(CreateThreadCommand.class)))
        .thenReturn(new CreatedThread(new Session(id(1), NOW), root, thread));

    UUID threadId =
        service.submit(
            "h3-agent",
            ENVIRONMENT,
            "system",
            new AgentMessage(
                AgentMessageRole.USER,
                List.of(new TextMessageContent("user"), new TextMessageContent(" media"))));

    assertEquals(id(3), threadId);
    ArgumentCaptor<CreateThreadCommand> create = ArgumentCaptor.forClass(CreateThreadCommand.class);
    verify(runtime).createThread(create.capture());
    assertEquals(List.of(), create.getValue().branchSettings().activeTools());
    ArgumentCaptor<ThreadCommandBatch> batch = ArgumentCaptor.forClass(ThreadCommandBatch.class);
    verify(runtime).enqueueCommands(batch.capture(), any(NewCommandPreflight.class));
    assertEquals(2, batch.getValue().commands().size());
    assertEquals(
        AgentMessageRole.SYSTEM,
        ((CustomMessageCommandPayload) batch.getValue().commands().get(0).payload())
            .message()
            .role());
    assertEquals(
        AgentMessageRole.USER,
        ((CustomMessageCommandPayload) batch.getValue().commands().get(1).payload())
            .message()
            .role());
  }

  @Test
  void passesPreflightThroughAndKeepsClientIdsAndHashes() {
    Entry root = new Entry(id(2), id(1), null, new RootPayload(SETTINGS, null), NOW);
    ThreadState thread = new ThreadState(id(3), root.id(), false, 1L, 0L, NOW, NOW);
    when(runtime.createThread(any(CreateThreadCommand.class)))
        .thenReturn(new CreatedThread(new Session(id(1), NOW), root, thread));

    NewCommandPreflight preflight = (tx, sessionId, commands) -> List.copyOf(commands);
    UUID threadId =
        service.submit(
            "h3-agent",
            ENVIRONMENT,
            "system",
            new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("user"))),
            preflight);

    assertEquals(id(3), threadId);
    ArgumentCaptor<ThreadCommandBatch> batch = ArgumentCaptor.forClass(ThreadCommandBatch.class);
    verify(runtime).enqueueCommands(batch.capture(), eq(preflight));
    List<NewThreadCommand> prepared = preflight.prepare(null, id(1), batch.getValue().commands());
    assertEquals(batch.getValue().commands(), prepared);
  }

  @Test
  void resumesByThreadIdAndExtractsLastAssistantText() {
    ThreadSnapshot completed = completed("final prompt");
    when(runtime.getThreadSnapshot(id(3))).thenReturn(completed);

    assertEquals("final prompt", service.await(id(3), Duration.ofSeconds(1), () -> true));
    verify(runtime).getThreadSnapshot(id(3));
  }

  @Test
  void timeoutStopsBestEffortAndEmptyTextFails() {
    ThreadSnapshot idle = idle();
    when(runtime.getThreadSnapshot(id(3))).thenReturn(idle);

    assertThrows(
        IllegalStateException.class, () -> service.await(id(3), Duration.ofNanos(1), () -> true));
    verify(runtime).stop(any(StopCommand.class));

    ThreadSnapshot completed = completed(" ");
    when(runtime.getThreadSnapshot(id(4))).thenReturn(completed);
    assertThrows(
        IllegalStateException.class, () -> service.await(id(4), Duration.ofSeconds(1), () -> true));
  }

  @Test
  void validatesInputsAndStopsWhenCallerBecomesInactive() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            service.submit(
                "h3-agent",
                ENVIRONMENT,
                "system",
                new AgentMessage(
                    AgentMessageRole.ASSISTANT, List.of(new TextMessageContent("wrong role")))));
    assertThrows(
        NullPointerException.class, () -> service.await(null, Duration.ofSeconds(1), () -> true));

    ThreadSnapshot idle = idle();
    when(runtime.getThreadSnapshot(id(3))).thenReturn(idle);
    assertThrows(
        IllegalStateException.class,
        () -> service.await(id(3), Duration.ofSeconds(1), () -> false));
    verify(runtime).stop(any(StopCommand.class));

    assertThrows(NullPointerException.class, () -> service.stop(null));
    verify(runtime, never()).getThreadSnapshot(null);
  }

  @Test
  void reportsTerminalFailureAndBestEffortStopHandlesRaces() {
    ThreadSnapshot error =
        terminalFailure(
            new AssistantErrorPayload(new AssistantError("PROVIDER_FAILED", "provider boom"), null),
            TurnEndOutcome.FAILED);
    ThreadSnapshot aborted =
        terminalFailure(
            new AssistantAbortedPayload(
                new AgentMessage(
                    AgentMessageRole.ASSISTANT,
                    List.of(new TextMessageContent("partial response")))),
            TurnEndOutcome.STOPPED);
    when(runtime.getThreadSnapshot(id(5))).thenReturn(error);
    when(runtime.getThreadSnapshot(id(6))).thenReturn(aborted);
    assertThrows(
        IllegalStateException.class, () -> service.await(id(5), Duration.ofSeconds(1), () -> true));
    assertThrows(
        IllegalStateException.class, () -> service.await(id(6), Duration.ofSeconds(1), () -> true));

    ThreadSnapshot idle = idle();
    when(runtime.getThreadSnapshot(id(9)))
        .thenReturn(idle)
        .thenThrow(new HarnessRuntimeNotFoundException("gone"));
    doThrow(
            new HarnessRuntimeConflictException(
                HarnessRuntimeConflictException.Reason.STALE_REVISION, "stale"))
        .when(runtime)
        .stop(any(StopCommand.class));
    service.stop(id(9));
  }

  private static ThreadSnapshot idle() {
    Entry root = new Entry(id(2), id(1), null, new RootPayload(SETTINGS, null), NOW);
    return new ThreadSnapshot(
        new ThreadState(id(3), root.id(), false, 1L, 0L, NOW, NOW),
        new EntryPath(List.of(root)),
        List.of(),
        null,
        List.of(),
        List.of());
  }

  private static ThreadSnapshot completed(String text) {
    Entry root = new Entry(id(2), id(1), null, new RootPayload(SETTINGS, null), NOW);
    Entry turn =
        new Entry(
            id(4), id(1), root.id(), new TurnStartPayload(TurnStartReason.INPUT, SETTINGS), NOW);
    Entry user =
        new Entry(
            id(5),
            id(1),
            turn.id(),
            new MessagePayload(
                new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("request"))),
                null,
                null),
            NOW);
    AssistantMessageMetadata metadata = mock(AssistantMessageMetadata.class);
    when(metadata.stopReason()).thenReturn(ProviderStopReason.COMPLETED);
    Entry assistant =
        new Entry(
            id(6),
            id(1),
            user.id(),
            new MessagePayload(
                new AgentMessage(AgentMessageRole.ASSISTANT, List.of(new TextMessageContent(text))),
                metadata,
                null),
            NOW);
    Entry end =
        new Entry(
            id(7),
            id(1),
            assistant.id(),
            new TurnEndPayload(turn.id(), TurnEndOutcome.COMPLETED, false, null, null),
            NOW);
    return new ThreadSnapshot(
        new ThreadState(id(3), end.id(), false, 2L, 2L, NOW, NOW),
        new EntryPath(List.of(root, turn, user, assistant, end)),
        List.of(),
        null,
        List.of(),
        List.of());
  }

  private static ThreadSnapshot terminalFailure(EntryPayload failure, TurnEndOutcome outcome) {
    Entry root = new Entry(id(2), id(1), null, new RootPayload(SETTINGS, null), NOW);
    Entry turn =
        new Entry(
            id(4), id(1), root.id(), new TurnStartPayload(TurnStartReason.INPUT, SETTINGS), NOW);
    Entry user =
        new Entry(
            id(5),
            id(1),
            turn.id(),
            new MessagePayload(
                new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("request"))),
                null,
                null),
            NOW);
    Entry failureEntry = new Entry(id(6), id(1), user.id(), failure, NOW);
    Entry end =
        new Entry(
            id(7),
            id(1),
            failureEntry.id(),
            new TurnEndPayload(
                turn.id(),
                outcome,
                false,
                outcome == TurnEndOutcome.FAILED
                    ? TurnEndReason.TURN_FAILED
                    : TurnEndReason.USER_STOP,
                outcome == TurnEndOutcome.STOPPED ? id(3) : null),
            NOW);
    return new ThreadSnapshot(
        new ThreadState(id(3), end.id(), false, 2L, 2L, NOW, NOW),
        new EntryPath(List.of(root, turn, user, failureEntry, end)),
        List.of(),
        null,
        List.of(),
        List.of());
  }
}
