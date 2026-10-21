package fun.fengwk.kkstudio.harness.runtime;

import fun.fengwk.kkstudio.harness.runtime.thread.command.NewThreadCommand;
import fun.fengwk.kkstudio.harness.runtime.thread.command.UserMessageCommandPayload;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 不可变的单次命令接受请求：sealed {@link AcceptCommandsTarget} + 本批 ordered Commands。
 *
 * <p>同一次创建（NEW_SESSION / NEW_THREAD）的幂等键由 target 内的预分配 id 与 {@code creationRequestHash} 派生，不额外携带
 * request id。
 */
public record AcceptCommandsCommand(AcceptCommandsTarget target, List<NewThreadCommand> commands) {

  public AcceptCommandsCommand {
    target = Objects.requireNonNull(target, "target");
    commands = requireCommandBatch(commands);
  }

  private static List<NewThreadCommand> requireCommandBatch(List<NewThreadCommand> commands) {
    Objects.requireNonNull(commands, "commands");
    if (commands.isEmpty()) {
      throw new IllegalArgumentException("commands must not be empty");
    }
    List<NewThreadCommand> copied = List.copyOf(commands);
    Set<UUID> idempotencyKeys = new HashSet<>();
    for (NewThreadCommand command : copied) {
      Objects.requireNonNull(command, "commands[]");
      if (!idempotencyKeys.add(command.idempotencyKey())) {
        throw new IllegalArgumentException(
            "commands must not contain duplicate idempotencyKey: " + command.idempotencyKey());
      }
    }
    return copied;
  }

  /**
   * 单条纯 USER_MESSAGE 批次不携带 Thread cursor（携带即输入错误）：并发追加只由按 Thread 的有序锁与幂等键保证。任何 SET_* 前缀、GOAL /
   * CUSTOM / NOTIFICATION 或包含多条命令的批次仍然是 cursor-bound。
   */
  public static boolean isPureUserMessageBatch(List<NewThreadCommand> commands) {
    return commands.size() == 1
        && commands.getFirst().payload() instanceof UserMessageCommandPayload;
  }

  /** 该批次是否必须携带精确 head / next-sequence cursor。 */
  public boolean requiresThreadCursor() {
    return !isPureUserMessageBatch(commands);
  }
}
