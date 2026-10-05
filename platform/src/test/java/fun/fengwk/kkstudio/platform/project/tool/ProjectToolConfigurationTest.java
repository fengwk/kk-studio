package fun.fengwk.kkstudio.platform.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.project.turn.ProjectRunScope;

import java.util.List;

/**
 * {@link ProjectToolConfiguration} 的 Spring 装配契约：每个组件唯一暴露，且 Contributor 冻结出可显式选择的交接工具与 Run 上下文投影。
 */
class ProjectToolConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(IssueAgentThreadRepository.class, () -> mock(IssueAgentThreadRepository.class))
          .withBean(ProjectRepository.class, () -> mock(ProjectRepository.class))
          .withBean(IssueRepository.class, () -> mock(IssueRepository.class))
          .withBean(IssueRunRepository.class, () -> mock(IssueRunRepository.class))
          .withBean(ProjectWorkflowJsonCodec.class, ProjectWorkflowJsonCodec::new)
          .withUserConfiguration(ProjectToolConfiguration.class);

  /** 测试意图：各组件各自只有一个 Bean，避免重复声明导致注入歧义。 */
  @Test
  void exposesEachComponentExactlyOnce() {
    runner.run(
        context -> {
          assertEquals(1, context.getBeanNamesForType(IssueTransitionService.class).length);
          assertEquals(1, context.getBeanNamesForType(IssueTransitionTool.class).length);
          assertEquals(1, context.getBeanNamesForType(ProjectHarnessContributor.class).length);
          assertEquals(1, context.getBeanNamesForType(ProjectThreadOwnerResolver.class).length);
        });
  }

  /**
   * 测试意图：Contributor 冻结出唯一的 {@code issue_transition}（SELECTABLE，由 Agent 配置显式选择），并注册 {@code
   * project/run} custom entry type 与对应的上下文 projector；运行时不再按 Thread owner 注入工具或上下文。
   */
  @Test
  void freezesSelectableTransitionToolAndRunContextProjector() {
    runner.run(
        context -> {
          HarnessCatalog catalog =
              HarnessCatalog.from(List.of(context.getBean(ProjectHarnessContributor.class)));

          assertEquals(1, catalog.tools().size());
          ToolContribution tool = catalog.tools().get(0);
          assertEquals(IssueTransitionTool.NAME, tool.definition().descriptor().name());
          assertEquals(ToolVisibility.SELECTABLE, tool.definition().visibility());
          assertEquals("project:issue.transition", tool.id().toString());
          assertEquals(List.of(tool), catalog.selectableTools());

          assertEquals(1, catalog.customEntryTypes().size());
          assertEquals(ProjectRunScope.CUSTOM_TYPE, catalog.customEntryTypes().get(0).customType());
          assertEquals(1, catalog.contextProjectors().size());
        });
  }
}
