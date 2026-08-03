package fun.fengwk.kkstudio.harness.runtime.thread.command;

import java.util.Objects;

/** Immutable command request before the Store allocates durable ID and sequence fields. */
public record NewThreadCommand(ThreadCommandPayload payload, String clientCommandId) {

  public NewThreadCommand {
    payload = Objects.requireNonNull(payload, "payload");
    clientCommandId =
        CommandValueValidation.requireCanonicalName(clientCommandId, "clientCommandId");
  }
}
