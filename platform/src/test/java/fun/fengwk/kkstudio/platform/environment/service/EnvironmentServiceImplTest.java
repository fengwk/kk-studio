package fun.fengwk.kkstudio.platform.environment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMcpServerDescriptor;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMcpServerStatus;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonMcpToolDescriptor;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCardDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class EnvironmentServiceImplTest {

  private static final UUID ENV_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
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
  void createExposesRegistrationTokenOnce() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettings.DEFAULT);

    when(repo.existsByName("local-env")).thenReturn(false);
    when(repo.create(any())).thenReturn(true);
    when(repo.getById(any()))
        .thenAnswer(
            invocation -> {
              UUID id = invocation.getArgument(0);
              Environment env = new Environment();
              env.setId(id);
              env.setName("local-env");
              env.setRegistrationToken("secret-token");
              env.setVersion(0L);
              env.setCreateTime(NOW);
              env.setUpdateTime(NOW);
              return env;
            });

    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(repo, registry, agents, jdbc, snapshot, CLOCK);

    EnvironmentCreateDTO create = new EnvironmentCreateDTO();
    create.setName("local-env");

    EnvironmentCardDTO result = service.create(create);

    assertEquals("local-env", result.getName());
    assertEquals("secret-token", result.getRegistrationToken());
    assertEquals("0", result.getVersion());
  }

  @Test
  void getAndListNeverExposeRegistrationToken() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettings.DEFAULT);

    Environment env = new Environment();
    env.setId(ENV_ID);
    env.setName("local-env");
    env.setRegistrationToken("secret-token");
    env.setVersion(0L);
    env.setCreateTime(NOW);
    env.setUpdateTime(NOW);

    when(repo.getById(ENV_ID)).thenReturn(env);
    when(repo.listNewestFirst()).thenReturn(List.of(env));

    EnvironmentConnection conn =
        new EnvironmentConnection(
            EnvironmentId.of(ENV_ID),
            UUID.randomUUID(),
            UUID.randomUUID(),
            LiveEnvironmentStatus.READY,
            new DaemonCapabilities(
                DaemonCapabilities.VERSION,
                new DaemonEnvironmentInfo(
                    DaemonOperatingSystem.LINUX, "Asia/Shanghai", "Note", "/home/dev"),
                List.of(new DaemonSkillDescriptor("dev", "dev skill")),
                List.of(
                    new DaemonMcpServerDescriptor(
                        "fs",
                        DaemonMcpServerStatus.READY,
                        null,
                        List.of(new DaemonMcpToolDescriptor("read", "read file"))))),
            NOW,
            NOW.plusSeconds(60));

    when(registry.find(EnvironmentId.of(ENV_ID))).thenReturn(Optional.of(conn));

    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(repo, registry, agents, jdbc, snapshot, CLOCK);

    EnvironmentCardDTO single = service.get(EnvironmentId.of(ENV_ID));
    assertNull(single.getRegistrationToken());
    assertEquals("READY", single.getStatus());
    assertTrue(single.isReady());
    assertEquals("/home/dev", single.getRootPath());
    assertEquals(1, single.getSkills().size());
    assertEquals("dev", single.getSkills().get(0).getName());

    List<EnvironmentCardDTO> list = service.list();
    assertEquals(1, list.size());
    assertNull(list.get(0).getRegistrationToken());
    assertEquals("READY", list.get(0).getStatus());
  }

  @Test
  void rotateTokenOnlyAllowedWhenOffline() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettings.DEFAULT);

    Environment env = new Environment();
    env.setId(ENV_ID);
    env.setName("local-env");
    env.setRegistrationToken("old-token");
    env.setVersion(0L);
    env.setCreateTime(NOW);
    env.setUpdateTime(NOW);

    when(repo.lockById(ENV_ID)).thenReturn(env);
    when(repo.getById(ENV_ID)).thenReturn(env);
    when(repo.updateById(any(), eq(0L))).thenReturn(true);

    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(repo, registry, agents, jdbc, snapshot, CLOCK);

    // Online -> rejects
    when(registry.isOnline(EnvironmentId.of(ENV_ID), NOW)).thenReturn(true);
    assertThrows(AiInUseException.class, () -> service.rotateToken(EnvironmentId.of(ENV_ID), "0"));

    // Offline -> succeeds and returns new token
    when(registry.isOnline(EnvironmentId.of(ENV_ID), NOW)).thenReturn(false);
    EnvironmentCardDTO rotated = service.rotateToken(EnvironmentId.of(ENV_ID), "0");
    assertNotNull(rotated.getRegistrationToken());
  }

  @Test
  void deleteChecksOnlineAgentRefsAndActiveWork() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettings.DEFAULT);

    Environment env = new Environment();
    env.setId(ENV_ID);
    env.setName("local-env");
    env.setRegistrationToken("token");
    env.setVersion(0L);

    when(repo.lockById(ENV_ID)).thenReturn(env);
    when(repo.deleteById(ENV_ID, 0L)).thenReturn(true);

    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(repo, registry, agents, jdbc, snapshot, CLOCK);

    // 1. Online -> rejects
    when(registry.isOnline(EnvironmentId.of(ENV_ID), NOW)).thenReturn(true);
    assertThrows(AiInUseException.class, () -> service.delete(EnvironmentId.of(ENV_ID), "0"));

    // 2. Offline but referenced by Agent -> rejects
    when(registry.isOnline(EnvironmentId.of(ENV_ID), NOW)).thenReturn(false);
    when(agents.existsByEnvironmentId(ENV_ID)).thenReturn(true);
    assertThrows(AiInUseException.class, () -> service.delete(EnvironmentId.of(ENV_ID), "0"));

    // 3. Offline, no agent ref, but active work in harness_work -> rejects
    when(agents.existsByEnvironmentId(ENV_ID)).thenReturn(false);
    when(jdbc.queryForObject(any(String.class), eq(Integer.class), eq(ENV_ID))).thenReturn(1);
    assertThrows(AiInUseException.class, () -> service.delete(EnvironmentId.of(ENV_ID), "0"));

    // 4. All clear -> succeeds
    when(jdbc.queryForObject(any(String.class), eq(Integer.class), eq(ENV_ID))).thenReturn(0);
    service.delete(EnvironmentId.of(ENV_ID), "0");
    verify(repo).deleteById(ENV_ID, 0L);
  }
}
