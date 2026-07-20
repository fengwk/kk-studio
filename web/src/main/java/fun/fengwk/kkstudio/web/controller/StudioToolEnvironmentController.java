package fun.fengwk.kkstudio.web.controller;

import fun.fengwk.convention4j.api.result.Result;
import fun.fengwk.convention4j.common.result.Results;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironment;
import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.share.model.LiveEnvironmentDTO;
import fun.fengwk.kkstudio.share.model.LiveEnvironmentSkillDTO;
import fun.fengwk.kkstudio.share.model.LiveEnvironmentToolDTO;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only live Environment registry API.
 *
 * <p>Environments are server-memory only; there is no create/update/delete surface. Daemon
 * connections populate the registry through the WebSocket gateway.
 */
@AllArgsConstructor
@RequestMapping("/api/environments")
@RestController
public class StudioToolEnvironmentController {

  private final LiveEnvironmentRegistry environmentRegistry;

  @GetMapping
  public Result<List<LiveEnvironmentDTO>> listEnvironments() {
    List<LiveEnvironmentDTO> result = new ArrayList<>();
    for (LiveEnvironment environment : environmentRegistry.list()) {
      result.add(toDto(environment));
    }
    return Results.ok(List.copyOf(result));
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
