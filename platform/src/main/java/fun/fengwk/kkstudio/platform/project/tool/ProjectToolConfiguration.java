package fun.fengwk.kkstudio.platform.project.tool;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.platform.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;
import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;

/**
 * Project Issue Agent 交接工具与 Turn 事实解析的 Spring 装配。
 *
 * <p>依赖都是 Project 域自身的仓库端口与 workflow 领域编解码器；没有 Harness Runtime 或其它域的对象，因为 Turn 规划与交接都只读写 Project
 * 事实。缺失依赖时启动期明确失败，不做静默降级。
 */
@Configuration(proxyBeanMethods = false)
public class ProjectToolConfiguration {

  @Bean(name = "projectIssueTurnResolver")
  @ConditionalOnMissingBean(name = "projectIssueTurnResolver")
  public ProjectIssueTurnResolver projectIssueTurnResolver(
      IssueAgentThreadRepository issueAgentThreadRepository,
      IssueRepository issueRepository,
      ProjectRepository projectRepository,
      IssueRunRepository issueRunRepository,
      ProjectWorkflowJsonCodec workflowCodec) {
    return new DatabaseProjectIssueTurnResolver(
        issueAgentThreadRepository,
        issueRepository,
        projectRepository,
        issueRunRepository,
        workflowCodec);
  }

  @Bean(name = "issueTransitionService")
  @ConditionalOnMissingBean(name = "issueTransitionService")
  public IssueTransitionService issueTransitionService(
      IssueAgentThreadRepository issueAgentThreadRepository,
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueRunRepository issueRunRepository,
      ProjectWorkflowJsonCodec workflowCodec) {
    return new IssueTransitionService(
        issueAgentThreadRepository,
        projectRepository,
        issueRepository,
        issueRunRepository,
        workflowCodec);
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
}
