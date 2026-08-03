package fun.fengwk.kkstudio.harness.runtime.thread.command;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable atomic enqueue request with exact head/sequence CAS expectations.
 *
 * <p>There is deliberately no batch identity or batch lifecycle state.
 */
public record ThreadCommandBatch(
    long threadId,
    long expectedHeadEntryId,
    long expectedNextCommandSequence,
    List<NewThreadCommand> commands) {

  public ThreadCommandBatch {
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    if (expectedHeadEntryId <= 0) {
      throw new IllegalArgumentException("expectedHeadEntryId must be positive");
    }
    if (expectedNextCommandSequence <= 0) {
      throw new IllegalArgumentException("expectedNextCommandSequence must be positive");
    }
    Objects.requireNonNull(commands, "commands");
    if (commands.isEmpty()) {
      throw new IllegalArgumentException("command batch must contain at least one command");
    }
    commands = List.copyOf(commands);

    Set<String> clientCommandIds = new HashSet<>();
    for (NewThreadCommand command : commands) {
      Objects.requireNonNull(command, "commands[]");
      if (!clientCommandIds.add(command.clientCommandId())) {
        throw new IllegalArgumentException(
            "command batch must not contain duplicate clientCommandId: "
                + command.clientCommandId());
      }
    }
  }
}
