package fun.fengwk.kkstudio.harness.runtime.interaction;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;

import java.util.Objects;

/** Deterministic handler output applied by an Interaction transaction before dispatch. */
public record InteractionResolution(
    InteractionOwnerDirective ownerDirective, ExecutionTarget nextTarget) {
  public InteractionResolution {
    ownerDirective = Objects.requireNonNull(ownerDirective, "ownerDirective");
    nextTarget = Objects.requireNonNull(nextTarget, "nextTarget");
  }
}
