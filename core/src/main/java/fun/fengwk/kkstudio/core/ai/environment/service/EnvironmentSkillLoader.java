package fun.fengwk.kkstudio.core.ai.environment.service;

import fun.fengwk.kkstudio.harness.tool.EnvironmentId;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Core port that requests a complete skill body from a READY live Environment by canonical {@link
 * EnvironmentId}.
 *
 * <p>Wrapped by the platform {@code load_skill} tool for Agents with selected skills. Display names
 * never participate in routing.
 */
public interface EnvironmentSkillLoader {

  /**
   * Requests skill {@code skillName} from the environment bound to {@code environmentId}.
   *
   * <p>Completes with {@link EnvironmentSkillLoadResult.Loaded} on success, or {@link
   * EnvironmentSkillLoadResult.Failed} on daemon failure, offline environment, disconnect, or
   * timeout. Never blocks the caller thread on the remote round-trip.
   */
  CompletableFuture<EnvironmentSkillLoadResult> loadSkill(
      EnvironmentId environmentId, String skillName, Duration timeout);
}
