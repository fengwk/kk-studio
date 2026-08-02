package fun.fengwk.kkstudio.core.ai.runtime.thread.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.thread.HarnessThread;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandCoordinator;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandCoordinator.EnqueueResult;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandCoordinator.StopResult;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.TurnSettings;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadCustomMessageCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadHeadUpdateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadMessageCreateDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopDTO;
import fun.fengwk.kkstudio.share.ai.runtime.HarnessThreadStopResultDTO;

import java.util.List;
import java.util.Optional;

/** Unit coverage for the thin DTO-to-name-reference command boundary. */
class HarnessThreadCommandServiceImplTest {

  @Test
  void createsThreadWithTheSuppliedTitle() {
    ThreadCommandCoordinator coordinator = mock(ThreadCommandCoordinator.class);
    HarnessThreadDtoConverter converter = mock(HarnessThreadDtoConverter.class);
    HarnessThread thread = mock(HarnessThread.class);
    HarnessThreadDTO expected = new HarnessThreadDTO();
    when(coordinator.createThread("title")).thenReturn(thread);
    when(converter.convert(thread)).thenReturn(expected);

    HarnessThreadCommandServiceImpl service =
        new HarnessThreadCommandServiceImpl(coordinator, converter);

    assertSame(expected, service.createThread("title"));
    verify(coordinator).createThread("title");
  }

  @Test
  void updatesHeadAfterParsingDecimalIdsAndEpoch() {
    ThreadCommandCoordinator coordinator = mock(ThreadCommandCoordinator.class);
    HarnessThreadDtoConverter converter = mock(HarnessThreadDtoConverter.class);
    HarnessThread thread = mock(HarnessThread.class);
    HarnessThreadDTO expected = new HarnessThreadDTO();
    when(coordinator.updateHead(5L, 7L, 9L)).thenReturn(thread);
    when(converter.convert(thread)).thenReturn(expected);

    HarnessThreadHeadUpdateDTO request = new HarnessThreadHeadUpdateDTO();
    request.setHeadEntryId("9");
    request.setExpectedExecutionEpoch(7L);

    HarnessThreadCommandServiceImpl service =
        new HarnessThreadCommandServiceImpl(coordinator, converter);

    assertSame(expected, service.updateHead("5", request));
    verify(coordinator).updateHead(5L, 7L, 9L);
  }

  @Test
  void submitsUserAndCustomMessagesWithExactTurnSettings() {
    ThreadCommandCoordinator coordinator = mock(ThreadCommandCoordinator.class);
    HarnessThreadDtoConverter converter = mock(HarnessThreadDtoConverter.class);
    HarnessThreadCommandServiceImpl service =
        new HarnessThreadCommandServiceImpl(coordinator, converter);
    ThreadInput userInput = mock(ThreadInput.class);
    ThreadInput customInput = mock(ThreadInput.class);
    HarnessThreadInputDTO userDTO = new HarnessThreadInputDTO();
    HarnessThreadInputDTO customDTO = new HarnessThreadInputDTO();
    TurnSettings settings = new TurnSettings("agent-a", "environment-a", true);
    when(coordinator.findExistingInput(5L, "user-key")).thenReturn(Optional.empty());
    when(coordinator.findExistingInput(5L, "custom-key")).thenReturn(Optional.empty());
    when(coordinator.submitUserMessage(5L, settings, "hello", "user-key", 3L))
        .thenReturn(new EnqueueResult(userInput));
    when(coordinator.submitCustomMessage(5L, settings, "system", "rules", "custom-key", 3L))
        .thenReturn(new EnqueueResult(customInput));
    when(converter.convert(userInput)).thenReturn(userDTO);
    when(converter.convert(customInput)).thenReturn(customDTO);

    HarnessThreadMessageCreateDTO user = new HarnessThreadMessageCreateDTO();
    user.setContent("hello");
    user.setAgentName("agent-a");
    user.setEnvironmentName("environment-a");
    user.setYoloEnabled(true);
    user.setClientMessageId("user-key");
    user.setExpectedExecutionEpoch(3L);
    HarnessThreadCustomMessageCreateDTO custom = new HarnessThreadCustomMessageCreateDTO();
    custom.setRole("system");
    custom.setContent("rules");
    custom.setAgentName("agent-a");
    custom.setEnvironmentName("environment-a");
    custom.setYoloEnabled(true);
    custom.setClientMessageId("custom-key");
    custom.setExpectedExecutionEpoch(3L);

    assertSame(userDTO, service.submitUserMessage("5", user));
    assertSame(customDTO, service.submitCustomMessage("5", custom));
    verify(coordinator).submitUserMessage(5L, settings, "hello", "user-key", 3L);
    verify(coordinator).submitCustomMessage(5L, settings, "system", "rules", "custom-key", 3L);
  }

  @Test
  void idempotentRetriesReturnExistingInputsBeforeParsingChangedSettings() {
    ThreadCommandCoordinator coordinator = mock(ThreadCommandCoordinator.class);
    HarnessThreadDtoConverter converter = mock(HarnessThreadDtoConverter.class);
    HarnessThreadCommandServiceImpl service =
        new HarnessThreadCommandServiceImpl(coordinator, converter);
    ThreadInput userInput = mock(ThreadInput.class);
    ThreadInput customInput = mock(ThreadInput.class);
    HarnessThreadInputDTO userDTO = new HarnessThreadInputDTO();
    HarnessThreadInputDTO customDTO = new HarnessThreadInputDTO();
    when(coordinator.findExistingInput(5L, "user-key"))
        .thenReturn(Optional.of(new EnqueueResult(userInput)));
    when(coordinator.findExistingInput(5L, "custom-key"))
        .thenReturn(Optional.of(new EnqueueResult(customInput)));
    when(converter.convert(userInput)).thenReturn(userDTO);
    when(converter.convert(customInput)).thenReturn(customDTO);

    HarnessThreadMessageCreateDTO user = new HarnessThreadMessageCreateDTO();
    user.setClientMessageId("user-key");
    HarnessThreadCustomMessageCreateDTO custom = new HarnessThreadCustomMessageCreateDTO();
    custom.setClientMessageId("custom-key");

    assertSame(userDTO, service.submitUserMessage("5", user));
    assertSame(customDTO, service.submitCustomMessage("5", custom));
    verify(coordinator).findExistingInput(5L, "user-key");
    verify(coordinator).findExistingInput(5L, "custom-key");
    verifyNoMoreInteractions(coordinator);
  }

  @Test
  void stopsAndMapsCancelledInputs() {
    ThreadCommandCoordinator coordinator = mock(ThreadCommandCoordinator.class);
    HarnessThreadDtoConverter converter = mock(HarnessThreadDtoConverter.class);
    HarnessThreadCommandServiceImpl service =
        new HarnessThreadCommandServiceImpl(coordinator, converter);
    ThreadInput cancelled = mock(ThreadInput.class);
    HarnessThreadInputDTO cancelledDTO = new HarnessThreadInputDTO();
    when(coordinator.stop(5L, 3L)).thenReturn(new StopResult(4L, List.of(cancelled)));
    when(converter.convert(cancelled)).thenReturn(cancelledDTO);

    HarnessThreadStopDTO request = new HarnessThreadStopDTO();
    request.setExpectedExecutionEpoch(3L);

    HarnessThreadStopResultDTO result = service.stop("5", request);

    assertEquals(4L, result.getExecutionEpoch());
    assertEquals(List.of(cancelledDTO), result.getCancelledInputs());
    verify(coordinator).stop(5L, 3L);
  }

  @Test
  void rejectsMissingOrInvalidEpochAndMissingYoloBeforeCoordinator() {
    ThreadCommandCoordinator coordinator = mock(ThreadCommandCoordinator.class);
    HarnessThreadCommandServiceImpl service =
        new HarnessThreadCommandServiceImpl(coordinator, mock(HarnessThreadDtoConverter.class));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.updateHead("1", new HarnessThreadHeadUpdateDTO()));
    HarnessThreadStopDTO negative = new HarnessThreadStopDTO();
    negative.setExpectedExecutionEpoch(-1L);
    assertThrows(IllegalArgumentException.class, () -> service.stop("1", negative));

    HarnessThreadMessageCreateDTO missingYolo = new HarnessThreadMessageCreateDTO();
    missingYolo.setContent("hello");
    missingYolo.setAgentName("agent");
    missingYolo.setClientMessageId("key");
    missingYolo.setExpectedExecutionEpoch(0L);
    when(coordinator.findExistingInput(1L, "key")).thenReturn(Optional.empty());
    assertThrows(IllegalArgumentException.class, () -> service.submitUserMessage("1", missingYolo));

    verify(coordinator).findExistingInput(1L, "key");
    verifyNoMoreInteractions(coordinator);
  }
}
