package fun.fengwk.kkstudio.platform.harness.skill;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.harness.builtin.skill.SkillBodyLoader;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentSkillLoadResult;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentSkillLoader;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** 为平台工具适配 {@link EnvironmentSkillLoader}：路由只使用冻结 binding 的 {@code environmentName}。 */
@Component
public final class EnvironmentSkillBodyLoader implements SkillBodyLoader {
  private final EnvironmentSkillLoader environmentSkillLoader;

  public EnvironmentSkillBodyLoader(@Lazy EnvironmentSkillLoader environmentSkillLoader) {
    this.environmentSkillLoader =
        Objects.requireNonNull(environmentSkillLoader, "environmentSkillLoader");
  }

  @Override
  public CompletableFuture<SkillBodyLoadResult> load(
      EnvironmentBinding binding, String skillName, Duration timeout) {
    Objects.requireNonNull(binding, "binding");
    return environmentSkillLoader
        .loadSkill(binding.environmentName(), skillName, timeout)
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
