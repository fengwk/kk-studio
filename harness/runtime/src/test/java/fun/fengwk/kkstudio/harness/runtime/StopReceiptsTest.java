package fun.fengwk.kkstudio.harness.runtime;

import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.CREATION_REQUEST_HASH;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T0;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.T1;
import static fun.fengwk.kkstudio.harness.runtime.HarnessRuntimeTestSupport.userMessagePayload;
import static fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds.id;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.api.CancelledThreadInput;
import fun.fengwk.kkstudio.harness.api.StoppedThreadReceipt;
import fun.fengwk.kkstudio.harness.api.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.NotificationCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.api.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.api.command.UserMessageCommandPayload;
import fun.fengwk.kkstudio.harness.api.history.NotificationKind;
import fun.fengwk.kkstudio.harness.api.session.AgentMessage;
import fun.fengwk.kkstudio.harness.api.thread.SpawnTrigger;
import fun.fengwk.kkstudio.harness.api.thread.ThreadConstraints;
import fun.fengwk.kkstudio.harness.api.thread.ThreadExecutionControl;
import fun.fengwk.kkstudio.harness.api.thread.ThreadState;
import fun.fengwk.kkstudio.harness.codec.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.spi.store.UuidOrder;

import java.util.List;
import java.util.UUID;

/** Stop 回执组装：人工输入筛选、节点回执、结果排序与 replay 保序。 */
class StopReceiptsTest {

  @Test
  void cancelledInputsKeepOnlyHumanInputsInSequenceOrder() {
    ThreadCommand user = command(1L, userMessagePayload("first"));
    ThreadCommand custom = command(2L, new CustomMessageCommandPayload(userMessage("custom")));
    ThreadCommand notification =
        command(
            3L,
            new NotificationCommandPayload(
                id(11L), NotificationKind.SUBAGENT_RESULT, id(12L), userMessage("notice")));
    ThreadCommand setting = command(4L, new SetAgentCommandPayload("agent"));

    List<CancelledThreadInput> inputs =
        StopReceipts.cancelledInputs(List.of(user, custom, notification, setting));

    assertEquals(List.of(1L, 2L), inputs.stream().map(CancelledThreadInput::sequence).toList());
    assertTrue(inputs.get(0).payload() instanceof UserMessageCommandPayload);
    assertTrue(inputs.get(1).payload() instanceof CustomMessageCommandPayload);
  }

  @Test
  void nodeReceiptCarriesAllCancelledCountsButOnlyHumanDrafts() {
    ThreadState stopped = stoppedThread(id(21L));
    List<ThreadCommand> cancelled =
        List.of(
            command(1L, userMessagePayload("first")),
            command(2L, new CustomMessageCommandPayload(userMessage("custom"))));

    StoppedThreadReceipt receipt = StopReceipts.nodeReceipt(stopped, id(22L), id(23L), cancelled);

    assertEquals(stopped.id(), receipt.threadId());
    assertEquals(id(22L), receipt.stopRequestId());
    assertEquals(id(23L), receipt.stoppedTurnEndEntryId());
    assertEquals(3, receipt.cancelledCommandCount());
    assertEquals(
        List.of(1L, 2L),
        receipt.cancelledInputs().stream().map(CancelledThreadInput::sequence).toList());
  }

  @Test
  void commitSortsFirstStopReceiptsAndCarriesLocalCancellation() {
    ThreadState target = stoppedThread(id(31L));
    StoppedThreadReceipt later = receipt(id(33L));
    StoppedThreadReceipt earlier = receipt(id(32L));

    StopControl.Commit commit =
        StopReceipts.commit(target, List.of(later, earlier), List.of(id(41L)), List.of(id(42L)));

    assertFalse(commit.result().replayed());
    assertEquals(target, commit.result().thread());
    assertEquals(List.of(earlier, later), commit.result().stoppedThreads());
    assertEquals(List.of(id(41L)), commit.driverExecutionIds());
    assertEquals(List.of(id(42L)), commit.toolExecutionIds());
  }

  @Test
  void replayKeepsPersistedReceiptOrderAndCancelsNothingLocally() {
    ThreadState target = stoppedThread(id(51L));
    List<StoppedThreadReceipt> stored = List.of(receipt(id(53L)), receipt(id(52L)));

    StopControl.Commit commit = StopReceipts.replay(target, stored);

    assertTrue(commit.result().replayed());
    assertEquals(target, commit.result().thread());
    assertEquals(stored, commit.result().stoppedThreads());
    assertTrue(commit.driverExecutionIds().isEmpty());
    assertTrue(commit.toolExecutionIds().isEmpty());
  }

  @Test
  void targetReceiptIsRequiredForFirstStop() {
    assertDoesNotThrow(() -> StopReceipts.requireTargetReceipt(List.of(receipt(id(61L))), id(61L)));
    assertThrows(
        IllegalStateException.class,
        () -> StopReceipts.requireTargetReceipt(List.of(receipt(id(61L))), id(62L)));
  }

  @Test
  void receiptOrderFollowsUuidOrder() {
    List<UUID> sortedIds =
        List.of(id(71L), id(72L), id(73L)).stream().sorted(UuidOrder.COMPARATOR).toList();
    assertEquals(
        sortedIds,
        StopReceipts.commit(
                stoppedThread(id(71L)),
                List.of(receipt(id(73L)), receipt(id(71L)), receipt(id(72L))),
                List.of(),
                List.of())
            .result()
            .stoppedThreads()
            .stream()
            .map(StoppedThreadReceipt::threadId)
            .toList());
  }

  private static ThreadCommand command(long sequence, ThreadCommandPayload payload) {
    return new ThreadCommand(
        id(1L),
        sequence,
        payload,
        id(100L + sequence),
        ThreadCommandPayloadJsonCodec.requestHash(payload),
        null,
        null,
        null,
        T1);
  }

  private static AgentMessage userMessage(String text) {
    return userMessagePayload(text).message();
  }

  private static StoppedThreadReceipt receipt(UUID threadId) {
    return new StoppedThreadReceipt(threadId, id(90L), id(91L), 0, List.of());
  }

  private static ThreadState stoppedThread(UUID threadId) {
    return new ThreadState(
        threadId,
        id(2L),
        null,
        id(3L),
        CREATION_REQUEST_HASH,
        "main",
        threadId,
        false,
        SpawnTrigger.USER,
        ThreadConstraints.NONE,
        ThreadExecutionControl.STOPPED,
        0,
        1,
        1,
        T0,
        T1);
  }
}
