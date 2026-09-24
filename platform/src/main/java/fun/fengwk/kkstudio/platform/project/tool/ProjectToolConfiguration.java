package fun.fengwk.kkstudio.platform.project.tool;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import fun.fengwk.kkstudio.harness.runtime.store.HarnessStore;
import fun.fengwk.kkstudio.platform.project.repo.IssueAgentSessionRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRepository;
import fun.fengwk.kkstudio.platform.project.repo.IssueRunRepository;
import fun.fengwk.kkstudio.platform.project.repo.ProjectRepository;

import java.util.Arrays;
import java.util.List;

/** Project 角色工具包与 Contributor Spring 装配配置。 */
@Configuration
public class ProjectToolConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public ProjectThreadOwnerResolver projectThreadOwnerResolver(
      ObjectProvider<HarnessStore> harnessStoreProvider,
      IssueAgentSessionRepository issueAgentSessionRepository,
      ProjectRepository projectRepository,
      IssueRepository issueRepository,
      IssueRunRepository issueRunRepository) {
    return ProjectThreadOwnerResolver.withStoreSupplier(
        harnessStoreProvider::getIfAvailable,
        issueAgentSessionRepository,
        projectRepository,
        issueRepository,
        issueRunRepository);
  }

  @Bean
  @ConditionalOnMissingBean
  public ProjectRoleToolSelector projectRoleToolSelector(
      ProjectThreadOwnerResolver projectThreadOwnerResolver) {
    return new ProjectRoleToolSelector(projectThreadOwnerResolver);
  }

  @Bean
  @ConditionalOnMissingBean
  public ProjectHarnessContributor projectHarnessContributor(
      ProjectThreadOwnerResolver ownerResolver, ProjectRoleToolService toolService) {
    List<ProjectRoleTool> tools =
        Arrays.stream(ProjectRoleToolType.values())
            .map(type -> new ProjectRoleTool(type, ownerResolver, toolService))
            .toList();
    return new ProjectHarnessContributor(tools);
  }
}
