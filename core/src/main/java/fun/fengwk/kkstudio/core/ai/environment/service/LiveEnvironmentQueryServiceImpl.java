package fun.fengwk.kkstudio.core.ai.environment.service;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironment;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.share.ai.environment.LiveEnvironmentDTO;
import fun.fengwk.kkstudio.share.ai.environment.LiveEnvironmentSkillDTO;
import fun.fengwk.kkstudio.share.ai.environment.LiveEnvironmentToolDTO;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Projects the server-memory live Environment registry into share DTOs. */
@Service
public class LiveEnvironmentQueryServiceImpl implements LiveEnvironmentQueryService {

  private final LiveEnvironmentRegistry environmentRegistry;

  public LiveEnvironmentQueryServiceImpl(LiveEnvironmentRegistry environmentRegistry) {
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
  }

  @Override
  public List<LiveEnvironmentDTO> listEnvironments() {
    List<LiveEnvironmentDTO> result = new ArrayList<>();
    for (LiveEnvironment environment : environmentRegistry.list()) {
      result.add(toDto(environment));
    }
    return List.copyOf(result);
  }

  private static LiveEnvironmentDTO toDto(LiveEnvironment environment) {
    LiveEnvironmentDTO dto = new LiveEnvironmentDTO();
    dto.setName(environment.environmentName());
    dto.setStatus(environment.status().name());
    dto.setLastSeen(environment.lastSeenAt());
    List<LiveEnvironmentToolDTO> tools = new ArrayList<>();
    for (ToolDescriptor tool : environment.tools()) {
      LiveEnvironmentToolDTO toolDto = new LiveEnvironmentToolDTO();
      toolDto.setName(tool.name());
      toolDto.setVersion(tool.version());
      toolDto.setDescription(tool.description());
      tools.add(toolDto);
    }
    dto.setTools(List.copyOf(tools));
    List<LiveEnvironmentSkillDTO> skills = new ArrayList<>();
    for (DaemonSkillDescriptor skill : environment.skills()) {
      LiveEnvironmentSkillDTO skillDto = new LiveEnvironmentSkillDTO();
      skillDto.setName(skill.name());
      skillDto.setDescription(skill.description());
      skills.add(skillDto);
    }
    dto.setSkills(List.copyOf(skills));
    return dto;
  }
}
