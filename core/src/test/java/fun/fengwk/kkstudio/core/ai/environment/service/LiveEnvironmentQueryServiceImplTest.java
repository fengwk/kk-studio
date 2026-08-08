package fun.fengwk.kkstudio.core.ai.environment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentDaemonConnection;
import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentGatewayProperties;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironment;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentRegistry;
import fun.fengwk.kkstudio.core.ai.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.harness.tool.EnvironmentName;
import fun.fengwk.kkstudio.harness.tool.EnvironmentToolCatalog;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMcpServerDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMcpServerStatus;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonMcpToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.share.ai.environment.LiveEnvironmentDTO;
import fun.fengwk.kkstudio.share.ai.environment.LiveEnvironmentMcpServerDTO;
import fun.fengwk.kkstudio.share.ai.environment.LiveEnvironmentMcpToolDTO;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 验证 registry 快照投影为 share DTO（只按 canonical 名称键控 + ready 可用性标记），且不会有 harness 类型泄漏。
 *
 * <p>Environment 工具来自静态 {@link EnvironmentToolCatalog}；daemon 仅对外声明 skill。
 */
class LiveEnvironmentQueryServiceImplTest {

  private static final EnvironmentName ENVIRONMENT_NAME = new EnvironmentName("env-1");
  private static final Instant NOW = Instant.parse("2026-07-26T00:00:00Z");
  private static final Clock CLOCK =
      new Clock() {
        @Override
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return NOW;
        }
      };

  @Test
  void projectsRegistryEntriesToShareDtos() {
    DaemonSkillDescriptor skill = new DaemonSkillDescriptor("demo", "demo skill");
    DaemonMcpServerDescriptor fs =
        new DaemonMcpServerDescriptor(
            "fs",
            DaemonMcpServerStatus.READY,
            null,
            List.of(new DaemonMcpToolDescriptor("read_file", "Read a file")));
    EnvironmentDaemonConnection connection = mock(EnvironmentDaemonConnection.class);
    when(connection.isOpen()).thenReturn(true);
    LiveEnvironment environment =
        new LiveEnvironment(
            ENVIRONMENT_NAME,
            LiveEnvironmentStatus.READY,
            connection,
            new DaemonCapabilities(DaemonCapabilities.VERSION, List.of(skill), List.of(fs)),
            NOW);
    LiveEnvironmentRegistry registry = mock(LiveEnvironmentRegistry.class);
    when(registry.list()).thenReturn(List.of(environment));

    LiveEnvironmentQueryService service =
        new LiveEnvironmentQueryServiceImpl(registry, new EnvironmentGatewayProperties(), CLOCK);
    List<LiveEnvironmentDTO> listed = service.listEnvironments();

    assertEquals(1, listed.size());
    LiveEnvironmentDTO dto = listed.get(0);
    assertEquals(ENVIRONMENT_NAME.value(), dto.getName());
    assertEquals("READY", dto.getStatus());
    assertTrue(dto.isReady());
    assertEquals(NOW, dto.getLastSeen());
    // Environment 工具由 EnvironmentToolCatalog 固定提供，无论 daemon 的 READY 载荷如何都会暴露。
    assertEquals(EnvironmentToolCatalog.descriptors().size(), dto.getTools().size());
    assertEquals(1, dto.getSkills().size());
    assertEquals("demo", dto.getSkills().get(0).getName());
    assertEquals("demo skill", dto.getSkills().get(0).getDescription());
    // MCP server 只投影摘要：状态/错误/工具名与描述，不暴露 headers/命令/URL/完整 schema。
    assertEquals(1, dto.getMcpServers().size());
    LiveEnvironmentMcpServerDTO serverDto = dto.getMcpServers().get(0);
    assertEquals("fs", serverDto.getName());
    assertEquals("READY", serverDto.getStatus());
    assertEquals(null, serverDto.getError());
    assertEquals(1, serverDto.getTools().size());
    LiveEnvironmentMcpToolDTO toolDto = serverDto.getTools().get(0);
    assertEquals("read_file", toolDto.getName());
    assertEquals("Read a file", toolDto.getDescription());
  }

  @Test
  void projectsStaleOrClosedEnvironmentAsNotReady() {
    EnvironmentDaemonConnection connection = mock(EnvironmentDaemonConnection.class);
    when(connection.isOpen()).thenReturn(true);
    LiveEnvironment stale =
        new LiveEnvironment(
            ENVIRONMENT_NAME,
            LiveEnvironmentStatus.READY,
            connection,
            DaemonCapabilities.empty(),
            NOW.minus(Duration.ofSeconds(120)));
    EnvironmentGatewayProperties properties = new EnvironmentGatewayProperties();
    properties.setHeartbeatTimeout(Duration.ofSeconds(60));
    LiveEnvironmentRegistry registry = mock(LiveEnvironmentRegistry.class);
    when(registry.list()).thenReturn(List.of(stale));

    LiveEnvironmentQueryService service =
        new LiveEnvironmentQueryServiceImpl(registry, properties, CLOCK);
    LiveEnvironmentDTO dto = service.listEnvironments().get(0);
    assertFalse(dto.isReady());
  }
}
