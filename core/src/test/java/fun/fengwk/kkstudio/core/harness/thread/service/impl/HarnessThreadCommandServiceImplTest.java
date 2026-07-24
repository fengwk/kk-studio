package fun.fengwk.kkstudio.core.harness.thread.service.impl;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.harness.thread.command.RuntimeConfigSnapshotResolver;
import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.kernel.thread.InputStatus;
import fun.fengwk.kkstudio.harness.kernel.thread.ThreadInput;
import fun.fengwk.kkstudio.harness.kernel.thread.ThreadInputPayload;
import fun.fengwk.kkstudio.harness.kernel.thread.ThreadInputType;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.runtime.port.ActivationNotifier;
import fun.fengwk.kkstudio.harness.runtime.thread.ThreadCommandTransactions;
import fun.fengwk.kkstudio.share.model.HarnessThreadAgentSetDTO;
import fun.fengwk.kkstudio.share.model.HarnessThreadInputDTO;

import java.time.Instant;
import java.util.Optional;

/** 同一幂等键必须在触碰 live config resolver 前短路。 */
class HarnessThreadCommandServiceImplTest {

  @Test
  void configRetryReturnsPersistedInputWithoutResolvingDeletedDefinition() {
    ThreadCommandTransactions transactions = mock(ThreadCommandTransactions.class);
    RuntimeConfigSnapshotResolver resolver = mock(RuntimeConfigSnapshotResolver.class);
    ActivationNotifier notifier = mock(ActivationNotifier.class);
    KernelHarnessThreadDtoConverter converter = mock(KernelHarnessThreadDtoConverter.class);
    HarnessThreadCommandServiceImpl service =
        new HarnessThreadCommandServiceImpl(
            transactions, resolver, () -> ToolSettings.DEFAULT, notifier, converter);
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
    when(transactions.findExistingInput(1, "same-key"))
        .thenReturn(Optional.of(new ThreadCommandTransactions.EnqueueResult(persisted, target)));
    when(converter.convert(persisted)).thenReturn(expected);

    HarnessThreadAgentSetDTO retry = new HarnessThreadAgentSetDTO();
    retry.setAgentDefinitionId("deleted-definition");
    retry.setClientMessageId("same-key");

    assertSame(expected, service.queueAgent("1", retry));
    verifyNoInteractions(resolver);
    verify(notifier).notifyAfterCommit(target);
  }
}
