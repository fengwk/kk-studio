package fun.fengwk.kkstudio.platform.environment.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCardDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentRegistrationTokenDTO;

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

  /** 测试意图：Environment 创建只写入 Card 行，创建时不存在任何已被接受的 READY 宿主 metadata。 */
  @Test
  void createLeavesNoAcceptedHostMetadata() {
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
              Environment env = new Environment();
              env.setId(invocation.getArgument(0));
              env.setName("local-env");
              env.setRegistrationToken("secret-token");
              env.setVersion(0L);
              env.setCreateTime(NOW);
              env.setUpdateTime(NOW);
              return env;
            });
    when(registry.find(any())).thenReturn(Optional.empty());

    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(repo, registry, jdbc, snapshot, CLOCK);

    EnvironmentCreateDTO create = new EnvironmentCreateDTO();
    create.setName("local-env");
    EnvironmentCardDTO card = service.create(create);

    assertNotNull(card.getId());
    assertEquals("OFFLINE", card.getStatus());
    assertNull(card.getOperatingSystem());
    assertNull(card.getTimeZone());
    assertNull(card.getNote());
    assertNull(card.getUserName());
    assertNull(card.getHomeDirectory());
  }

  /** 测试意图：Card 行创建失败必须向上冒泡，绝不留下半成品。 */
  @Test
  void createPropagatesCardInsertFailure() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettings.DEFAULT);

    when(repo.existsByName("local-env")).thenReturn(false);
    when(repo.create(any())).thenReturn(false);

    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(repo, registry, jdbc, snapshot, CLOCK);

    EnvironmentCreateDTO create = new EnvironmentCreateDTO();
    create.setName("local-env");
    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.create(create));
    assertTrue(error.getMessage().contains("create environment failed"), error.getMessage());
  }

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
        new EnvironmentServiceImpl(repo, registry, jdbc, snapshot, CLOCK);

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
                    DaemonOperatingSystem.LINUX, "Asia/Shanghai", "dev", "/home/dev", "Note")),
            NOW,
            NOW.plusSeconds(60));

    when(registry.find(EnvironmentId.of(ENV_ID))).thenReturn(Optional.of(conn));

    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(repo, registry, jdbc, snapshot, CLOCK);

    EnvironmentCardDTO single = service.get(EnvironmentId.of(ENV_ID));
    assertNull(single.getRegistrationToken());
    assertEquals("READY", single.getStatus());
    assertTrue(single.isReady());
    assertEquals("linux", single.getOperatingSystem());
    assertEquals("Asia/Shanghai", single.getTimeZone());
    assertEquals("Note", single.getNote());
    assertEquals("dev", single.getUserName());
    assertEquals("/home/dev", single.getHomeDirectory());
    List<EnvironmentCardDTO> list = service.list();
    assertEquals(1, list.size());
    assertNull(list.get(0).getRegistrationToken());
    assertEquals("READY", list.get(0).getStatus());
  }

  /** 测试意图：从未连接过的 Environment 没有保留的宿主 metadata，Card 除状态外全部为空。 */
  @Test
  void neverConnectedCardHasNoHostMetadata() {
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
    // 没有任何连接行：Environment 从未连接，无任何已保留事实。
    when(registry.find(EnvironmentId.of(ENV_ID))).thenReturn(Optional.empty());

    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(repo, registry, jdbc, snapshot, CLOCK);

    EnvironmentCardDTO card = service.get(EnvironmentId.of(ENV_ID));

    assertEquals("OFFLINE", card.getStatus());
    assertFalse(card.isReady());
    assertNull(card.getOperatingSystem());
    assertNull(card.getTimeZone());
    assertNull(card.getNote());
    assertNull(card.getUserName());
    assertNull(card.getHomeDirectory());
    assertTrue(card.getCapabilities().isEmpty());
  }

  /**
   * 测试意图：CONNECTING 连接行仍保留最近一次 READY 的宿主 metadata，Card 直接投影该保留事实而不清空。
   *
   * <p>这是"断线或重新 CONNECTING 不清空 runtime_info"契约在产品投影侧的可观察行为。
   */
  @Test
  void connectingConnectionStillProjectsRetainedHostMetadata() {
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

    EnvironmentConnection conn =
        new EnvironmentConnection(
            EnvironmentId.of(ENV_ID),
            UUID.randomUUID(),
            UUID.randomUUID(),
            LiveEnvironmentStatus.CONNECTING,
            new DaemonCapabilities(
                DaemonCapabilities.VERSION,
                new DaemonEnvironmentInfo(
                    DaemonOperatingSystem.WSL,
                    "Asia/Shanghai",
                    "dev",
                    "/home/dev",
                    "Retained note")),
            NOW,
            NOW.plusSeconds(60));
    when(registry.find(EnvironmentId.of(ENV_ID))).thenReturn(Optional.of(conn));

    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(repo, registry, jdbc, snapshot, CLOCK);

    EnvironmentCardDTO card = service.get(EnvironmentId.of(ENV_ID));

    assertEquals("CONNECTING", card.getStatus());
    assertFalse(card.isReady());
    assertEquals("wsl", card.getOperatingSystem());
    assertEquals("Asia/Shanghai", card.getTimeZone());
    assertEquals("Retained note", card.getNote());
    assertEquals("dev", card.getUserName());
    assertEquals("/home/dev", card.getHomeDirectory());
  }

  /** 测试意图：registrationToken 只读端点幂等——连续读取返回同一 token，且不推进 version / updateTime（不轮换、不写库）。 */
  @Test
  void getRegistrationTokenReturnsStableValueWithoutRotating() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettings.DEFAULT);

    Environment env = new Environment();
    env.setId(ENV_ID);
    env.setName("local-env");
    env.setRegistrationToken("stable-token");
    env.setVersion(3L);
    env.setCreateTime(NOW);
    env.setUpdateTime(NOW);
    when(repo.getById(ENV_ID)).thenReturn(env);

    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(repo, registry, jdbc, snapshot, CLOCK);

    EnvironmentRegistrationTokenDTO first = service.getRegistrationToken(EnvironmentId.of(ENV_ID));
    EnvironmentRegistrationTokenDTO second = service.getRegistrationToken(EnvironmentId.of(ENV_ID));

    assertEquals("stable-token", first.getRegistrationToken());
    assertEquals("stable-token", second.getRegistrationToken());
    assertEquals("3", first.getVersion());
    assertEquals("3", second.getVersion());
    // 只读路径不得写库，也不得触碰租约
    verify(repo, never()).updateById(any(), anyLong());
    verify(registry, never()).hasActiveLease(any());
    assertEquals("stable-token", env.getRegistrationToken());
    assertEquals(3L, env.getVersion());
    assertEquals(NOW, env.getUpdateTime());
  }

  /** 测试意图：读取不存在的 Environment 时抛出稳定的 404 语义错误，而不是返回空 token。 */
  @Test
  void getRegistrationTokenRejectsMissingEnvironment() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettings.DEFAULT);
    when(repo.getById(ENV_ID)).thenReturn(null);

    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(repo, registry, jdbc, snapshot, CLOCK);

    assertThrows(
        AiResourceNotFoundException.class,
        () -> service.getRegistrationToken(EnvironmentId.of(ENV_ID)));
  }

  /** 测试意图：在线轮换必须被允许——不再查询/拒绝活跃 lease，既有连接不被主动断开；轮换只改变 token 并推进版本。 */
  @Test
  void rotateTokenAllowedWhileLeaseIsActive() {
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
    // 数据库中存在活跃租约：轮换成功且不得查询租约（在线轮换不要求离线）
    when(registry.hasActiveLease(EnvironmentId.of(ENV_ID))).thenReturn(true);

    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(repo, registry, jdbc, snapshot, CLOCK);

    EnvironmentCardDTO rotated = service.rotateToken(EnvironmentId.of(ENV_ID), "0");

    assertNotNull(rotated.getRegistrationToken());
    assertNotEquals("old-token", rotated.getRegistrationToken());
    verify(registry, never()).hasActiveLease(any());
    verify(registry, never()).disconnect(any(), any(), any());
  }

  @Test
  void deleteChecksOnlineLeaseAndActiveWork() {
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
        new EnvironmentServiceImpl(repo, registry, jdbc, snapshot, CLOCK);

    // 1. Active lease in DB -> rejects
    when(registry.hasActiveLease(EnvironmentId.of(ENV_ID))).thenReturn(true);
    assertThrows(AiInUseException.class, () -> service.delete(EnvironmentId.of(ENV_ID), "0"));

    // 2. Offline, but active work in harness_work -> rejects
    when(registry.hasActiveLease(EnvironmentId.of(ENV_ID))).thenReturn(false);
    when(jdbc.queryForObject(any(String.class), eq(Integer.class), eq(ENV_ID))).thenReturn(1);
    assertThrows(AiInUseException.class, () -> service.delete(EnvironmentId.of(ENV_ID), "0"));

    // 3. All clear -> succeeds
    when(jdbc.queryForObject(any(String.class), eq(Integer.class), eq(ENV_ID))).thenReturn(0);
    service.delete(EnvironmentId.of(ENV_ID), "0");
    verify(repo).deleteById(ENV_ID, 0L);
  }
}
