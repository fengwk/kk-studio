package fun.fengwk.kkstudio.core.harness.thread.service.impl;

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

import fun.fengwk.kkstudio.core.harness.session.service.impl.HarnessSessionDtoConverter;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier;
import fun.fengwk.kkstudio.harness.runtime.thread.InputStatus;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandCoordinator;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandCoordinator.EnqueueResult;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadInputType;
import fun.fengwk.kkstudio.share.model.HarnessThreadAgentSetDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadBootstrapDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadHeadUpdateDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadInputDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadModelSetDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadStopDTO;

import java.time.Instant;
import java.util.Optional;

/**
 * Thin Core wrapper preserves idempotent short-circuit before parsing live definition ids, and
 * isolates after-commit notifier from durable result mapping.
 */
class HarnessThreadCommandServiceImplTest {

  @Test
  void configRetryReturnsPersistedInputWithoutResolvingDeletedDefinition() {
    ThreadCommandCoordinator coordinator = mock(ThreadCommandCoordinator.class);
    ActivationNotifier notifier = mock(ActivationNotifier.class);
    HarnessThreadDtoConverter converter = mock(HarnessThreadDtoConverter.class);
    HarnessThreadCommandServiceImpl service =
        new HarnessThreadCommandServiceImpl(
            coordinator,
            () -> ToolSettings.DEFAULT,
            notifier,
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
    ExecutionTarget target = new ExecutionTarget(ExecutionTargetKind.THREAD, 1);
    HarnessThreadInputDTO expected = new HarnessThreadInputDTO();
    when(coordinator.findExistingInput(1, "same-key"))
        .thenReturn(Optional.of(new EnqueueResult(persisted, target)));
    when(converter.convert(persisted)).thenReturn(expected);

    HarnessThreadAgentSetDTO retry = new HarnessThreadAgentSetDTO();
    retry.setAgentDefinitionId("deleted-definition");
    retry.setClientMessageId("same-key");
    retry.setExpectedExecutionEpoch(3L);

    assertSame(expected, service.queueAgent("1", retry));
    verify(coordinator, never())
        .queueAgent(anyLong(), anyLong(), anyBoolean(), anyString(), anyLong());
    verify(notifier).notifyAfterCommit(target);
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
            mock(ActivationNotifier.class),
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
            mock(ActivationNotifier.class),
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
            mock(ActivationNotifier.class),
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
        .bootstrapThread(anyLong(), anyLong(), anyString(), anyLong(), anyBoolean());
  }
}
