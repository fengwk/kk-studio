package fun.fengwk.kkstudio.platform.project.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import fun.fengwk.kkstudio.harness.contributor.api.HarnessCatalog;
import fun.fengwk.kkstudio.harness.contributor.api.ToolContribution;
import fun.fengwk.kkstudio.harness.tool.ToolVisibility;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;

import java.util.List;

/** {@link ProjectToolConfiguration} 的 Spring 装配契约：每个组件唯一暴露，且 Contributor 能冻结出唯一的 INTERNAL 交接工具。 */
class ProjectToolConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withBean(IssueAgentThreadRepository.class, () -> mock(IssueAgentThreadRepository.class))
          .withBean(ProjectRepository.class, () -> mock(ProjectRepository.class))
          .withBean(IssueRepository.class, () -> mock(IssueRepository.class))
          .withBean(IssueRunRepository.class, () -> mock(IssueRunRepository.class))
          .withBean(ProjectWorkflowJsonCodec.class, ProjectWorkflowJsonCodec::new)
          .withUserConfiguration(ProjectToolConfiguration.class);

  /** 测试意图：五个组件各自只有一个 Bean，避免重复声明导致注入歧义。 */
  @Test
  void exposesEachComponentExactlyOnce() {
    runner.run(
        context -> {
          assertEquals(1, context.getBeanNamesForType(ProjectIssueTurnResolver.class).length);
          assertEquals(1, context.getBeanNamesForType(IssueTransitionService.class).length);
          assertEquals(1, context.getBeanNamesForType(IssueTransitionTool.class).length);
          assertEquals(1, context.getBeanNamesForType(ProjectHarnessContributor.class).length);
        });
  }

  /**
   * 测试意图：冻结出的 Catalog 必须包含唯一的 {@code issue_transition} 且为 INTERNAL——模型可见工具面只由平台按归属注入， Agent
   * 配置无法自行选择。
   */
  @Test
  void freezesOneInternalTransitionTool() {
    runner.run(
        context -> {
          HarnessCatalog catalog =
              HarnessCatalog.from(List.of(context.getBean(ProjectHarnessContributor.class)));

          assertEquals(1, catalog.tools().size());
          ToolContribution tool = catalog.tools().get(0);
          assertEquals(IssueTransitionTool.NAME, tool.definition().descriptor().name());
          assertEquals(ToolVisibility.INTERNAL, tool.definition().visibility());
          assertEquals("project:issue.transition", tool.id().toString());
          assertEquals(List.of(), catalog.selectableTools());
          assertTrue(catalog.contextProjectors().isEmpty(), "上下文必须由 Turn 解析器按 Thread 注入");
        });
  }
}
