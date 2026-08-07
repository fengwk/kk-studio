package fun.fengwk.kkstudio.core.ai.environment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironment;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.harness.tool.EnvironmentId;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.share.ai.environment.LiveEnvironmentDTO;

import java.time.Instant;
import java.util.List;

/**
 * 验证 registry 快照投影为 share DTO（canonical id 与 display name），且不会有 harness 类型泄漏。
 *
 * <p>Environment 工具来自静态 {@link EnvironmentToolCatalog}；daemon 仅对外声明 skill。
 */
class LiveEnvironmentQueryServiceImplTest {

  private static final EnvironmentId ENVIRONMENT_ID =
      new EnvironmentId("0f8fad5b-d9cb-469f-a165-70867728950e");

  @Test
  void projectsRegistryEntriesToShareDtos() {
    Instant now = Instant.parse("2026-07-26T00:00:00Z");
    DaemonSkillDescriptor skill = new DaemonSkillDescriptor("demo", "demo skill");
    EnvironmentDaemonConnection connection = mock(EnvironmentDaemonConnection.class);
    when(connection.isOpen()).thenReturn(true);
    LiveEnvironment environment =
        new LiveEnvironment(
            ENVIRONMENT_ID, "dev", LiveEnvironmentStatus.READY, connection, List.of(skill), now);
    LiveEnvironmentRegistry registry = mock(LiveEnvironmentRegistry.class);
    when(registry.list()).thenReturn(List.of(environment));

    LiveEnvironmentQueryService service = new LiveEnvironmentQueryServiceImpl(registry);
    List<LiveEnvironmentDTO> listed = service.listEnvironments();

    assertEquals(1, listed.size());
    LiveEnvironmentDTO dto = listed.get(0);
    assertEquals(ENVIRONMENT_ID.value(), dto.getId());
    assertEquals("dev", dto.getName());
    assertEquals("READY", dto.getStatus());
    assertEquals(now, dto.getLastSeen());
    // Environment 工具由 EnvironmentToolCatalog 固定提供，无论 daemon 的 READY 载荷如何都会暴露。
    assertEquals(EnvironmentToolCatalog.descriptors().size(), dto.getTools().size());
    assertEquals(1, dto.getSkills().size());
    assertEquals("demo", dto.getSkills().get(0).getName());
    assertEquals("demo skill", dto.getSkills().get(0).getDescription());
  }
}
