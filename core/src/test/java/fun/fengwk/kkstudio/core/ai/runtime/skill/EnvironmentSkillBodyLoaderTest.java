package fun.fengwk.kkstudio.core.ai.runtime.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.environment.service.EnvironmentSkillLoadResult;
import fun.fengwk.kkstudio.core.ai.environment.service.EnvironmentSkillLoader;
import fun.fengwk.kkstudio.harness.runtime.skill.SkillBodyLoader.SkillBodyLoadResult;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** 运行时 skill binding 引用必须是 canonical EnvironmentId 文本；display-name 引用会安全失败，而不是按名称路由。 */
class EnvironmentSkillBodyLoaderTest {

  private static final EnvironmentId ENVIRONMENT_ID =
      new EnvironmentId("0f8fad5b-d9cb-469f-a165-70867728950e");

  @Test
  void parsesCanonicalIdTextAndDelegatesToEnvironmentSkillLoader() {
    EnvironmentSkillLoader delegate = mock(EnvironmentSkillLoader.class);
    when(delegate.loadSkill(ENVIRONMENT_ID, "dev", Duration.ofSeconds(5)))
        .thenReturn(
            CompletableFuture.completedFuture(
                new EnvironmentSkillLoadResult.Loaded("dev", "skill body")));

    EnvironmentSkillBodyLoader loader = new EnvironmentSkillBodyLoader(delegate);
    SkillBodyLoadResult result =
        loader.load(ENVIRONMENT_ID.value(), "dev", Duration.ofSeconds(5)).join();

    verify(delegate).loadSkill(ENVIRONMENT_ID, "dev", Duration.ofSeconds(5));
    assertTrue(result instanceof SkillBodyLoadResult.Loaded);
    SkillBodyLoadResult.Loaded loaded = (SkillBodyLoadResult.Loaded) result;
    assertEquals("dev", loaded.skillName());
    assertEquals("skill body", loaded.content());
  }

  @Test
  void rejectsDisplayNameReferenceInsteadOfLookingItUp() {
    EnvironmentSkillBodyLoader loader =
        new EnvironmentSkillBodyLoader(mock(EnvironmentSkillLoader.class));
    // display name 不是 canonical UUID 文本；adapter 必须安全失败，而不是
    // 通过任何 registry lookup 按名称路由。
    assertThrows(
        IllegalArgumentException.class,
        () -> loader.load("display-name", "dev", Duration.ofSeconds(5)));
  }
}
