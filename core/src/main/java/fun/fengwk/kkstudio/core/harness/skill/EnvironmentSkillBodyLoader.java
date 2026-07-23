package fun.fengwk.kkstudio.core.harness.skill;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.environment.service.EnvironmentSkillLoadResult;
import fun.fengwk.kkstudio.core.environment.service.EnvironmentSkillLoader;
import fun.fengwk.kkstudio.harness.runtime.skill.SkillBodyLoader;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Adapts {@link EnvironmentSkillLoader} for platform tools without creating a Spring cycle through
 * {@code HarnessExtensionHost}.
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
      String environmentName, String skillName, Duration timeout) {
    return environmentSkillLoader
        .loadSkill(environmentName, skillName, timeout)
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
