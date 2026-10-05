package fun.fengwk.kkstudio.harness.runtime.processor;

import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.NOW;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.command;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.path;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedBaseline;
import static fun.fengwk.kkstudio.harness.runtime.processor.ThreadProcessorTestSupport.seedCommand;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import fun.fengwk.kkstudio.harness.runtime.entry.TurnStartReason;
import fun.fengwk.kkstudio.harness.runtime.history.CustomEntryPayload;
import fun.fengwk.kkstudio.harness.runtime.history.Entry;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationKind;
import fun.fengwk.kkstudio.harness.runtime.history.NotificationPayload;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.store.testing.InMemoryHarnessStore;
import fun.fengwk.kkstudio.harness.runtime.thread.command.NotificationCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetAgentCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.SetContributorStateCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandPayload;
import fun.fengwk.kkstudio.harness.runtime.thread.command.ThreadCommandState;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;

import java.util.List;
import java.util.UUID;

/** Contributor state 与设置按序进入候选历史；规划本身不持久化，消息只由 INPUT 消费。 */
class TurnPlanBuilderContributorStateTest {

  private final TurnPlanBuilder builder = new TurnPlanBuilder();

  @ParameterizedTest
  @EnumSource(
      value = TurnStartReason.class,
      names = {"INPUT", "CONTINUATION"})
  void materializesContributorStatesInOrderWithoutConsumingContinuationInputs(
      TurnStartReason reason) {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    UUID threadId = seedBaseline(store).threadId();
    CustomEntryPayload opened = new CustomEntryPayload("project", "run", 1, "{\"active\":true}");
    CustomEntryPayload closed = new CustomEntryPayload("project", "run", 1, "{\"active\":false}");
    AgentMessage message =
        new AgentMessage(AgentMessageRole.USER, List.of(new TextMessageContent("continue")));
    List<ThreadCommandPayload> payloads =
        List.of(
            new SetContributorStateCommandPayload(opened),
            new UserMessageCommandPayload(message),
            new SetAgentCommandPayload("worker"),
            new SetContributorStateCommandPayload(closed),
            new NotificationCommandPayload(
                UUID.randomUUID(), NotificationKind.SUBAGENT_RESULT, UUID.randomUUID(), message));
    List<ThreadCommand> commands =
        payloads.stream()
            .map(payload -> command(store, threadId, seedCommand(store, threadId, payload)))
            .toList();
    var source = path(store, threadId);

    TurnPlan plan =
        builder.build(threadId, source, reason, commands, 5L, UUID::randomUUID, NOW, null, false);

    assertEquals(
        reason == TurnStartReason.INPUT
            ? commands
            : List.of(commands.get(0), commands.get(2), commands.get(3)),
        plan.consumedCommands());
    assertEquals("worker", plan.candidatePath().baseSettings().agentName());
    assertEquals(
        List.of(opened, closed),
        plan.candidateEntries().stream()
            .map(Entry::payload)
            .filter(CustomEntryPayload.class::isInstance)
            .toList());
    assertEquals(plan.consumedCommands().size(), plan.appliedEntryIds().size());
    // 通知的应用坐标指向自身；配置指向本轮边界，不能把配置更新误当成待消费输入。
    for (ThreadCommand consumed : plan.consumedCommands()) {
      if (consumed.payload() instanceof NotificationCommandPayload) {
        assertEquals(
            plan.candidateEntries().stream()
                .filter(entry -> entry.payload() instanceof NotificationPayload)
                .findFirst()
                .orElseThrow()
                .id(),
            plan.appliedEntryIds().get(consumed.sequence()));
      } else {
        assertEquals(plan.turnStartEntryId(), plan.appliedEntryIds().get(consumed.sequence()));
      }
    }
    assertEquals(source, path(store, threadId));
    for (ThreadCommand original : commands) {
      assertEquals(
          ThreadCommandState.QUEUED, command(store, threadId, original.idempotencyKey()).state());
    }
  }

  @Test
  void contributorStateAloneCannotStartAnInputTurn() {
    InMemoryHarnessStore store = new InMemoryHarnessStore();
    UUID threadId = seedBaseline(store).threadId();
    UUID key =
        seedCommand(
            store,
            threadId,
            new SetContributorStateCommandPayload(
                new CustomEntryPayload("project", "run", 1, "{\"active\":false}")));
    // 纯状态更新不唤醒模型，也不提前应用；下一条真实输入到来后才由规划消费。
    var error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                builder.build(
                    threadId,
                    path(store, threadId),
                    TurnStartReason.INPUT,
                    List.of(command(store, threadId, key)),
                    1L,
                    UUID::randomUUID,
                    NOW,
                    null,
                    false));
    assertTrue(error.getMessage().contains("requires at least one"));
    assertEquals(ThreadCommandState.QUEUED, command(store, threadId, key).state());
  }
}
