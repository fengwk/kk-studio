package fun.fengwk.kkstudio.harness.runtime.thread.command;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * queued Thread command harvest 的纯 reducer。
 *
 * <p>本 reducer 仅应用 typed 字段变更。Environment quiescence 以及所有 persistence/CAS 决策都不在本类内。
 */
public final class CommandHarvestReducer {

  public CommandHarvestResult reduce(
      UUID threadId, BranchSettings baseSettings, List<ThreadCommand> eligibleCommands) {
    Objects.requireNonNull(threadId, "threadId");
    BranchSettings settings = Objects.requireNonNull(baseSettings, "baseSettings");
    Objects.requireNonNull(eligibleCommands, "eligibleCommands");

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
        case SetAgentCommandPayload value -> settings = settings.withAgentName(value.agentName());
        case SetModelCommandPayload value -> settings = settings.withModel(value.model());
      }
    }
    return new CommandHarvestResult(settings);
  }
}
