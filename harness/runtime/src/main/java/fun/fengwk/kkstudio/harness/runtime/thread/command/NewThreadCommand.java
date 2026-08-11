package fun.fengwk.kkstudio.harness.runtime.thread.command;

import java.util.Objects;
import java.util.UUID;

/** Store 分配 durable sequence 之前的不可变 command request。 */
public record NewThreadCommand(ThreadCommandPayload payload, UUID clientCommandId) {

  public NewThreadCommand {
    payload = Objects.requireNonNull(payload, "payload");
    Objects.requireNonNull(clientCommandId, "clientCommandId");
  }
}
