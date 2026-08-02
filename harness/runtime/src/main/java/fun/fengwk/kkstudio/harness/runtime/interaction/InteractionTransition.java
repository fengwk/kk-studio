package fun.fengwk.kkstudio.harness.runtime.interaction;

import java.util.Objects;

/** Completed atomic Interaction lifecycle transition with its activation mutation committed. */
public record InteractionTransition(Interaction interaction) {
  public InteractionTransition {
    interaction = Objects.requireNonNull(interaction, "interaction");
    if (!interaction.status().isTerminal()) {
      throw new IllegalArgumentException("interaction transition must be terminal");
    }
  }
}
