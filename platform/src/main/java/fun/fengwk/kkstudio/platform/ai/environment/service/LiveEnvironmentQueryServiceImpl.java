package fun.fengwk.kkstudio.platform.ai.environment.service;

import org.springframework.stereotype.Service;

import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMcpServerDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMcpToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.platform.ai.environment.registry.LiveEnvironment;
import fun.fengwk.kkstudio.platform.ai.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.platform.systemsettings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.share.ai.environment.LiveEnvironmentDTO;
import fun.fengwk.kkstudio.share.ai.environment.LiveEnvironmentMcpServerDTO;
import fun.fengwk.kkstudio.share.ai.environment.LiveEnvironmentMcpToolDTO;
import fun.fengwk.kkstudio.share.ai.environment.LiveEnvironmentSkillDTO;
import fun.fengwk.kkstudio.share.ai.environment.LiveEnvironmentToolDTO;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 把服务端内存 live Environment registry 投影为 share DTO（只按 canonical 名称键控）。 */
@Service
public class LiveEnvironmentQueryServiceImpl implements LiveEnvironmentQueryService {

  private final LiveEnvironmentRegistry environmentRegistry;
  private final SystemSettingsSnapshot snapshot;
  private final Clock clock;

  public LiveEnvironmentQueryServiceImpl(
      LiveEnvironmentRegistry environmentRegistry, SystemSettingsSnapshot snapshot, Clock clock) {
    this.environmentRegistry = Objects.requireNonNull(environmentRegistry, "environmentRegistry");
    this.snapshot = Objects.requireNonNull(snapshot, "snapshot");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public List<LiveEnvironmentDTO> listEnvironments() {
    List<LiveEnvironmentDTO> result = new ArrayList<>();
    for (LiveEnvironment environment : environmentRegistry.list()) {
      result.add(toDto(environment));
    }
    return List.copyOf(result);
  }

  private LiveEnvironmentDTO toDto(LiveEnvironment environment) {
    LiveEnvironmentDTO dto = new LiveEnvironmentDTO();
    dto.setName(environment.name().value());
    dto.setStatus(environment.status().name());
    dto.setReady(
        environment.isReady(
            clock.instant(),
            Duration.ofMillis(snapshot.get().environment().heartbeatTimeoutMillis())));
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
    List<LiveEnvironmentMcpServerDTO> mcpServers = new ArrayList<>();
    for (DaemonMcpServerDescriptor server : environment.mcpServers()) {
      LiveEnvironmentMcpServerDTO serverDto = new LiveEnvironmentMcpServerDTO();
      serverDto.setName(server.name());
      serverDto.setStatus(server.status().name());
      serverDto.setError(server.error());
      List<LiveEnvironmentMcpToolDTO> serverTools = new ArrayList<>();
      for (DaemonMcpToolDescriptor tool : server.tools()) {
        LiveEnvironmentMcpToolDTO toolDto = new LiveEnvironmentMcpToolDTO();
        toolDto.setName(tool.name());
        toolDto.setDescription(tool.description());
        serverTools.add(toolDto);
      }
      serverDto.setTools(List.copyOf(serverTools));
      mcpServers.add(serverDto);
    }
    dto.setMcpServers(List.copyOf(mcpServers));
    dto.setRootPath(environment.rootPath());
    return dto;
  }
}
