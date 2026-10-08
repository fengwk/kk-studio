package fun.fengwk.kkstudio.harness.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.history.NotificationKind;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.store.testing.TestIds;
import fun.fengwk.kkstudio.harness.runtime.thread.command.CustomMessageCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NotificationCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@link ThreadInputDemand} 抽取自 Processor 的同一输入需求判据，本测试以 typed 矩阵锁定其语义：消息/通知构成 turn 需求，
 * 设置与已终结命令不构成；已物化但未越过输入水位的 APPLIED 通知仍构成需求，其余状态不构成。
 */
class ThreadInputDemandTest {

  private static final Instant T0 = Instant.ofEpochMilli(1_000);

  /** 纯类型判据：USER / CUSTOM / NOTIFICATION 构成需求，SET_* 与空集不构成。 */
  @Test
  void hasQueuedDemandDependsOnlyOnTurnDrivingTypes() {
    assertTrue(ThreadInputDemand.hasQueuedDemand(List.of(command(1L, userMessage(), null, null))));
    assertTrue(
        ThreadInputDemand.hasQueuedDemand(List.of(command(1L, customMessage(), null, null))));
    assertTrue(
        ThreadInputDemand.hasQueuedDemand(
            List.of(command(1L, notification(UUID.randomUUID()), null, null))));
    assertFalse(
        ThreadInputDemand.hasQueuedDemand(
            List.of(command(1L, new SetAgentCommandPayload("assistant"), null, null))));
    assertFalse(ThreadInputDemand.hasQueuedDemand(List.of()));
  }

  /** 只有水位之后的 APPLIED 通知构成未接纳需求；QUEUED/CANCELLED、非通知类型与已越过水位都不构成。 */
  @Test
  void hasUnconsumedNotificationOnlyCountsAppliedNotificationBeyondWatermark() {
    UUID notificationId = UUID.randomUUID();
    ThreadCommand appliedNotification =
        command(2L, notification(notificationId), TestIds.id(3), null);
    assertTrue(ThreadInputDemand.hasUnconsumedNotification(List.of(appliedNotification), 1L));
    assertFalse(ThreadInputDemand.hasUnconsumedNotification(List.of(appliedNotification), 2L));

    // 尚未 APPLIED 的通知不是可接纳的已物化需求。
    assertFalse(
        ThreadInputDemand.hasUnconsumedNotification(
            List.of(command(2L, notification(notificationId), null, null)), 1L));
    // 已取消的命令不算需求。
    assertFalse(
        ThreadInputDemand.hasUnconsumedNotification(
            List.of(command(2L, notification(notificationId), null, TestIds.id(4))), 1L));
    // 非通知类型即使 APPLIED 也不构成「未接纳通知」。
    assertFalse(
        ThreadInputDemand.hasUnconsumedNotification(
            List.of(command(2L, userMessage(), TestIds.id(5), null)), 1L));
    assertFalse(ThreadInputDemand.hasUnconsumedNotification(List.of(), 0L));
  }

  /** queued 用户消息构成输入需求。 */
  @Test
  void hasInputDemandTrueForQueuedUserMessage() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    insertCommands(store, baseline, command(1L, userMessage(), null, null));
    assertTrue(hasInputDemand(store, baseline.threadId()));
  }

  /** queued 设置命令不构成输入需求。 */
  @Test
  void hasInputDemandFalseForQueuedSettingOnly() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    insertCommands(
        store, baseline, command(1L, new SetAgentCommandPayload("assistant"), null, null));
    assertFalse(hasInputDemand(store, baseline.threadId()));
  }

  /** 无任何命令时没有输入需求。 */
  @Test
  void hasInputDemandFalseWhenNoCommands() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    HarnessRuntimeTestSupport.Baseline baseline = HarnessRuntimeTestSupport.seedBaseline(store);
    assertFalse(hasInputDemand(store, baseline.threadId()));
  }

  private static void insertCommands(
      InMemoryHarnessStore store,
      HarnessRuntimeTestSupport.Baseline baseline,
      ThreadCommand command) {
    store.transaction(
        tx -> {
          tx.lockThread(baseline.threadId());
          tx.insertCommands(
              List.of(
                  command(baseline.threadId(), command.sequence(), command.payload(), null, null)));
          return null;
        });
  }

  private static boolean hasInputDemand(InMemoryHarnessStore store, UUID threadId) {
    return store.<Boolean>transaction(
        tx -> {
          tx.lockThread(threadId);
          return ThreadInputDemand.hasInputDemand(tx, tx.findThread(threadId).orElseThrow());
        });
  }

  private static ThreadCommandPayload userMessage() {
    return new UserMessageCommandPayload(AgentMessage.user("hello"));
  }

  private static ThreadCommandPayload customMessage() {
    return new CustomMessageCommandPayload(AgentMessage.user("custom"));
  }

  private static ThreadCommandPayload notification(UUID notificationId) {
    return new NotificationCommandPayload(
        notificationId,
        NotificationKind.SUBAGENT_RESULT,
        TestIds.id(9),
        AgentMessage.user("receipt"));
  }

  private static ThreadCommand command(
      long sequence, ThreadCommandPayload payload, UUID appliedEntryId, UUID stopRequestId) {
    return command(TestIds.id(1), sequence, payload, appliedEntryId, stopRequestId);
  }

  private static ThreadCommand command(
      UUID threadId,
      long sequence,
      ThreadCommandPayload payload,
      UUID appliedEntryId,
      UUID stopRequestId) {
    return new ThreadCommand(
        threadId,
        sequence,
        payload,
        TestIds.id(100 + sequence),
        ThreadCommandPayloadJsonCodec.requestHash(payload),
        appliedEntryId,
        stopRequestId,
        stopRequestId == null ? null : T0,
        T0);
  }
}
