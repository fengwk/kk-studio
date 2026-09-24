package fun.fengwk.kkstudio.harness.runtime.thread.command;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;
import fun.fengwk.kkstudio.harness.runtime.entry.GoalSetting;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * queued Thread command harvest 的纯 reducer。
 *
 * <p>本 reducer 仅应用 typed 字段变更。Environment quiescence 以及所有 persistence/CAS 决策都不在本类内。
 *
 * <p>typed GOAL 命令同时写入 settings 快照（由 {@code goalIdAllocator} 在 speculative planning 时分配新 id）与同 turn
 * 的冻结 USER 消息；SET_* 只改变快照，不产生提醒。CONTINUATION 只消费 SET_*，不提前消费 Goal。
 */
public final class CommandHarvestReducer {

  public BranchSettings reduce(
      UUID threadId,
      BranchSettings baseSettings,
      List<ThreadCommand> eligibleCommands,
      Supplier<UUID> goalIdAllocator) {
    Objects.requireNonNull(threadId, "threadId");
    BranchSettings settings = Objects.requireNonNull(baseSettings, "baseSettings");
    Objects.requireNonNull(eligibleCommands, "eligibleCommands");
    Objects.requireNonNull(goalIdAllocator, "goalIdAllocator");

    long previousSequence = 0L;
    for (ThreadCommand command : eligibleCommands) {
      Objects.requireNonNull(command, "eligibleCommands[]");
      if (!command.threadId().equals(threadId)) {
        throw new IllegalArgumentException(
            "command threadId " + command.threadId() + " does not match " + threadId);
      }
      if (command.state() != ThreadCommandState.QUEUED) {
        throw new IllegalArgumentException(
            "command must be QUEUED but was "
                + command.state()
                + " at sequence "
                + command.sequence());
      }
      if (command.sequence() <= previousSequence) {
        throw new IllegalArgumentException(
            "command sequence must be strictly increasing at "
                + command.sequence()
                + " after "
                + previousSequence);
      }
      previousSequence = command.sequence();

      ThreadCommandPayload payload = command.payload();
      switch (payload) {
        case UserMessageCommandPayload ignored -> {}
        case CustomMessageCommandPayload ignored -> {}
        case GoalCommandPayload value -> {
          // 每次设置都是新目标（即使文本相同），清除则回到无 Goal。
          GoalSetting goal =
              value.text() == null ? null : new GoalSetting(goalIdAllocator.get(), value.text());
          settings = settings.withGoal(goal);
        }
        case SetAgentCommandPayload value -> settings = settings.withAgentName(value.agentName());
        case SetModelCommandPayload value -> settings = settings.withModel(value.model());
        case SetEnvironmentCommandPayload value -> settings =
            settings.withEnvironmentName(value.environmentName());
      }
    }
    return settings;
  }
}
