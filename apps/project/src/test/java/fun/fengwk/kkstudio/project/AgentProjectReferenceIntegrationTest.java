package fun.fengwk.kkstudio.project;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentRepository;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentService;
import fun.fengwk.kkstudio.project.model.Project;

import java.util.UUID;

/** 删除 Agent 时，未归档 Project 工作流阶段对它的引用必须阻止删除，归档后放行。 */
class AgentProjectReferenceIntegrationTest extends ProjectTestSupport {

  @Autowired private AgentService agentService;
  @Autowired private AgentRepository agentRepository;

  @Test
  void projectWorkflowStageBlocksAgentDeleteUntilTheProjectIsArchived() {
    String agent = createAgent();
    UUID projectId = createProjectWithStages("AgentReferenceProj", agent, agent, 3);

    assertThrows(
        DomainException.class, () -> agentService.deleteAgent(agent, currentVersion(agent)));

    Project project = projectService.getProject(projectId);
    projectService.archiveProject(projectId, project.getVersion());
    agentService.deleteAgent(agent, currentVersion(agent));
    assertNull(agentRepository.getByName(agent));
  }

  private String currentVersion(String agent) {
    return String.valueOf(agentRepository.getByName(agent).version());
  }
}
