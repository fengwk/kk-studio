package fun.fengwk.kkstudio.harness.plugin.api;

import java.util.Objects;

/** 冻结后的上下文投影器贡献：身份即其 scoped {@link ContributionId}。 */
public record ContextProjectorContribution(ContributionId id, ContextProjector projector) {

  public ContextProjectorContribution {
    id = Objects.requireNonNull(id, "id");
    projector = Objects.requireNonNull(projector, "projector");
  }
}
