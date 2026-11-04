package fun.fengwk.kkstudio.project.service;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.platform.catalog.definition.AgentReferenceCheck;
import fun.fengwk.kkstudio.project.repo.ProjectRepository;

import java.util.Objects;

/** 检查未归档项目工作流阶段的 Agent 引用。 */
@Service
public class ProjectAgentReferenceCheck implements AgentReferenceCheck {

  private final ProjectRepository projects;

  public ProjectAgentReferenceCheck(ProjectRepository projects) {
    this.projects = Objects.requireNonNull(projects, "projects");
  }

  @Override
  public boolean isReferenced(String agentName) {
    return projects.existsReferencingAgent(agentName);
  }
}
