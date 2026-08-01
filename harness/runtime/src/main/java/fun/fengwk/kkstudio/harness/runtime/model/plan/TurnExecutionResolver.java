package fun.fengwk.kkstudio.harness.runtime.model.plan;

import fun.fengwk.kkstudio.harness.runtime.thread.TurnSettings;

/**
 * Resolves the current live execution facts for one name-only turn reference.
 *
 * <p>Implementations resolve the Agent, Model, Environment, Tool, and Skill names on every call. No
 * result from this SPI is durable by itself.
 */
@FunctionalInterface
public interface TurnExecutionResolver {

  Resolution resolve(TurnSettings settings);

  sealed interface Resolution permits Resolution.Resolved, Resolution.Failed {

    record Resolved(ResolvedTurnExecution execution) implements Resolution {
      public Resolved {
        if (execution == null) {
          throw new NullPointerException("execution");
        }
      }
    }

    record Failed(PlanningFailure failure) implements Resolution {
      public Failed {
        if (failure == null) {
          throw new NullPointerException("failure");
        }
      }
    }
  }
}
