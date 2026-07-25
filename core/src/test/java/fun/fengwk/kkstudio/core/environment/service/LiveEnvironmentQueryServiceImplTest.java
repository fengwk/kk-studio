package fun.fengwk.kkstudio.core.environment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironment;
import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.core.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonToolCapabilitiesCodec.DaemonToolCapabilities;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;
import fun.fengwk.kkstudio.share.model.LiveEnvironmentDTO;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Verifies registry snapshots project into share DTOs without harness types leaking out. */
class LiveEnvironmentQueryServiceImplTest {

  @Test
  void projectsRegistryEntriesToShareDtos() {
    Instant now = Instant.parse("2026-07-26T00:00:00Z");
    ToolDescriptor tool =
        new ToolDescriptor(
            "echo",
            "1",
            "echo tool",
            "echo",
            new ToolParamsSchema(null, Map.of(), Set.of(), false),
            ToolSideEffect.READ_ONLY,
            Duration.ofSeconds(1));
    DaemonSkillDescriptor skill = new DaemonSkillDescriptor("demo", "demo skill");
    EnvironmentDaemonConnection connection = mock(EnvironmentDaemonConnection.class);
    when(connection.isOpen()).thenReturn(true);
    LiveEnvironment environment =
        new LiveEnvironment(
            "dev",
            LiveEnvironmentStatus.READY,
            connection,
            new DaemonToolCapabilities(List.of(tool), List.of(skill)),
            now);
    LiveEnvironmentRegistry registry = mock(LiveEnvironmentRegistry.class);
    when(registry.list()).thenReturn(List.of(environment));

    LiveEnvironmentQueryService service = new LiveEnvironmentQueryServiceImpl(registry);
    List<LiveEnvironmentDTO> listed = service.listEnvironments();

    assertEquals(1, listed.size());
    LiveEnvironmentDTO dto = listed.get(0);
    assertEquals("dev", dto.getName());
    assertEquals("READY", dto.getStatus());
    assertEquals(now, dto.getLastSeen());
    assertEquals(1, dto.getTools().size());
    assertEquals("echo", dto.getTools().get(0).getName());
    assertEquals("1", dto.getTools().get(0).getVersion());
    assertEquals("echo tool", dto.getTools().get(0).getDescription());
    assertEquals(1, dto.getSkills().size());
    assertEquals("demo", dto.getSkills().get(0).getName());
    assertEquals("demo skill", dto.getSkills().get(0).getDescription());
  }
}
