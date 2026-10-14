package fun.fengwk.kkstudio.platform.project.tool;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;

/**
 * Project Issue 交接工具、Run 上下文投影与 Thread 归属判定的 Spring 装配。
 *
 * <p>依赖都是 Project 域自身的仓库端口与 workflow 领域编解码器；没有 Harness Runtime 或其它域的对象，因为工具执行与上下文投影都只读写 Project
 * 事实。缺失依赖时启动期明确失败，不做静默降级。
 */
@Configuration(proxyBeanMethods = false)
public class ProjectToolConfiguration {

  @Bean(name = "issueTransitionService")
  @ConditionalOnMissingBean(name = "issueTransitionService")
  public IssueTransitionService issueTransitionService(
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueRunRepository issueRunRepository,
      ProjectWorkflowJsonCodec workflowCodec) {
    return new IssueTransitionService(
        projectRepository, issueRepository, issueRunRepository, workflowCodec);
  }

  @Bean(name = "issueTransitionTool")
  @ConditionalOnMissingBean(name = "issueTransitionTool")
  public IssueTransitionTool issueTransitionTool(IssueTransitionService issueTransitionService) {
    return new IssueTransitionTool(issueTransitionService);
  }

  @Bean(name = "projectHarnessContributor")
  @ConditionalOnMissingBean(name = "projectHarnessContributor")
  public ProjectHarnessContributor projectHarnessContributor(
      IssueTransitionTool issueTransitionTool) {
    return new ProjectHarnessContributor(issueTransitionTool);
  }

  @Bean(name = "projectThreadOwnerResolver")
  @ConditionalOnMissingBean(name = "projectThreadOwnerResolver")
  public ProjectThreadOwnerResolver projectThreadOwnerResolver(
      IssueAgentThreadRepository issueAgentThreadRepository) {
    return new DatabaseProjectThreadOwnerResolver(issueAgentThreadRepository);
  }
}
