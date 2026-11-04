package fun.fengwk.kkstudio.project.tool;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.project.domain.ProjectWorkflowJsonCodec;
import fun.fengwk.kkstudio.project.platform.IssueThreadExecution;
import fun.fengwk.kkstudio.project.repo.IssueAgentThreadRepository;
import fun.fengwk.kkstudio.project.repo.IssueRepository;
import fun.fengwk.kkstudio.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;

/**
 * Project Issue 交接工具的 Spring 装配。
 *
 * <p>依赖都是 Project 域自身的仓库端口、Project 自己的 Thread 执行事实端口与 workflow 领域编解码器；没有 Harness Runtime 或其它域的对象，
 * 因为工具执行只读写 Project 事实与自己绑定 Thread 的历史位置。缺失依赖时启动期明确失败，不做静默降级。
 */
@Configuration(proxyBeanMethods = false)
public class ProjectToolConfiguration {

  @Bean(name = "issueTransitionService")
  @ConditionalOnMissingBean(name = "issueTransitionService")
  public IssueTransitionService issueTransitionService(
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueRunRepository issueRunRepository,
      IssueAgentThreadRepository issueAgentThreadRepository,
      IssueThreadExecution issueThreadExecution,
      ProjectWorkflowJsonCodec workflowCodec) {
    return new IssueTransitionService(
        projectRepository,
        issueRepository,
        issueRunRepository,
        issueAgentThreadRepository,
        issueThreadExecution,
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
