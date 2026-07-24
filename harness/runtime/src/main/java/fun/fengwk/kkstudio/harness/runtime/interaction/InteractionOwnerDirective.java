package fun.fengwk.kkstudio.harness.runtime.interaction;

import java.util.Objects;

/** Explicit owner state transition directive carried by an Interaction use case. */
public record InteractionOwnerDirective(InteractionOwnerAction action) {
  public InteractionOwnerDirective {
    action = Objects.requireNonNull(action, "action");
  }
}
