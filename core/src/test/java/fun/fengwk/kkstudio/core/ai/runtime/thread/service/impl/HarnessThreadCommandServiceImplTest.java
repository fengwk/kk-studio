package fun.fengwk.kkstudio.core.ai.runtime.thread.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.runtime.session.service.impl.HarnessSessionDtoConverter;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.runtime.session.Session;
import fun.fengwk.kkstudio.harness.runtime.session.SessionEntry;
import fun.fengwk.kkstudio.harness.runtime.thread.HarnessThread;
import fun.fengwk.kkstudio.harness.runtime.thread.InputStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandCoordinator;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandCoordinator.BootstrapResult;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandCoordinator.EnqueueResult;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandCoordinator.StopResult;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessSessionDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadAgentSetDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadBootstrapDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadBootstrapResultDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCustomMessageCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadHeadUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadMessageCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadModelSetDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopResultDTO;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Thin Core wrapper preserves idempotent short-circuit before parsing live definition ids and maps
 * durable results without activation side effects.
 */
class HarnessThreadCommandServiceImplTest {

  /** Thread creation maps the unbound Runtime record through the Core DTO boundary. */
  @Test
  void createsThreadAndMapsRuntimeResult() {
    ThreadCommandCoordinator coordinator = mock(ThreadCommandCoordinator.class);
    HarnessThreadDtoConverter converter = mock(HarnessThreadDtoConverter.class);
    HarnessThread thread = mock(HarnessThread.class);
    HarnessThreadDTO expected = new HarnessThreadDTO();
    when(coordinator.createThread()).thenReturn(thread);
    when(converter.convert(thread)).thenReturn(expected);
    HarnessThreadCommandServiceImpl service =
        new HarnessThreadCommandServiceImpl(
            coordinator,
            () -> ToolSettings.DEFAULT,
            converter,
            mock(HarnessSessionDtoConverter.class));

    assertSame(expected, service.createThread());
  }

  /**
   * Bootstrap maps both atomically-created Session and rebound Thread from one coordinator call.
   */
  @Test
  void bootstrapsThreadAndMapsAtomicResult() {
    ThreadCommandCoordinator coordinator = mock(ThreadCommandCoordinator.class);
    HarnessThreadDtoConverter converter = mock(HarnessThreadDtoConverter.class);
    HarnessSessionDtoConverter sessionConverter = mock(HarnessSessionDtoConverter.class);
    Session session = mock(Session.class);
    HarnessThread thread = mock(HarnessThread.class);
    BootstrapResult result =
        new BootstrapResult(session, mock(SessionEntry.class), mock(SessionEntry.class), thread);
    HarnessSessionDTO sessionDTO = new HarnessSessionDTO();
    HarnessThreadDTO threadDTO = new HarnessThreadDTO();
    when(coordinator.bootstrapThread(5L, 7L, "title", 11L, "env-a", true)).thenReturn(result);
    when(sessionConverter.convert(session)).thenReturn(sessionDTO);
    when(converter.convert(thread)).thenReturn(threadDTO);
    HarnessThreadCommandServiceImpl service =
        new HarnessThreadCommandServiceImpl(
            coordinator, () -> ToolSettings.DEFAULT, converter, sessionConverter);
    HarnessThreadBootstrapDTO request = new HarnessThreadBootstrapDTO();
    request.setTitle("title");
    request.setAgentDefinitionId("11");
    request.setEnvironmentName("env-a");
    request.setYoloEnabled(true);
    request.setExpectedExecutionEpoch(7L);
    when(converter.convert(thread, null)).thenReturn(threadDTO);

    HarnessThreadBootstrapResultDTO response = service.bootstrapThread("5", request);

    assertSame(sessionDTO, response.getSession());
    assertSame(threadDTO, response.getThread());
  }

  /** Head updates preserve nullable head semantics while always forwarding the caller epoch. */
  @Test
  void updatesOrClearsHeadAndMapsRuntimeResult() {
    ThreadCommandCoordinator coordinator = mock(ThreadCommandCoordinator.class);
    HarnessThreadDtoConverter converter = mock(HarnessThreadDtoConverter.class);
    HarnessThread bound = mock(HarnessThread.class);
    HarnessThread unbound = mock(HarnessThread.class);
    HarnessThreadDTO boundDTO = new HarnessThreadDTO();
    HarnessThreadDTO unboundDTO = new HarnessThreadDTO();
    when(coordinator.updateHead(5L, 7L, 9L)).thenReturn(bound);
    when(coordinator.updateHead(5L, 8L, null)).thenReturn(unbound);
    when(converter.convert(bound)).thenReturn(boundDTO);
    when(converter.convert(unbound)).thenReturn(unboundDTO);
    HarnessThreadCommandServiceImpl service =
        new HarnessThreadCommandServiceImpl(
            coordinator,
            () -> ToolSettings.DEFAULT,
            converter,
            mock(HarnessSessionDtoConverter.class));
    HarnessThreadHeadUpdateDTO bind = new HarnessThreadHeadUpdateDTO();
    bind.setHeadEntryId("9");
    bind.setExpectedExecutionEpoch(7L);
    HarnessThreadHeadUpdateDTO clear = new HarnessThreadHeadUpdateDTO();
    clear.setExpectedExecutionEpoch(8L);

    assertSame(boundDTO, service.updateHead("5", bind));
    assertSame(unboundDTO, service.updateHead("5", clear));
  }

  /** Message and stop facades forward epoch-fenced commands and map their durable outcomes. */
  @Test
  void submitsMessagesAndStopsThread() {
    ThreadCommandCoordinator coordinator = mock(ThreadCommandCoordinator.class);
    HarnessThreadDtoConverter converter = mock(HarnessThreadDtoConverter.class);
    HarnessThreadCommandServiceImpl service =
        new HarnessThreadCommandServiceImpl(
            coordinator,
            () -> ToolSettings.DEFAULT,
            converter,
            mock(HarnessSessionDtoConverter.class));
    ThreadInput userInput = mock(ThreadInput.class);
    ThreadInput customInput = mock(ThreadInput.class);
    ThreadInput cancelledInput = mock(ThreadInput.class);
    HarnessThreadInputDTO userDTO = new HarnessThreadInputDTO();
    HarnessThreadInputDTO customDTO = new HarnessThreadInputDTO();
    HarnessThreadInputDTO cancelledDTO = new HarnessThreadInputDTO();
    when(coordinator.submitUserMessage(5L, "hello", "user-key", 3L))
        .thenReturn(new EnqueueResult(userInput));
    when(coordinator.submitCustomMessage(5L, "system", "rules", "custom-key", 3L))
        .thenReturn(new EnqueueResult(customInput));
    when(coordinator.stop(5L, 3L)).thenReturn(new StopResult(4L, List.of(cancelledInput)));
    when(converter.convert(userInput)).thenReturn(userDTO);
    when(converter.convert(customInput)).thenReturn(customDTO);
    when(converter.convert(cancelledInput)).thenReturn(cancelledDTO);
    HarnessThreadMessageCreateDTO user = new HarnessThreadMessageCreateDTO();
    user.setContent("hello");
    user.setClientMessageId("user-key");
    user.setExpectedExecutionEpoch(3L);
    HarnessThreadCustomMessageCreateDTO custom = new HarnessThreadCustomMessageCreateDTO();
    custom.setRole("system");
    custom.setContent("rules");
    custom.setClientMessageId("custom-key");
    custom.setExpectedExecutionEpoch(3L);
    HarnessThreadStopDTO stop = new HarnessThreadStopDTO();
    stop.setExpectedExecutionEpoch(3L);

    assertSame(userDTO, service.submitUserMessage("5", user));
    assertSame(customDTO, service.submitCustomMessage("5", custom));
    HarnessThreadStopResultDTO stopped = service.stop("5", stop);

    assertEquals(4L, stopped.getExecutionEpoch());
    assertEquals(List.of(cancelledDTO), stopped.getCancelledInputs());
  }

  @Test
  void configRetryReturnsPersistedInputWithoutResolvingDeletedDefinition() {
    ThreadCommandCoordinator coordinator = mock(ThreadCommandCoordinator.class);
    HarnessThreadDtoConverter converter = mock(HarnessThreadDtoConverter.class);
    HarnessThreadCommandServiceImpl service =
        new HarnessThreadCommandServiceImpl(
            coordinator,
            () -> ToolSettings.DEFAULT,
            converter,
            mock(HarnessSessionDtoConverter.class));
    ThreadInputPayload configPayload = mock(ThreadInputPayload.class);
    when(configPayload.type()).thenReturn(ThreadInputType.SET_AGENT);
    ThreadInput persisted =
        new ThreadInput(
            9,
            1,
            1,
            ThreadInputType.SET_AGENT,
            configPayload,
            "same-key",
            InputStatus.QUEUED,
            Instant.parse("2026-07-24T00:00:00Z"),
            null);
    HarnessThreadInputDTO expected = new HarnessThreadInputDTO();
    when(coordinator.findExistingInput(1, "same-key"))
        .thenReturn(Optional.of(new EnqueueResult(persisted)));
    when(converter.convert(persisted)).thenReturn(expected);

    HarnessThreadAgentSetDTO retry = new HarnessThreadAgentSetDTO();
    retry.setAgentDefinitionId("deleted-definition");
    retry.setClientMessageId("same-key");
    retry.setExpectedExecutionEpoch(3L);

    assertSame(expected, service.queueAgent("1", retry));
    verify(coordinator, never())
        .queueAgent(anyLong(), anyLong(), anyBoolean(), anyString(), anyLong());
  }

  /** Invalid decimal model ids are rejected at the Core boundary before runtime orchestration. */
  @Test
  void rejectsInvalidModelIdBeforeCoordinator() {
    ThreadCommandCoordinator coordinator = mock(ThreadCommandCoordinator.class);
    when(coordinator.findExistingInput(1L, "k")).thenReturn(Optional.empty());
    HarnessThreadCommandServiceImpl service =
        new HarnessThreadCommandServiceImpl(
            coordinator,
            () -> ToolSettings.DEFAULT,
            mock(HarnessThreadDtoConverter.class),
            mock(HarnessSessionDtoConverter.class));
    HarnessThreadModelSetDTO request = new HarnessThreadModelSetDTO();
    request.setModelId("not-a-number");
    request.setClientMessageId("k");
    request.setExpectedExecutionEpoch(0L);
    assertThrows(IllegalArgumentException.class, () -> service.queueModel("1", request));
    verify(coordinator, never())
        .queueModel(anyLong(), anyLong(), anyString(), anyString(), anyLong());
  }

  /** Head update and stop are external mutations: a missing/negative epoch is a 400-class error. */
  @Test
  void rejectsMissingOrNegativeExpectedExecutionEpoch() {
    ThreadCommandCoordinator coordinator = mock(ThreadCommandCoordinator.class);
    HarnessThreadCommandServiceImpl service =
        new HarnessThreadCommandServiceImpl(
            coordinator,
            () -> ToolSettings.DEFAULT,
            mock(HarnessThreadDtoConverter.class),
            mock(HarnessSessionDtoConverter.class));

    HarnessThreadHeadUpdateDTO missing = new HarnessThreadHeadUpdateDTO();
    assertThrows(IllegalArgumentException.class, () -> service.updateHead("1", missing));
    HarnessThreadHeadUpdateDTO negative = new HarnessThreadHeadUpdateDTO();
    negative.setExpectedExecutionEpoch(-1L);
    assertThrows(IllegalArgumentException.class, () -> service.updateHead("1", negative));
    assertThrows(
        IllegalArgumentException.class, () -> service.stop("1", new HarnessThreadStopDTO()));
    verify(coordinator, never()).updateHead(anyLong(), anyLong(), any());
    verify(coordinator, never()).stop(anyLong(), anyLong());
  }

  /** Bootstrap requires an explicit YOLO policy and a decimal agent definition id. */
  @Test
  void rejectsIncompleteBootstrapRequest() {
    ThreadCommandCoordinator coordinator = mock(ThreadCommandCoordinator.class);
    HarnessThreadCommandServiceImpl service =
        new HarnessThreadCommandServiceImpl(
            coordinator,
            () -> ToolSettings.DEFAULT,
            mock(HarnessThreadDtoConverter.class),
            mock(HarnessSessionDtoConverter.class));

    HarnessThreadBootstrapDTO missingYolo = new HarnessThreadBootstrapDTO();
    missingYolo.setAgentDefinitionId("1");
    missingYolo.setExpectedExecutionEpoch(0L);
    assertThrows(IllegalArgumentException.class, () -> service.bootstrapThread("1", missingYolo));

    HarnessThreadBootstrapDTO badAgent = new HarnessThreadBootstrapDTO();
    badAgent.setAgentDefinitionId("not-a-number");
    badAgent.setYoloEnabled(false);
    badAgent.setExpectedExecutionEpoch(0L);
    assertThrows(IllegalArgumentException.class, () -> service.bootstrapThread("1", badAgent));
    verify(coordinator, never())
        .bootstrapThread(anyLong(), anyLong(), anyString(), anyLong(), anyString(), anyBoolean());
  }
}
