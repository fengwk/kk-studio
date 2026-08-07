package fun.fengwk.kkstudio.core.ai.runtime.skill;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import fun.fengwk.kkstudio.core.ai.environment.service.EnvironmentSkillLoadResult;
import fun.fengwk.kkstudio.core.ai.environment.service.EnvironmentSkillLoader;
import fun.fengwk.kkstudio.harness.runtime.skill.SkillBodyLoader;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * 为平台工具适配 {@link EnvironmentSkillLoader}。
 *
 * <p>Runtime {@link SkillBodyLoader} 的绑定引用是 canonical {@link EnvironmentName} 文本
 * （绝不是展示名）；它被严格解析，因此展示名引用会 fail-closed，而不是按名路由。
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
      String environmentNameText, String skillName, Duration timeout) {
    EnvironmentName environmentName = new EnvironmentName(environmentNameText);
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
