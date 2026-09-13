package fun.fengwk.kkstudio.platform.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.platform.project.repo.IssueDependencyRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueInputRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectSessionRepository;
import fun.fengwk.kkstudio.platform.project.service.IssueRunService;
import fun.fengwk.kkstudio.platform.project.service.IssueService;
import fun.fengwk.kkstudio.platform.project.service.ProjectService;

import java.util.List;
import java.util.UUID;

/**
 * {@link ProjectToolConfiguration} Spring 装配测试。
 *
 * <p>测试意图：
 *
 * <ul>
 *   <li>验证 Spring 上下文中正确暴露 resolver、selector、service、projector 与 contributor；
 *   <li>验证各类核心组件均只存在唯一 Bean，杜绝重复声明与注入歧义；
 *   <li>验证装配出的 Contributor 能够被 HarnessCatalog 成功冻结。
 * </ul>
 */
class ProjectToolConfigurationTest {

  private final ApplicationContextRunner runner =
      baseRunner().withBean(HarnessStore.class, () -> mock(HarnessStore.class));

  private static ApplicationContextRunner baseRunner() {
    return new ApplicationContextRunner()
        .withBean(ProjectSessionRepository.class, () -> mock(ProjectSessionRepository.class))
        .withBean(IssueRunSessionRepository.class, () -> mock(IssueRunSessionRepository.class))
        .withBean(ProjectRepository.class, () -> mock(ProjectRepository.class))
        .withBean(IssueRepository.class, () -> mock(IssueRepository.class))
        .withBean(IssueDependencyRepository.class, () -> mock(IssueDependencyRepository.class))
        .withBean(IssueInputRepository.class, () -> mock(IssueInputRepository.class))
        .withBean(IssueRunRepository.class, () -> mock(IssueRunRepository.class))
        .withBean(ProjectService.class, () -> mock(ProjectService.class))
        .withBean(IssueService.class, () -> mock(IssueService.class))
        .withBean(IssueRunService.class, () -> mock(IssueRunService.class))
        .withUserConfiguration(
            ProjectToolConfiguration.class,
            ProjectRoleToolService.class,
            ProjectRoleContextProjector.class);
  }

  @Test
  void exposesExpectedBeansWithoutDuplicates() {
    // 验证核心组件唯一暴露
    runner.run(
        context -> {
          assertTrue(context.containsBean("projectThreadOwnerResolver"));
          assertTrue(context.containsBean("projectRoleToolSelector"));
          assertTrue(context.containsBean("projectHarnessContributor"));
          assertTrue(context.containsBean("projectRoleToolService"));
          assertTrue(context.containsBean("projectRoleContextProjector"));

          assertEquals(1, context.getBeanNamesForType(ProjectThreadOwnerResolver.class).length);
          assertEquals(1, context.getBeanNamesForType(ProjectRoleToolSelector.class).length);
          assertEquals(1, context.getBeanNamesForType(ProjectHarnessContributor.class).length);
          assertEquals(1, context.getBeanNamesForType(ProjectRoleToolService.class).length);
          assertEquals(1, context.getBeanNamesForType(ProjectRoleContextProjector.class).length);

          ProjectHarnessContributor contributor = context.getBean(ProjectHarnessContributor.class);
          assertNotNull(contributor);
          HarnessCatalog catalog = HarnessCatalog.from(List.of(contributor));
          assertNotNull(catalog);
        });
  }

  @Test
  void contextCanBuildBeforeCompositionRootProvidesHarnessStore() {
    // 验证 platform 独立测试上下文可冻结 Contributor；实际解析时缺 Store 则 fail closed
    baseRunner()
        .run(
            context -> {
              assertTrue(context.isRunning());
              assertEquals(1, context.getBeanNamesForType(ProjectHarnessContributor.class).length);
              ProjectThreadOwnerResolver resolver =
                  context.getBean(ProjectThreadOwnerResolver.class);
              IllegalStateException error =
                  assertThrows(
                      IllegalStateException.class, () -> resolver.resolve(UUID.randomUUID()));
              assertEquals("Project thread ownership is inconsistent", error.getMessage());
            });
  }
}
