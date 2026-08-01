package fun.fengwk.kkstudio.harness.runtime.model.plan;

import java.util.Objects;

/** Pure planner outcome: no debt, a durable request, or an explicit persistable failure. */
public sealed interface PlanningResult
    permits PlanningResult.NoDebt, PlanningResult.Planned, PlanningResult.Failed {

  record NoDebt() implements PlanningResult {}

  record Planned(ModelInvocationPlan plan) implements PlanningResult {
    public Planned {
      plan = Objects.requireNonNull(plan, "plan");
    }
  }

  record Failed(PlanningFailure failure) implements PlanningResult {
    public Failed {
      failure = Objects.requireNonNull(failure, "failure");
    }
  }
}
