package fun.fengwk.kkstudio.platform.harness.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.builtin.skill.SkillBodyLoader.SkillBodyLoadResult;
import fun.fengwk.kkstudio.harness.tool.EnvironmentBinding;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentSkillLoadResult;
import fun.fengwk.kkstudio.platform.environment.service.EnvironmentSkillLoader;
import fun.fengwk.kkstudio.platform.testing.TestEnvironmentBindings;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** 运行时 skill body loader 直接使用 canonical EnvironmentName 路由。 */
class EnvironmentSkillBodyLoaderTest {

  private static final EnvironmentBinding ENVIRONMENT_ID = TestEnvironmentBindings.binding("env-1");

  @Test
  void delegatesCanonicalEnvironmentNameToEnvironmentSkillLoader() {
    EnvironmentSkillLoader delegate = mock(EnvironmentSkillLoader.class);
    when(delegate.loadSkill(ENVIRONMENT_ID.environmentName(), "dev", Duration.ofSeconds(5)))
        .thenReturn(
            CompletableFuture.completedFuture(
                new EnvironmentSkillLoadResult.Loaded("dev", "skill body")));

    EnvironmentSkillBodyLoader loader = new EnvironmentSkillBodyLoader(delegate);
    SkillBodyLoadResult result = loader.load(ENVIRONMENT_ID, "dev", Duration.ofSeconds(5)).join();

    verify(delegate).loadSkill(ENVIRONMENT_ID.environmentName(), "dev", Duration.ofSeconds(5));
    assertTrue(result instanceof SkillBodyLoadResult.Loaded);
    SkillBodyLoadResult.Loaded loaded = (SkillBodyLoadResult.Loaded) result;
    assertEquals("dev", loaded.skillName());
    assertEquals("skill body", loaded.content());
  }
}
