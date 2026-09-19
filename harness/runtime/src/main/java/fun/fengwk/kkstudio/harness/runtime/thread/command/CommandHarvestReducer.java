package fun.fengwk.kkstudio.harness.runtime.thread.command;

import fun.fengwk.kkstudio.harness.runtime.entry.BranchSettings;

import java.util.ArrayList;
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
    List<CommandHarvestResult.SettingsChange> changes = new ArrayList<>();
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
        case SetAgentCommandPayload value -> {
          BranchSettings updated = settings.withAgentName(value.agentName());
          if (!updated.equals(settings)) {
            settings = updated;
            changes.add(
                new CommandHarvestResult.SettingsChange(ThreadCommandType.SET_AGENT, settings));
          }
        }
        case SetModelCommandPayload value -> {
          BranchSettings updated = settings.withModel(value.model());
          if (!updated.equals(settings)) {
            settings = updated;
            changes.add(
                new CommandHarvestResult.SettingsChange(ThreadCommandType.SET_MODEL, settings));
          }
        }
        case SetEnvironmentCommandPayload value -> {
          BranchSettings updated = settings.withEnvironmentName(value.environmentName());
          if (!updated.equals(settings)) {
            settings = updated;
            changes.add(
                new CommandHarvestResult.SettingsChange(
                    ThreadCommandType.SET_ENVIRONMENT, settings));
          }
        }
      }
    }
    return new CommandHarvestResult(settings, changes);
  }
}
