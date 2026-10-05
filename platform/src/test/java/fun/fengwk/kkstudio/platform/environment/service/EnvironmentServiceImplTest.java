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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.catalog.definition.repo.AgentDefinitionRepository;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentEvent;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.error.AiDuplicateException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.error.AiResourceNotFoundException;
import fun.fengwk.kkstudio.platform.error.AiValidationException;
import fun.fengwk.kkstudio.platform.error.AiVersionConflictException;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.share.ai.environment.DaemonConfiguration;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCardDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentEventDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallCodeDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentInstallConfigDTO;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentRegistrationTokenDTO;

import java.time.Clock;
import java.time.Duration;
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
            List.of(),
            List.of(),
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
            List.of(),
            List.of(),
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

  /** 测试意图：同一 Environment 身份的安装命令在轮换前后仍按 id 读取，但渲染当前 token，且不额外写库。 */
  @Test
  void installationScriptUsesCurrentTokenForStableEnvironmentId() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentServiceImpl service = service(repo);
    Environment env = environment("secret-token", 4L);
    DaemonConfiguration daemon = new DaemonConfiguration();
    daemon.setStudioUrl("https://studio.example.com");
    EnvironmentInstallConfigDTO config = new EnvironmentInstallConfigDTO();
    config.setOperatingSystem("linux");
    config.setDaemon(daemon);
    env.setInstallConfig(config);
    when(repo.getById(ENV_ID)).thenReturn(env);

    assertThrows(
        AiVersionConflictException.class,
        () -> service.issueInstallCode(EnvironmentId.of(ENV_ID), "3"));
    EnvironmentInstallCodeDTO issued = service.issueInstallCode(EnvironmentId.of(ENV_ID), "4");
    String before = service.installationScript(EnvironmentId.of(ENV_ID), issued.getCode());
    env.setRegistrationToken("rotated-token");
    env.setVersion(5L);
    assertThrows(
        AiValidationException.class,
        () -> service.installationScript(EnvironmentId.of(ENV_ID), issued.getCode()));
    String after =
        service.installationScript(
            EnvironmentId.of(ENV_ID),
            service.issueInstallCode(EnvironmentId.of(ENV_ID), "5").getCode());

    assertTrue(before.contains("secret-token"));
    assertFalse(before.contains("rotated-token"));
    assertTrue(after.contains("rotated-token"));
    assertFalse(after.contains("secret-token"));
    assertEquals(ENV_ID, env.getId());
    assertEquals(NOW.plus(Duration.ofMinutes(5)), issued.getExpiresAt());
    verify(repo, never()).updateById(any(), anyLong());
    verify(repo, never()).getByRegistrationToken(any());
    assertThrows(
        AiValidationException.class,
        () -> service.installationScript(EnvironmentId.of(ENV_ID), null));
    assertThrows(
        AiValidationException.class,
        () -> service.issueInstallCode(EnvironmentId.of(ENV_ID), null));
    assertThrows(
        AiValidationException.class, () -> service.issueInstallCode(EnvironmentId.of(ENV_ID), " "));
    String tampered = issued.getCode().substring(0, issued.getCode().length() - 1) + "A";
    assertThrows(
        AiValidationException.class,
        () -> service.installationScript(EnvironmentId.of(ENV_ID), tampered));
  }

  /** 测试意图：未知身份或缺少已保存安装设置都不能返回可执行的成功空脚本。 */
  @Test
  void installationScriptRejectsUnknownIdAndMissingConfig() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentServiceImpl service = service(repo);
    when(repo.getById(ENV_ID)).thenReturn(null);
    assertThrows(
        AiResourceNotFoundException.class,
        () -> service.installationScript(EnvironmentId.of(ENV_ID), "1.bad"));

    Environment env = environment("saved", 1L);
    when(repo.getById(ENV_ID)).thenReturn(env);
    assertThrows(
        AiResourceNotFoundException.class,
        () -> service.installationScript(EnvironmentId.of(ENV_ID), "1.bad"));
    verify(repo, never()).updateById(any(), anyLong());
  }

  /** 测试意图：卸载命令只校验操作系统，不查询仓库。 */
  @Test
  void uninstallationScriptDoesNotTouchRepository() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentServiceImpl service = service(repo);

    assertTrue(service.uninstallationScript("macos").contains("uninstall"));
    assertThrows(AiValidationException.class, () -> service.uninstallationScript("unknown"));
    verify(repo, never()).getByRegistrationToken(any());
  }

  private static EnvironmentServiceImpl service(EnvironmentRepository repo) {
    return new EnvironmentServiceImpl(
        repo,
        mock(EnvironmentRegistry.class),
        mock(JdbcTemplate.class),
        mock(SystemSettingsSnapshot.class),
        CLOCK);
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

  /** 测试意图：Card 只把最近一条 WARN/ERROR 事件作为 lastEvent 暴露；只有 INFO 事件时为 null。 */
  @Test
  void cardExposesOnlyLatestAlertEvent() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettings.DEFAULT);

    Environment env = new Environment();
    env.setId(ENV_ID);
    env.setName("local-env");
    env.setVersion(0L);
    when(repo.getById(ENV_ID)).thenReturn(env);

    EnvironmentEvent syncFailed =
        new EnvironmentEvent(
            NOW,
            EnvironmentEvent.LEVEL_ERROR,
            EnvironmentEvent.TYPE_SKILL_SYNC_FAILED,
            "skill package sync failed: dev");
    when(registry.find(EnvironmentId.of(ENV_ID)))
        .thenReturn(Optional.of(connection(List.of(syncFailed))));

    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(repo, registry, jdbc, snapshot, CLOCK);

    EnvironmentCardDTO card = service.get(EnvironmentId.of(ENV_ID));
    EnvironmentEventDTO lastEvent = card.getLastEvent();
    assertNotNull(lastEvent);
    assertEquals("SKILL_SYNC_FAILED", lastEvent.getType());
    assertEquals("ERROR", lastEvent.getLevel());
    assertEquals("skill package sync failed: dev", lastEvent.getMessage());

    when(registry.find(EnvironmentId.of(ENV_ID)))
        .thenReturn(
            Optional.of(
                connection(
                    List.of(
                        new EnvironmentEvent(
                            NOW,
                            EnvironmentEvent.LEVEL_INFO,
                            EnvironmentEvent.TYPE_READY,
                            "environment ready")))));
    assertNull(service.get(EnvironmentId.of(ENV_ID)).getLastEvent());
  }

  /** 测试意图：事件端点按时间正序返回同一连接行的保留事件窗口，未知 Environment 抛 404 语义错误。 */
  @Test
  void listEventsReturnsChronologicalWindow() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettings.DEFAULT);

    Environment env = new Environment();
    env.setId(ENV_ID);
    env.setName("local-env");
    env.setVersion(0L);
    when(repo.getById(ENV_ID)).thenReturn(env);

    when(registry.find(EnvironmentId.of(ENV_ID)))
        .thenReturn(
            Optional.of(
                connection(
                    List.of(
                        new EnvironmentEvent(
                            NOW,
                            EnvironmentEvent.LEVEL_INFO,
                            EnvironmentEvent.TYPE_CONNECTING,
                            "daemon connection accepted"),
                        new EnvironmentEvent(
                            NOW.plusSeconds(1),
                            EnvironmentEvent.LEVEL_WARN,
                            EnvironmentEvent.TYPE_DISCONNECTED,
                            "daemon connection lost")))));

    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(repo, registry, jdbc, snapshot, CLOCK);

    List<EnvironmentEventDTO> events = service.listEvents(EnvironmentId.of(ENV_ID));
    assertEquals(2, events.size());
    assertEquals("CONNECTING", events.get(0).getType());
    assertEquals("DISCONNECTED", events.get(1).getType());
    assertEquals(NOW, events.get(0).getTime());

    // 从未连接过（无连接行）的 Environment 事件窗口为空，而不是错误。
    when(registry.find(EnvironmentId.of(ENV_ID))).thenReturn(Optional.empty());
    assertEquals(List.of(), service.listEvents(EnvironmentId.of(ENV_ID)));

    // 未知 Environment 与详情查询一样是 404 语义错误。
    when(repo.getById(ENV_ID)).thenReturn(null);
    assertThrows(
        AiResourceNotFoundException.class, () -> service.listEvents(EnvironmentId.of(ENV_ID)));
  }

  /** 意图：导入新 Environment 使用新 UUID 并保留 token，仅在新建响应中暴露 token。 */
  @Test
  void importEnvironmentCreatesNewUuidAndExposesTokenOnce() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    when(repo.existsByName("imported")).thenReturn(false);
    when(repo.getByRegistrationToken("token-1")).thenReturn(null);
    when(repo.create(any())).thenReturn(true);
    when(repo.getById(any()))
        .thenAnswer(
            invocation -> {
              Environment env = new Environment();
              env.setId(invocation.getArgument(0));
              env.setName("imported");
              env.setRegistrationToken("token-1");
              env.setVersion(0L);
              env.setCreateTime(NOW);
              env.setUpdateTime(NOW);
              return env;
            });
    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(
            repo,
            mock(EnvironmentRegistry.class),
            mock(JdbcTemplate.class),
            mock(SystemSettingsSnapshot.class),
            CLOCK);

    EnvironmentCardDTO card = service.importEnvironment("imported", "token-1", null);

    assertEquals("imported", card.getName());
    assertEquals("token-1", card.getRegistrationToken());
    assertEquals("0", card.getVersion());
  }

  /** 意图：同名的 Environment 导入必须拒绝（name 即身份）。 */
  @Test
  void importEnvironmentRejectsExistingName() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    when(repo.existsByName("dup")).thenReturn(true);
    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(
            repo,
            mock(EnvironmentRegistry.class),
            mock(JdbcTemplate.class),
            mock(SystemSettingsSnapshot.class),
            CLOCK);

    assertThrows(AiDuplicateException.class, () -> service.importEnvironment("dup", "token", null));
  }

  /** 意图：registrationToken 已被占用时拒绝，且错误文本不回显 token 值。 */
  @Test
  void importEnvironmentRejectsTokenConflictWithoutLeakingToken() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    when(repo.existsByName("fresh")).thenReturn(false);
    when(repo.getByRegistrationToken("secret-token")).thenReturn(new Environment());
    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(
            repo,
            mock(EnvironmentRegistry.class),
            mock(JdbcTemplate.class),
            mock(SystemSettingsSnapshot.class),
            CLOCK);

    AiDuplicateException error =
        assertThrows(
            AiDuplicateException.class,
            () -> service.importEnvironment("fresh", "secret-token", null));

    assertFalse(error.getMessage().contains("secret-token"));
  }

  /** 意图：同名 Environment 更新 token 用 lockById + CAS；过期预期版本冲突，不改写既有行。 */
  @Test
  void updateImportedEnvironmentUsesCasAndConflictsOnStaleVersion() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    Environment env = new Environment();
    env.setId(ENV_ID);
    env.setName("local-env");
    env.setRegistrationToken("old-token");
    env.setVersion(0L);
    env.setCreateTime(NOW);
    env.setUpdateTime(NOW);
    when(repo.lockById(ENV_ID)).thenReturn(env);
    when(repo.updateById(any(), eq(0L))).thenReturn(true);
    when(repo.getById(ENV_ID))
        .thenAnswer(
            invocation -> {
              Environment updated = new Environment();
              updated.setId(ENV_ID);
              updated.setName("local-env");
              updated.setRegistrationToken("new-token");
              updated.setVersion(1L);
              updated.setCreateTime(NOW);
              updated.setUpdateTime(NOW);
              return updated;
            });
    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(
            repo,
            mock(EnvironmentRegistry.class),
            mock(JdbcTemplate.class),
            mock(SystemSettingsSnapshot.class),
            CLOCK);

    EnvironmentCardDTO card =
        service.updateImportedEnvironment(EnvironmentId.of(ENV_ID), "new-token", null, "0");
    assertEquals("new-token", card.getRegistrationToken());
    assertEquals("1", card.getVersion());

    // 过期版本：既有行版本 2，预期 0，必须冲突。
    Environment stale = new Environment();
    stale.setId(ENV_ID);
    stale.setName("local-env");
    stale.setRegistrationToken("newer-token");
    stale.setVersion(2L);
    when(repo.lockById(ENV_ID)).thenReturn(stale);
    assertThrows(
        AiVersionConflictException.class,
        () ->
            service.updateImportedEnvironment(EnvironmentId.of(ENV_ID), "other-token", null, "0"));
  }

  /** 意图：导入 create 返回 false 时冒泡，不留下半成品。 */
  @Test
  void importEnvironmentPropagatesCreateFailure() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    when(repo.existsByName("imported")).thenReturn(false);
    when(repo.getByRegistrationToken("token-1")).thenReturn(null);
    when(repo.create(any())).thenReturn(false);
    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(
            repo,
            mock(EnvironmentRegistry.class),
            mock(JdbcTemplate.class),
            mock(SystemSettingsSnapshot.class),
            CLOCK);

    assertThrows(
        IllegalStateException.class, () -> service.importEnvironment("imported", "token-1", null));
  }

  /** 意图：并发插入触发唯一约束竞争时映射为业务重复错误。 */
  @Test
  void importEnvironmentMapsDuplicateKeyToDuplicate() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    when(repo.existsByName("imported")).thenReturn(false);
    when(repo.getByRegistrationToken("token-1")).thenReturn(null);
    when(repo.create(any())).thenThrow(new DuplicateKeyException("dup"));
    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(
            repo,
            mock(EnvironmentRegistry.class),
            mock(JdbcTemplate.class),
            mock(SystemSettingsSnapshot.class),
            CLOCK);

    assertThrows(
        AiDuplicateException.class, () -> service.importEnvironment("imported", "token-1", null));
  }

  /** 意图：目标行不存在时 lockById 返回 null 必须 404。 */
  @Test
  void updateImportedEnvironmentRejectsMissingEnvironment() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    when(repo.lockById(ENV_ID)).thenReturn(null);
    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(
            repo,
            mock(EnvironmentRegistry.class),
            mock(JdbcTemplate.class),
            mock(SystemSettingsSnapshot.class),
            CLOCK);

    assertThrows(
        AiResourceNotFoundException.class,
        () -> service.updateImportedEnvironment(EnvironmentId.of(ENV_ID), "new-token", null, "0"));
  }

  /** 意图：token 与当前值一致时幂等成功返回，且不写库。 */
  @Test
  void updateImportedEnvironmentIsIdempotentWhenUnchanged() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    when(repo.lockById(ENV_ID)).thenReturn(environment("old-token", 0L));
    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(
            repo,
            mock(EnvironmentRegistry.class),
            mock(JdbcTemplate.class),
            mock(SystemSettingsSnapshot.class),
            CLOCK);

    EnvironmentCardDTO card =
        service.updateImportedEnvironment(EnvironmentId.of(ENV_ID), "old-token", null, "0");

    assertEquals("old-token", card.getRegistrationToken());
    verify(repo, never()).updateById(any(), anyLong());
  }

  /** 意图：CAS 失败且重读发现行已消失时按 404 语义失败。 */
  @Test
  void updateImportedEnvironmentCasLossWithMissingRowThrowsNotFound() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    Environment env = environment("old-token", 0L);
    when(repo.lockById(ENV_ID)).thenReturn(env);
    when(repo.updateById(env, 0L)).thenReturn(false);
    when(repo.getById(ENV_ID)).thenReturn(null);
    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(
            repo,
            mock(EnvironmentRegistry.class),
            mock(JdbcTemplate.class),
            mock(SystemSettingsSnapshot.class),
            CLOCK);

    assertThrows(
        AiResourceNotFoundException.class,
        () -> service.updateImportedEnvironment(EnvironmentId.of(ENV_ID), "new-token", null, "0"));
  }

  /** 意图：CAS 失败但行仍在（版本已前进）时按版本冲突失败。 */
  @Test
  void updateImportedEnvironmentCasLossWithNewerVersionThrowsConflict() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    Environment env = environment("old-token", 0L);
    when(repo.lockById(ENV_ID)).thenReturn(env);
    when(repo.updateById(env, 0L)).thenReturn(false);
    when(repo.getById(ENV_ID)).thenReturn(environment("newer-token", 2L));
    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(
            repo,
            mock(EnvironmentRegistry.class),
            mock(JdbcTemplate.class),
            mock(SystemSettingsSnapshot.class),
            CLOCK);

    assertThrows(
        AiVersionConflictException.class,
        () -> service.updateImportedEnvironment(EnvironmentId.of(ENV_ID), "new-token", null, "0"));
  }

  /** 意图：唯一 token 竞争触发约束异常时映射为业务重复错误。 */
  @Test
  void updateImportedEnvironmentMapsDuplicateKeyToDuplicate() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    Environment env = environment("old-token", 0L);
    when(repo.lockById(ENV_ID)).thenReturn(env);
    when(repo.updateById(env, 0L)).thenThrow(new DuplicateKeyException("dup"));
    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(
            repo,
            mock(EnvironmentRegistry.class),
            mock(JdbcTemplate.class),
            mock(SystemSettingsSnapshot.class),
            CLOCK);

    assertThrows(
        AiDuplicateException.class,
        () -> service.updateImportedEnvironment(EnvironmentId.of(ENV_ID), "new-token", null, "0"));
  }

  /** 意图：CRUD 与配置同步共用的静态校验覆盖全部拒绝分支。 */
  @Test
  void staticValidatorsRejectEveryInvalidShape() {
    for (String invalid : new String[] {null, "", "   ", " x", "x ", "a/b", "x".repeat(65)}) {
      assertThrows(AiValidationException.class, () -> EnvironmentServiceImpl.validateName(invalid));
    }
    assertEquals("ok", EnvironmentServiceImpl.validateName("ok"));
    for (String invalid : new String[] {null, "", "   ", " t", "t ", "x".repeat(129)}) {
      assertThrows(
          AiValidationException.class,
          () -> EnvironmentServiceImpl.validateRegistrationToken(invalid));
    }
    assertEquals("t", EnvironmentServiceImpl.validateRegistrationToken("t"));
  }

  /** 意图：create 拒绝 null 请求与重名，并把并发唯一约束竞争映射为重复错误。 */
  @Test
  void createValidatesRequestAndMapsDuplicateConstraints() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettings.DEFAULT);
    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(
            repo, mock(EnvironmentRegistry.class), mock(JdbcTemplate.class), snapshot, CLOCK);

    assertThrows(AiValidationException.class, () -> service.create(null));

    when(repo.existsByName("dup")).thenReturn(true);
    EnvironmentCreateDTO duplicate = new EnvironmentCreateDTO();
    duplicate.setName("dup");
    assertThrows(AiDuplicateException.class, () -> service.create(duplicate));

    when(repo.existsByName("fresh")).thenReturn(false);
    when(repo.create(any())).thenThrow(new DuplicateKeyException("dup"));
    EnvironmentCreateDTO fresh = new EnvironmentCreateDTO();
    fresh.setName("fresh");
    assertThrows(AiDuplicateException.class, () -> service.create(fresh));
  }

  /** 意图：详情查询未知 id 必须 404。 */
  @Test
  void getUnknownEnvironmentThrowsNotFound() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(
            repo,
            mock(EnvironmentRegistry.class),
            mock(JdbcTemplate.class),
            mock(SystemSettingsSnapshot.class),
            CLOCK);

    assertThrows(AiResourceNotFoundException.class, () -> service.get(EnvironmentId.of(ENV_ID)));
  }

  /** 意图：rotateToken 覆盖缺失行、过期版本与 CAS 丢失（行消失/版本前进）三条失败路径。 */
  @Test
  void rotateTokenFailurePaths() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(
            repo,
            mock(EnvironmentRegistry.class),
            mock(JdbcTemplate.class),
            mock(SystemSettingsSnapshot.class),
            CLOCK);

    assertThrows(
        AiResourceNotFoundException.class,
        () -> service.rotateToken(EnvironmentId.of(ENV_ID), "0"));

    when(repo.lockById(ENV_ID)).thenReturn(environment("old", 2L));
    assertThrows(
        AiVersionConflictException.class, () -> service.rotateToken(EnvironmentId.of(ENV_ID), "0"));

    when(repo.lockById(ENV_ID)).thenReturn(environment("old", 0L));
    when(repo.updateById(any(), eq(0L))).thenReturn(false);
    when(repo.getById(ENV_ID)).thenReturn(null);
    assertThrows(
        AiResourceNotFoundException.class,
        () -> service.rotateToken(EnvironmentId.of(ENV_ID), "0"));

    when(repo.getById(ENV_ID)).thenReturn(environment("newer", 3L));
    assertThrows(
        AiVersionConflictException.class, () -> service.rotateToken(EnvironmentId.of(ENV_ID), "0"));
  }

  /** 意图：delete 覆盖缺失行、过期版本、CAS 丢失与引用完整性冲突的拒绝路径。 */
  @Test
  void deleteFailurePaths() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    EnvironmentServiceImpl service =
        new EnvironmentServiceImpl(repo, registry, jdbc, mock(SystemSettingsSnapshot.class), CLOCK);

    assertThrows(
        AiResourceNotFoundException.class, () -> service.delete(EnvironmentId.of(ENV_ID), "0"));

    when(repo.lockById(ENV_ID)).thenReturn(environment("token", 2L));
    assertThrows(
        AiVersionConflictException.class, () -> service.delete(EnvironmentId.of(ENV_ID), "0"));

    when(repo.lockById(ENV_ID)).thenReturn(environment("token", 0L));
    when(registry.hasActiveLease(EnvironmentId.of(ENV_ID))).thenReturn(false);
    when(jdbc.queryForObject(any(String.class), eq(Integer.class), eq(ENV_ID))).thenReturn(0);

    when(repo.deleteById(ENV_ID, 0L)).thenReturn(false);
    when(repo.getById(ENV_ID)).thenReturn(null);
    assertThrows(
        AiResourceNotFoundException.class, () -> service.delete(EnvironmentId.of(ENV_ID), "0"));

    when(repo.getById(ENV_ID)).thenReturn(environment("token", 5L));
    assertThrows(
        AiVersionConflictException.class, () -> service.delete(EnvironmentId.of(ENV_ID), "0"));

    when(repo.deleteById(ENV_ID, 0L)).thenThrow(new DataIntegrityViolationException("ref"));
    assertThrows(AiInUseException.class, () -> service.delete(EnvironmentId.of(ENV_ID), "0"));
  }

  private static Environment environment(String registrationToken, long version) {
    Environment env = new Environment();
    env.setId(ENV_ID);
    env.setName("local-env");
    env.setRegistrationToken(registrationToken);
    env.setVersion(version);
    env.setCreateTime(NOW);
    env.setUpdateTime(NOW);
    return env;
  }

  private static EnvironmentConnection connection(List<EnvironmentEvent> events) {
    return new EnvironmentConnection(
        EnvironmentId.of(ENV_ID),
        UUID.randomUUID(),
        UUID.randomUUID(),
        LiveEnvironmentStatus.READY,
        new DaemonCapabilities(
            DaemonCapabilities.VERSION,
            new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX, "Asia/Shanghai", "dev", "/home/dev", "Note")),
        List.of(),
        events,
        NOW,
        NOW.plusSeconds(60));
  }
}
