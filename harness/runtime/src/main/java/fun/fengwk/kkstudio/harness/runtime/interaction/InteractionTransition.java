package fun.fengwk.kkstudio.harness.runtime.interaction;

import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTarget;

import java.util.Objects;

/** Completed atomic Interaction lifecycle transition and the target to signal after commit. */
public record InteractionTransition(Interaction interaction, ExecutionTarget nextTarget) {
  public InteractionTransition {
    interaction = Objects.requireNonNull(interaction, "interaction");
    nextTarget = Objects.requireNonNull(nextTarget, "nextTarget");
    if (!interaction.status().isTerminal()) {
      throw new IllegalArgumentException("interaction transition must be terminal");
    }
  }
}
