package fun.fengwk.kkstudio.core.environment.service;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Core port that requests a complete skill body from a READY live Environment by name.
 *
 * <p>Wrapped by the platform {@code load_skill} tool for Agents with selected skills.
 */
public interface EnvironmentSkillLoader {

  /**
   * Requests skill {@code skillName} from {@code environmentName}.
   *
   * <p>Completes with {@link EnvironmentSkillLoadResult.Loaded} on success, or {@link
   * EnvironmentSkillLoadResult.Failed} on daemon failure, offline environment, disconnect, or
   * timeout. Never blocks the caller thread on the remote round-trip.
   */
  CompletableFuture<EnvironmentSkillLoadResult> loadSkill(
      String environmentName, String skillName, Duration timeout);
}
