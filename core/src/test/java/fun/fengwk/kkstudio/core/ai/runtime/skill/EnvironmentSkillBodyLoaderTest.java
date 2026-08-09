package fun.fengwk.kkstudio.core.ai.runtime.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.environment.service.EnvironmentSkillLoadResult;
import fun.fengwk.kkstudio.core.ai.environment.service.EnvironmentSkillLoader;
import fun.fengwk.kkstudio.harness.runtime.skill.SkillBodyLoader.SkillBodyLoadResult;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** 运行时 skill body loader 直接使用 canonical EnvironmentName 路由。 */
class EnvironmentSkillBodyLoaderTest {

  private static final EnvironmentName ENVIRONMENT_ID = new EnvironmentName("env-1");

  @Test
  void delegatesCanonicalEnvironmentNameToEnvironmentSkillLoader() {
    EnvironmentSkillLoader delegate = mock(EnvironmentSkillLoader.class);
    when(delegate.loadSkill(ENVIRONMENT_ID, "dev", Duration.ofSeconds(5)))
        .thenReturn(
            CompletableFuture.completedFuture(
                new EnvironmentSkillLoadResult.Loaded("dev", "skill body")));

    EnvironmentSkillBodyLoader loader = new EnvironmentSkillBodyLoader(delegate);
    SkillBodyLoadResult result = loader.load(ENVIRONMENT_ID, "dev", Duration.ofSeconds(5)).join();

    verify(delegate).loadSkill(ENVIRONMENT_ID, "dev", Duration.ofSeconds(5));
    assertTrue(result instanceof SkillBodyLoadResult.Loaded);
    SkillBodyLoadResult.Loaded loaded = (SkillBodyLoadResult.Loaded) result;
    assertEquals("dev", loaded.skillName());
    assertEquals("skill body", loaded.content());
  }
}
