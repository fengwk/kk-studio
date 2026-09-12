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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillDescriptor;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonSkillSourceSnapshot;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.environment.skill.EnvironmentSkillSourceInitializer;
import fun.fengwk.kkstudio.platform.environment.skill.model.EnvironmentInventory;
import fun.fengwk.kkstudio.platform.environment.skill.model.SkillInventoryEntry;
import fun.fengwk.kkstudio.platform.environment.skill.repo.SkillSourceRepository;
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
  private static final UUID SKILL_SOURCE_ID =
      UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final String CONTENT_REVISION =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  /** 冻结的六字段 skill descriptor fixture。 */
  private static DaemonSkillDescriptor skillDescriptor(String name, String description) {
    return new DaemonSkillDescriptor(
        SKILL_SOURCE_ID, 1, name, description, "/home/dev/skills/" + name, CONTENT_REVISION);
  }

  /** 构造一条当前可用的持久 inventory fixture（来源 READY、applied 与行版本都等于当前配置版本）。 */
  private static SkillInventoryEntry usableSkill(String name) {
    SkillInventoryEntry entry = new SkillInventoryEntry();
    entry.setEnvironmentId(ENV_ID);
    entry.setSourceId(SKILL_SOURCE_ID);
    entry.setName(name);
    entry.setSourceVersion(1L);
    entry.setDescription(name + " skill");
    entry.setBaseDirectory("/home/dev/skills/" + name);
    entry.setContentRevision(CONTENT_REVISION);
    entry.setDiscoveredAt(NOW);
    return entry;
  }

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

  /** 测试意图：Environment 创建必须在同一事务内委托初始化器补齐 inventory 与缺省 PATH 来源。 */
  @Test
  void createInitializesInventoryAndDefaultSourceInSameTransaction() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettings.DEFAULT);
    EnvironmentSkillSourceInitializer initializer = mock(EnvironmentSkillSourceInitializer.class);
    SkillSourceRepository skillSources = mock(SkillSourceRepository.class);

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
    when(skillSources.listUsableSkills(any())).thenReturn(List.of());

    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(
            repo, registry, agents, jdbc, snapshot, CLOCK, initializer, skillSources);

    EnvironmentCreateDTO create = new EnvironmentCreateDTO();
    create.setName("local-env");
    service.create(create);

    ArgumentCaptor<UUID> environmentIdCaptor = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<UUID> sourceIdCaptor = ArgumentCaptor.forClass(UUID.class);
    verify(initializer).initialize(environmentIdCaptor.capture(), sourceIdCaptor.capture());
    assertNotNull(environmentIdCaptor.getValue());
    assertNotNull(sourceIdCaptor.getValue());
  }

  /** 测试意图：初始化器失败必须向上冒泡，使 Environment 创建整体回滚而不是留下半成品。 */
  @Test
  void createPropagatesInitializerFailure() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettings.DEFAULT);
    EnvironmentSkillSourceInitializer initializer = mock(EnvironmentSkillSourceInitializer.class);
    SkillSourceRepository skillSources = mock(SkillSourceRepository.class);

    when(repo.existsByName("local-env")).thenReturn(false);
    when(repo.create(any())).thenReturn(true);
    when(repo.getById(any()))
        .thenAnswer(
            invocation -> {
              Environment env = new Environment();
              env.setId(invocation.getArgument(0));
              env.setName("local-env");
              env.setRegistrationToken("token");
              env.setVersion(0L);
              return env;
            });
    doThrow(new IllegalStateException("create environment inventory failed"))
        .when(initializer)
        .initialize(any(), any());

    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(
            repo, registry, agents, jdbc, snapshot, CLOCK, initializer, skillSources);

    EnvironmentCreateDTO create = new EnvironmentCreateDTO();
    create.setName("local-env");
    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.create(create));
    assertTrue(error.getMessage().contains("inventory"), error.getMessage());
  }

  @Test
  void createExposesRegistrationTokenOnce() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettings.DEFAULT);
    EnvironmentSkillSourceInitializer initializer = mock(EnvironmentSkillSourceInitializer.class);
    SkillSourceRepository skillSources = mock(SkillSourceRepository.class);
    when(skillSources.listUsableSkills(any())).thenReturn(List.of());

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
        new EnvironmentServiceImpl(
            repo, registry, agents, jdbc, snapshot, CLOCK, initializer, skillSources);

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
    EnvironmentSkillSourceInitializer initializer = mock(EnvironmentSkillSourceInitializer.class);
    SkillSourceRepository skillSources = mock(SkillSourceRepository.class);
    when(skillSources.listUsableSkills(any())).thenReturn(List.of());

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
                1,
                List.of(
                    new DaemonSkillSourceSnapshot(
                        SKILL_SOURCE_ID,
                        1,
                        CONTENT_REVISION,
                        List.of(skillDescriptor("dev", "dev skill")),
                        List.of()))),
            NOW,
            NOW.plusSeconds(60));

    when(registry.find(EnvironmentId.of(ENV_ID))).thenReturn(Optional.of(conn));
    when(skillSources.listUsableSkills(ENV_ID)).thenReturn(List.of(usableSkill("dev")));

    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(
            repo, registry, agents, jdbc, snapshot, CLOCK, initializer, skillSources);

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

  /** 测试意图：Environment 离线时卡片仍投影持久 inventory 的可用 Skill 与最近一次被接受报告的 root，而不是清空成空列表/空路径。 */
  @Test
  void offlineCardProjectsDurableInventory() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettings.DEFAULT);
    EnvironmentSkillSourceInitializer initializer = mock(EnvironmentSkillSourceInitializer.class);
    SkillSourceRepository skillSources = mock(SkillSourceRepository.class);

    Environment env = new Environment();
    env.setId(ENV_ID);
    env.setName("local-env");
    env.setRegistrationToken("secret-token");
    env.setVersion(0L);
    env.setCreateTime(NOW);
    env.setUpdateTime(NOW);
    when(repo.getById(ENV_ID)).thenReturn(env);

    EnvironmentInventory inventory = new EnvironmentInventory();
    inventory.setEnvironmentId(ENV_ID);
    inventory.setSourceSetVersion(2L);
    inventory.setAppliedSourceSetVersion(2L);
    inventory.setRootPath("/home/dev");
    when(skillSources.getInventory(ENV_ID)).thenReturn(inventory);
    when(skillSources.listUsableSkills(ENV_ID)).thenReturn(List.of(usableSkill("dev")));
    // 没有任何连接行：Environment 离线。
    when(registry.find(EnvironmentId.of(ENV_ID))).thenReturn(Optional.empty());

    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(
            repo, registry, agents, jdbc, snapshot, CLOCK, initializer, skillSources);

    EnvironmentCardDTO card = service.get(EnvironmentId.of(ENV_ID));

    assertEquals("OFFLINE", card.getStatus());
    assertFalse(card.isReady());
    assertEquals("/home/dev", card.getRootPath(), "离线仍展示最近一次报告的宿主 root");
    assertEquals(1, card.getSkills().size(), "离线仍展示持久 inventory 的可用 Skill");
    assertEquals("dev", card.getSkills().get(0).getName());
    assertTrue(card.getCapabilities().isEmpty());
  }

  /** 测试意图：在线但未 READY 时 Skill 投影仍来自持久 inventory（连接状态与 Skill 事实解耦），root 走持久报告而不是空值。 */
  @Test
  void connectingConnectionStillProjectsDurableInventory() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettings.DEFAULT);
    EnvironmentSkillSourceInitializer initializer = mock(EnvironmentSkillSourceInitializer.class);
    SkillSourceRepository skillSources = mock(SkillSourceRepository.class);

    Environment env = new Environment();
    env.setId(ENV_ID);
    env.setName("local-env");
    env.setRegistrationToken("secret-token");
    env.setVersion(0L);
    env.setCreateTime(NOW);
    env.setUpdateTime(NOW);
    when(repo.getById(ENV_ID)).thenReturn(env);

    EnvironmentInventory inventory = new EnvironmentInventory();
    inventory.setEnvironmentId(ENV_ID);
    inventory.setSourceSetVersion(1L);
    inventory.setAppliedSourceSetVersion(1L);
    inventory.setRootPath("/home/dev");
    when(skillSources.getInventory(ENV_ID)).thenReturn(inventory);
    when(skillSources.listUsableSkills(ENV_ID)).thenReturn(List.of(usableSkill("dev")));

    EnvironmentConnection conn =
        new EnvironmentConnection(
            EnvironmentId.of(ENV_ID),
            UUID.randomUUID(),
            UUID.randomUUID(),
            LiveEnvironmentStatus.CONNECTING,
            null,
            NOW,
            NOW.plusSeconds(60));
    when(registry.find(EnvironmentId.of(ENV_ID))).thenReturn(Optional.of(conn));

    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(
            repo, registry, agents, jdbc, snapshot, CLOCK, initializer, skillSources);

    EnvironmentCardDTO card = service.get(EnvironmentId.of(ENV_ID));

    assertEquals("CONNECTING", card.getStatus());
    assertFalse(card.isReady());
    assertEquals("/home/dev", card.getRootPath());
    assertEquals(1, card.getSkills().size());
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
    EnvironmentSkillSourceInitializer initializer = mock(EnvironmentSkillSourceInitializer.class);
    SkillSourceRepository skillSources = mock(SkillSourceRepository.class);
    when(skillSources.listUsableSkills(any())).thenReturn(List.of());

    Environment env = new Environment();
    env.setId(ENV_ID);
    env.setName("local-env");
    env.setRegistrationToken("stable-token");
    env.setVersion(3L);
    env.setCreateTime(NOW);
    env.setUpdateTime(NOW);
    when(repo.getById(ENV_ID)).thenReturn(env);

    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(
            repo, registry, agents, jdbc, snapshot, CLOCK, initializer, skillSources);

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
    EnvironmentSkillSourceInitializer initializer = mock(EnvironmentSkillSourceInitializer.class);
    SkillSourceRepository skillSources = mock(SkillSourceRepository.class);
    when(skillSources.listUsableSkills(any())).thenReturn(List.of());
    when(repo.getById(ENV_ID)).thenReturn(null);

    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(
            repo, registry, agents, jdbc, snapshot, CLOCK, initializer, skillSources);

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
    EnvironmentSkillSourceInitializer initializer = mock(EnvironmentSkillSourceInitializer.class);
    SkillSourceRepository skillSources = mock(SkillSourceRepository.class);
    when(skillSources.listUsableSkills(any())).thenReturn(List.of());

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
        new EnvironmentServiceImpl(
            repo, registry, agents, jdbc, snapshot, CLOCK, initializer, skillSources);

    EnvironmentCardDTO rotated = service.rotateToken(EnvironmentId.of(ENV_ID), "0");

    assertNotNull(rotated.getRegistrationToken());
    assertNotEquals("old-token", rotated.getRegistrationToken());
    verify(registry, never()).hasActiveLease(any());
    verify(registry, never()).disconnect(any(), any(), any());
  }

  @Test
  void deleteChecksOnlineAgentRefsAndActiveWork() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    AgentDefinitionRepository agents = mock(AgentDefinitionRepository.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettings.DEFAULT);
    EnvironmentSkillSourceInitializer initializer = mock(EnvironmentSkillSourceInitializer.class);
    SkillSourceRepository skillSources = mock(SkillSourceRepository.class);
    when(skillSources.listUsableSkills(any())).thenReturn(List.of());

    Environment env = new Environment();
    env.setId(ENV_ID);
    env.setName("local-env");
    env.setRegistrationToken("token");
    env.setVersion(0L);

    when(repo.lockById(ENV_ID)).thenReturn(env);
    when(repo.deleteById(ENV_ID, 0L)).thenReturn(true);

    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(
            repo, registry, agents, jdbc, snapshot, CLOCK, initializer, skillSources);

    // 1. Active lease in DB -> rejects
    when(registry.hasActiveLease(EnvironmentId.of(ENV_ID))).thenReturn(true);
    assertThrows(AiInUseException.class, () -> service.delete(EnvironmentId.of(ENV_ID), "0"));

    // 2. No active lease but referenced by Agent -> rejects
    when(registry.hasActiveLease(EnvironmentId.of(ENV_ID))).thenReturn(false);
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
