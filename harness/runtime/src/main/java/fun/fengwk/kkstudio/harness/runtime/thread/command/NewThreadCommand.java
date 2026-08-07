package fun.fengwk.kkstudio.harness.runtime.thread.command;

import java.util.Objects;

/** Store 分配 durable ID 与 sequence 字段之前的不可变 command request。 */
public record NewThreadCommand(ThreadCommandPayload payload, String clientCommandId) {

  public NewThreadCommand {
    payload = Objects.requireNonNull(payload, "payload");
    clientCommandId =
        CommandValueValidation.requireCanonicalName(clientCommandId, "clientCommandId");
  }
}
