package fun.fengwk.kkstudio.core.ai.runtime.skill;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.environment.service.EnvironmentSkillLoadResult;
import fun.fengwk.kkstudio.core.ai.environment.service.EnvironmentSkillLoader;
import fun.fengwk.kkstudio.harness.runtime.skill.SkillBodyLoader;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Adapts {@link EnvironmentSkillLoader} for platform tools.
 *
 * <p>The runtime {@link SkillBodyLoader} binding reference is the canonical {@link EnvironmentId}
 * text (never a display name); it is parsed strictly so a display-name reference fails closed
 * instead of routing by name.
 */
@Component
public final class EnvironmentSkillBodyLoader implements SkillBodyLoader {
  private final EnvironmentSkillLoader environmentSkillLoader;

  public EnvironmentSkillBodyLoader(@Lazy EnvironmentSkillLoader environmentSkillLoader) {
    this.environmentSkillLoader =
        Objects.requireNonNull(environmentSkillLoader, "environmentSkillLoader");
  }

  @Override
  public CompletableFuture<SkillBodyLoadResult> load(
      String environmentIdText, String skillName, Duration timeout) {
    EnvironmentId environmentId = new EnvironmentId(environmentIdText);
    return environmentSkillLoader
        .loadSkill(environmentId, skillName, timeout)
        .thenApply(EnvironmentSkillBodyLoader::map);
  }

  private static SkillBodyLoadResult map(EnvironmentSkillLoadResult result) {
    if (result instanceof EnvironmentSkillLoadResult.Loaded loaded) {
      return new SkillBodyLoadResult.Loaded(loaded.skillName(), loaded.content());
    }
    if (result instanceof EnvironmentSkillLoadResult.Failed failed) {
      return new SkillBodyLoadResult.Failed(failed.skillName(), failed.message());
    }
    throw new IllegalStateException("unexpected skill load result: " + result);
  }
}
