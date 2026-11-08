package fun.fengwk.kkstudio.platform.environment;

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
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.common.error.DomainException;
import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.platform.catalog.definition.AgentRepository;
import fun.fengwk.kkstudio.platform.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.platform.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.platform.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.platform.environment.dto.DaemonConfigDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentCreateDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentEventDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentEventLevel;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentEventType;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentInstallCodeDTO;
import fun.fengwk.kkstudio.platform.environment.dto.EnvironmentStatus;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentEvent;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsTestFixture;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class EnvironmentServiceTest {

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

  /** 测试意图：Environment 创建只写入 Environment 行，创建时不存在任何已被接受的 READY 宿主 metadata。 */
  @Test
  void createLeavesNoAcceptedHostMetadata() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    AgentRepository agents = mock(AgentRepository.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettingsTestFixture.DEFAULT);

    when(repo.existsByName("local-env")).thenReturn(false);
    when(repo.create(any())).thenReturn(true);
    when(repo.getById(any()))
        .thenAnswer(
            invocation -> {
              Environment env = new Environment();
              env.setId(invocation.getArgument(0));
              env.setName("local-env");
              env.setRegistrationTokenHash(h("secret-token"));
              env.setVersion(0L);
              env.setCreatedAt(NOW);
              env.setUpdatedAt(NOW);
              return env;
            });
    when(registry.find(any())).thenReturn(Optional.empty());

    EnvironmentService service = new EnvironmentService(repo, registry, snapshot, CLOCK);

    EnvironmentCreateDTO create = new EnvironmentCreateDTO();
    create.setName("local-env");
    create.setDaemonConfig(daemonConfig());
    EnvironmentDTO card = service.create(create);

    assertNotNull(card.getId());
    assertEquals(EnvironmentStatus.OFFLINE, card.getStatus());
    assertNull(card.getOperatingSystem());
    assertNull(card.getTimeZone());
    assertNull(card.getNote());
    assertNull(card.getUserName());
    assertNull(card.getHomeDirectory());
  }

  /** 测试意图：Environment 行创建失败必须向上冒泡，绝不留下半成品。 */
  @Test
  void createPropagatesEnvironmentInsertFailure() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    AgentRepository agents = mock(AgentRepository.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettingsTestFixture.DEFAULT);

    when(repo.existsByName("local-env")).thenReturn(false);
    when(repo.create(any())).thenReturn(false);

    EnvironmentService service = new EnvironmentService(repo, registry, snapshot, CLOCK);

    EnvironmentCreateDTO create = new EnvironmentCreateDTO();
    create.setName("local-env");
    create.setDaemonConfig(daemonConfig());
    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> service.create(create));
    assertTrue(error.getMessage().contains("create environment failed"), error.getMessage());
  }

  @Test
  void createExposesRegistrationTokenOnce() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    AgentRepository agents = mock(AgentRepository.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettingsTestFixture.DEFAULT);

    when(repo.existsByName("local-env")).thenReturn(false);
    when(repo.create(any())).thenReturn(true);
    when(repo.getById(any()))
        .thenAnswer(
            invocation -> {
              UUID id = invocation.getArgument(0);
              Environment env = new Environment();
              env.setId(id);
              env.setName("local-env");
              env.setRegistrationTokenHash(h("secret-token"));
              env.setVersion(0L);
              env.setCreatedAt(NOW);
              env.setUpdatedAt(NOW);
              return env;
            });

    EnvironmentService service = new EnvironmentService(repo, registry, snapshot, CLOCK);

    EnvironmentCreateDTO create = new EnvironmentCreateDTO();
    create.setName("local-env");
    create.setDaemonConfig(daemonConfig());

    EnvironmentDTO result = service.create(create);

    assertEquals("local-env", result.getName());
    ArgumentCaptor<Environment> created = ArgumentCaptor.forClass(Environment.class);
    verify(repo).create(created.capture());
    assertEquals(h(result.getRegistrationToken()), created.getValue().getRegistrationTokenHash());
    assertEquals(0L, result.getVersion());
  }

  @Test
  void getAndListNeverExposeRegistrationToken() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    AgentRepository agents = mock(AgentRepository.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettingsTestFixture.DEFAULT);

    Environment env = new Environment();
    env.setId(ENV_ID);
    env.setName("local-env");
    env.setNote("Card note");
    env.setRegistrationTokenHash(h("secret-token"));
    env.setDaemonConfig(daemonConfig());
    env.setVersion(0L);
    env.setCreatedAt(NOW);
    env.setUpdatedAt(NOW);

    when(repo.getById(ENV_ID)).thenReturn(env);
    when(repo.listNewestFirst()).thenReturn(List.of(env));

    EnvironmentConnection conn =
        new EnvironmentConnection(
            EnvironmentId.of(ENV_ID),
            UUID.randomUUID(),
            UUID.randomUUID(),
            LiveEnvironmentStatus.READY,
            new DaemonCapabilities(
                "1.0.9",
                new DaemonEnvironmentInfo(
                    DaemonOperatingSystem.LINUX,
                    "Asia/Shanghai",
                    "dev",
                    "/home/dev",
                    "Note",
                    "/tmp/kk-studio",
                    "amd64")),
            List.of(),
            List.of(),
            NOW,
            NOW.plusSeconds(60));

    when(registry.find(EnvironmentId.of(ENV_ID))).thenReturn(Optional.of(conn));

    EnvironmentService service = new EnvironmentService(repo, registry, snapshot, CLOCK);

    EnvironmentDTO single = service.get(EnvironmentId.of(ENV_ID));
    assertNull(single.getRegistrationToken());
    assertEquals(EnvironmentStatus.READY, single.getStatus());
    assertTrue(single.isReady());
    assertEquals(NOW.plusSeconds(60), single.getStatusExpiresAt());
    assertEquals(DaemonOperatingSystem.LINUX, single.getOperatingSystem());
    assertEquals("Asia/Shanghai", single.getTimeZone());
    assertEquals("Card note", single.getNote());
    assertEquals("dev", single.getUserName());
    assertEquals("/home/dev", single.getHomeDirectory());
    List<EnvironmentDTO> list = service.list();
    assertEquals(1, list.size());
    assertNull(list.get(0).getRegistrationToken());
    assertEquals(EnvironmentStatus.READY, list.get(0).getStatus());
  }

  /** 测试意图：从未连接过的 Environment 没有保留的宿主 metadata，Environment 除状态外全部为空。 */
  @Test
  void neverConnectedEnvironmentHasNoHostMetadata() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    AgentRepository agents = mock(AgentRepository.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettingsTestFixture.DEFAULT);

    Environment env = new Environment();
    env.setId(ENV_ID);
    env.setName("local-env");
    env.setRegistrationTokenHash(h("secret-token"));
    env.setVersion(0L);
    env.setCreatedAt(NOW);
    env.setUpdatedAt(NOW);
    when(repo.getById(ENV_ID)).thenReturn(env);
    // 没有任何连接行：Environment 从未连接，无任何已保留事实。
    when(registry.find(EnvironmentId.of(ENV_ID))).thenReturn(Optional.empty());

    EnvironmentService service = new EnvironmentService(repo, registry, snapshot, CLOCK);

    EnvironmentDTO card = service.get(EnvironmentId.of(ENV_ID));

    assertEquals(EnvironmentStatus.OFFLINE, card.getStatus());
    assertFalse(card.isReady());
    assertNull(card.getOperatingSystem());
    assertNull(card.getTimeZone());
    assertNull(card.getNote());
    assertNull(card.getUserName());
    assertNull(card.getHomeDirectory());
    assertTrue(card.getCapabilities().isEmpty());
  }

  /**
   * 测试意图：CONNECTING 连接行仍保留最近一次 READY 的宿主 metadata，Environment 直接投影该保留事实而不清空。
   *
   * <p>这是"断线或重新 CONNECTING 不清空 runtime_info"契约在产品投影侧的可观察行为。
   */
  @Test
  void connectingConnectionStillProjectsRetainedHostMetadata() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    AgentRepository agents = mock(AgentRepository.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettingsTestFixture.DEFAULT);

    Environment env = new Environment();
    env.setId(ENV_ID);
    env.setName("local-env");
    env.setNote("Card note");
    env.setRegistrationTokenHash(h("secret-token"));
    env.setDaemonConfig(daemonConfig());
    env.setVersion(0L);
    env.setCreatedAt(NOW);
    env.setUpdatedAt(NOW);
    when(repo.getById(ENV_ID)).thenReturn(env);

    EnvironmentConnection conn =
        new EnvironmentConnection(
            EnvironmentId.of(ENV_ID),
            UUID.randomUUID(),
            UUID.randomUUID(),
            LiveEnvironmentStatus.CONNECTING,
            new DaemonCapabilities(
                "1.0.9",
                new DaemonEnvironmentInfo(
                    DaemonOperatingSystem.WSL,
                    "Asia/Shanghai",
                    "dev",
                    "/home/dev",
                    "Retained note",
                    "/tmp/kk-studio",
                    "amd64")),
            List.of(),
            List.of(),
            NOW,
            NOW.plusSeconds(60));
    when(registry.find(EnvironmentId.of(ENV_ID))).thenReturn(Optional.of(conn));

    EnvironmentService service = new EnvironmentService(repo, registry, snapshot, CLOCK);

    EnvironmentDTO card = service.get(EnvironmentId.of(ENV_ID));

    assertEquals(EnvironmentStatus.CONNECTING, card.getStatus());
    assertFalse(card.isReady());
    assertEquals(DaemonOperatingSystem.WSL, card.getOperatingSystem());
    assertEquals("Asia/Shanghai", card.getTimeZone());
    assertEquals("Card note", card.getNote());
    assertEquals("dev", card.getUserName());
    assertEquals("/home/dev", card.getHomeDirectory());
  }

  /** 测试意图：兑换安装 code 由仓库原子清 code、轮换凭证并写入脚本；签发不推进版本，同一 code 无法再次兑换。 */
  @Test
  void installationScriptIssuesAFreshTokenAndConsumesTheCode() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentService service = service(repo);
    Environment env = environment("secret-token", 4L);
    env.setDaemonConfig(daemonConfig());
    when(repo.getById(ENV_ID)).thenReturn(env);
    when(repo.lockById(ENV_ID)).thenReturn(env);

    AtomicReference<String> savedCodeHash = new AtomicReference<>();
    AtomicReference<String> savedTokenHash = new AtomicReference<>();
    Instant expiresAt = NOW.plus(Duration.ofMinutes(5));
    when(repo.saveInstallCode(eq(ENV_ID), eq(4L), any()))
        .thenAnswer(
            invocation -> {
              savedCodeHash.set(invocation.getArgument(2));
              return Optional.of(expiresAt);
            });
    when(repo.redeemInstallCode(eq(ENV_ID), eq(4L), any(), any()))
        .thenAnswer(
            invocation -> {
              String codeHash = invocation.getArgument(2);
              String tokenHash = invocation.getArgument(3);
              if (Objects.equals(savedCodeHash.get(), codeHash)) {
                savedCodeHash.set(null);
                savedTokenHash.set(tokenHash);
                return Optional.of(5L);
              }
              return Optional.empty();
            });

    assertThrows(
        DomainException.class, () -> service.issueInstallCode(EnvironmentId.of(ENV_ID), 3L));
    EnvironmentInstallCodeDTO issued = service.issueInstallCode(EnvironmentId.of(ENV_ID), 4L);
    assertEquals(expiresAt, issued.getExpiresAt());
    verify(repo)
        .saveInstallCode(
            eq(ENV_ID), eq(4L), eq(EnvironmentInstallCodes.hash(issued.getCode())));

    String tampered =
        (issued.getCode().charAt(0) == 'A' ? "B" : "A") + issued.getCode().substring(1);
    assertThrows(
        DomainException.class,
        () ->
            service.installationScript(
                EnvironmentId.of(ENV_ID), tampered, DaemonOperatingSystem.LINUX, "3.0.0"));

    String script =
        service.installationScript(
            EnvironmentId.of(ENV_ID), issued.getCode(), DaemonOperatingSystem.LINUX, "3.0.0");

    assertTrue(script.contains("--environment-id '" + ENV_ID + "'"));
    assertTrue(script.contains("\"version\":\"5\""), "script uses the post-redeem CAS version");
    assertTrue(script.contains("v3.0.0/scripts/daemon/install.sh"));
    assertTrue(script.contains("\"proxyUrl\":null"));
    assertFalse(script.contains("secret-token"), "the old token is never recoverable");
    verify(repo)
        .redeemInstallCode(
            eq(ENV_ID),
            eq(4L),
            eq(EnvironmentInstallCodes.hash(issued.getCode())),
            eq(savedTokenHash.get()));
    assertTrue(
        script.contains(tokenFor(savedTokenHash.get(), script)),
        "the script carries the plaintext whose hash is now stored");
    assertThrows(
        DomainException.class,
        () ->
            service.installationScript(
                EnvironmentId.of(ENV_ID), issued.getCode(), DaemonOperatingSystem.LINUX, "3.0.0"),
        "a redeemed code cannot be reused");
    verify(repo, never()).updateById(any(), anyLong());
    verify(repo, never()).getByRegistrationTokenHash(any());
    assertThrows(
        DomainException.class,
        () ->
            service.installationScript(
                EnvironmentId.of(ENV_ID), null, DaemonOperatingSystem.LINUX, "3.0.0"));
  }

  /** 测试意图：未知身份或无效 code 都不能返回可执行的成功空脚本。 */
  @Test
  void installationScriptRejectsUnknownIdAndInvalidCode() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentService service = service(repo);
    when(repo.lockById(ENV_ID)).thenReturn(null);
    assertThrows(
        DomainException.class,
        () ->
            service.installationScript(
                EnvironmentId.of(ENV_ID), "1.bad", DaemonOperatingSystem.LINUX, "3.0.0"));

    Environment env = environment("saved", 1L);
    when(repo.lockById(ENV_ID)).thenReturn(env);
    assertThrows(
        DomainException.class,
        () ->
            service.installationScript(
                EnvironmentId.of(ENV_ID), "1.bad", DaemonOperatingSystem.LINUX, "3.0.0"));
    verify(repo, never()).updateById(any(), anyLong());
  }

  /** 测试意图：卸载命令只定位本卡片并校验操作系统，不读取 token，也不轮换凭据。 */
  @Test
  void uninstallationScriptTargetsTheCardWithoutToken() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentService service = service(repo);
    when(repo.getById(ENV_ID)).thenReturn(environment("saved", 1L));

    String script =
        service.uninstallationScript(
            EnvironmentId.of(ENV_ID), DaemonOperatingSystem.MACOS, "3.0.0");
    assertTrue(script.contains("uninstall --environment-id '" + ENV_ID + "'"));
    assertTrue(script.contains("v3.0.0/scripts/daemon/install.sh"));
    assertFalse(script.contains("daemon.token"));
    assertThrows(
        DomainException.class,
        () ->
            service.uninstallationScript(
                EnvironmentId.of(ENV_ID), DaemonOperatingSystem.WSL, "3.0.0"));
    verify(repo, never()).getByRegistrationTokenHash(any());

    when(repo.getById(ENV_ID)).thenReturn(null);
    assertThrows(
        DomainException.class,
        () ->
            service.uninstallationScript(
                EnvironmentId.of(ENV_ID), DaemonOperatingSystem.LINUX, "3.0.0"));
  }

  private static EnvironmentService service(EnvironmentRepository repo) {
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettingsTestFixture.DEFAULT);
    return new EnvironmentService(repo, mock(EnvironmentRegistry.class), snapshot, CLOCK);
  }

  /** 测试意图：在线轮换被允许且不要求离线，既有连接不被主动断开；轮换只改变 token 并推进版本。 */
  @Test
  void rotateTokenAllowedWhileLeaseIsActive() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    AgentRepository agents = mock(AgentRepository.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettingsTestFixture.DEFAULT);

    Environment env = new Environment();
    env.setId(ENV_ID);
    env.setName("local-env");
    env.setRegistrationTokenHash(h("old-token"));
    env.setVersion(0L);
    env.setCreatedAt(NOW);
    env.setUpdatedAt(NOW);

    when(repo.lockById(ENV_ID)).thenReturn(env);
    when(repo.getById(ENV_ID)).thenReturn(env);
    when(repo.updateById(any(), eq(0L))).thenReturn(true);
    // 数据库中存在活跃租约：轮换成功且不得查询租约（在线轮换不要求离线）
    when(registry.hasActiveLease(EnvironmentId.of(ENV_ID))).thenReturn(true);

    EnvironmentService service = new EnvironmentService(repo, registry, snapshot, CLOCK);

    EnvironmentDTO rotated = service.rotateToken(EnvironmentId.of(ENV_ID), 0L);

    assertNotNull(rotated.getRegistrationToken());
    assertNotEquals("old-token", rotated.getRegistrationToken());
    assertEquals(h(rotated.getRegistrationToken()), env.getRegistrationTokenHash());
    verify(registry, never()).hasActiveLease(any());
    verify(registry, never()).disconnect(any(), any(), any());
  }

  @Test
  void deleteChecksOnlineLeaseAndActiveWork() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    AgentRepository agents = mock(AgentRepository.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettingsTestFixture.DEFAULT);

    Environment env = new Environment();
    env.setId(ENV_ID);
    env.setName("local-env");
    env.setRegistrationTokenHash(h("token"));
    env.setVersion(0L);

    when(repo.lockById(ENV_ID)).thenReturn(env);
    when(repo.deleteById(ENV_ID, 0L)).thenReturn(true);

    EnvironmentService service = new EnvironmentService(repo, registry, snapshot, CLOCK);

    // 1. Active lease in DB -> rejects
    when(registry.hasActiveLease(EnvironmentId.of(ENV_ID))).thenReturn(true);
    assertThrows(DomainException.class, () -> service.delete(EnvironmentId.of(ENV_ID), 0L));

    // 2. Offline, but active work in durable_work -> rejects
    when(registry.hasActiveLease(EnvironmentId.of(ENV_ID))).thenReturn(false);
    when(repo.hasActiveWork(ENV_ID)).thenReturn(true);
    assertThrows(DomainException.class, () -> service.delete(EnvironmentId.of(ENV_ID), 0L));

    // 3. All clear -> succeeds
    when(repo.hasActiveWork(ENV_ID)).thenReturn(false);
    service.delete(EnvironmentId.of(ENV_ID), 0L);
    verify(repo).deleteById(ENV_ID, 0L);
  }

  /**
   * 测试意图：Environment 不内嵌历史事件；有历史 WARN 且当前 READY 时只暴露当前 READY 状态，而完整历史（历史 WARN 与后续 READY）仍由 events
   * 端点按时间正序保留，不以删除事件让卡片干净。
   */
  @Test
  void cardExposesCurrentStatusOnlyWhileEventsRetainHistory() throws Exception {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettingsTestFixture.DEFAULT);

    Environment env = new Environment();
    env.setId(ENV_ID);
    env.setName("local-env");
    env.setVersion(0L);
    when(repo.getById(ENV_ID)).thenReturn(env);

    List<EnvironmentEvent> history =
        List.of(
            new EnvironmentEvent(
                NOW,
                EnvironmentEvent.LEVEL_WARN,
                EnvironmentEvent.TYPE_SKILL_SYNC_FAILED,
                "skill package sync failed: dev"),
            new EnvironmentEvent(
                NOW.plusSeconds(1),
                EnvironmentEvent.LEVEL_INFO,
                EnvironmentEvent.TYPE_READY,
                "environment ready"));
    when(registry.find(EnvironmentId.of(ENV_ID))).thenReturn(Optional.of(connection(history)));

    EnvironmentService service = new EnvironmentService(repo, registry, snapshot, CLOCK);

    EnvironmentDTO card = service.get(EnvironmentId.of(ENV_ID));
    // 卡片只投影当前连接状态，且序列化后不含任何历史事件字段。
    assertEquals(EnvironmentStatus.READY, card.getStatus());
    assertTrue(card.isReady());
    ObjectMapper mapper = JsonMapper.builder().build();
    JsonNode json = mapper.readTree(mapper.writeValueAsString(card));
    assertFalse(json.has("lastEvent"));

    // 历史事件仍按时间正序完整保留，供管理 Events 展示。
    List<EnvironmentEventDTO> events = service.listEvents(EnvironmentId.of(ENV_ID));
    assertEquals(2, events.size());
    assertEquals(EnvironmentEventType.SKILL_SYNC_FAILED, events.get(0).getType());
    assertEquals(EnvironmentEventLevel.WARN, events.get(0).getLevel());
    assertEquals(EnvironmentEventType.READY, events.get(1).getType());
  }

  /** 测试意图：事件端点按时间正序返回同一连接行的保留事件窗口，未知 Environment 抛 404 语义错误。 */
  @Test
  void listEventsReturnsChronologicalWindow() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettingsTestFixture.DEFAULT);

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

    EnvironmentService service = new EnvironmentService(repo, registry, snapshot, CLOCK);

    List<EnvironmentEventDTO> events = service.listEvents(EnvironmentId.of(ENV_ID));
    assertEquals(2, events.size());
    assertEquals(EnvironmentEventType.CONNECTING, events.get(0).getType());
    assertEquals(EnvironmentEventType.DISCONNECTED, events.get(1).getType());
    assertEquals(NOW, events.get(0).getOccurredAt());

    // 从未连接过（无连接行）的 Environment 事件窗口为空，而不是错误。
    when(registry.find(EnvironmentId.of(ENV_ID))).thenReturn(Optional.empty());
    assertEquals(List.of(), service.listEvents(EnvironmentId.of(ENV_ID)));

    // 未知 Environment 与详情查询一样是 404 语义错误。
    when(repo.getById(ENV_ID)).thenReturn(null);
    assertThrows(DomainException.class, () -> service.listEvents(EnvironmentId.of(ENV_ID)));
  }

  /** 意图：导入新 Environment 使用新 UUID 并保留导出的凭证摘要；导入不产生可回显的明文。 */
  @Test
  void importEnvironmentCreatesNewUuidAndKeepsTheTokenHash() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    when(repo.existsByName("imported")).thenReturn(false);
    when(repo.getByRegistrationTokenHash(h("token-1"))).thenReturn(null);
    when(repo.create(any())).thenReturn(true);
    when(repo.getById(any()))
        .thenAnswer(
            invocation -> {
              Environment env = new Environment();
              env.setId(invocation.getArgument(0));
              env.setName("imported");
              env.setRegistrationTokenHash(h("token-1"));
              env.setVersion(0L);
              env.setCreatedAt(NOW);
              env.setUpdatedAt(NOW);
              return env;
            });
    EnvironmentService service =
        new EnvironmentService(
            repo, mock(EnvironmentRegistry.class), mock(SystemSettingsSnapshot.class), CLOCK);

    EnvironmentDTO card = service.importEnvironment("imported", null, h("token-1"), daemonConfig());

    assertEquals("imported", card.getName());
    assertNull(card.getRegistrationToken());
    assertEquals(0L, card.getVersion());
  }

  /** 意图：同名的 Environment 导入必须拒绝（name 即身份）。 */
  @Test
  void importEnvironmentRejectsExistingName() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    when(repo.existsByName("dup")).thenReturn(true);
    EnvironmentService service =
        new EnvironmentService(
            repo, mock(EnvironmentRegistry.class), mock(SystemSettingsSnapshot.class), CLOCK);

    assertThrows(DomainException.class, () -> service.importEnvironment("dup", null, h("token"), daemonConfig()));
  }

  /** 意图：registrationToken 已被占用时拒绝，且错误文本不回显 token 值。 */
  @Test
  void importEnvironmentRejectsTokenConflictWithoutLeakingToken() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    when(repo.existsByName("fresh")).thenReturn(false);
    when(repo.getByRegistrationTokenHash(h("secret-token"))).thenReturn(new Environment());
    EnvironmentService service =
        new EnvironmentService(
            repo, mock(EnvironmentRegistry.class), mock(SystemSettingsSnapshot.class), CLOCK);

    DomainException error =
        assertThrows(
            DomainException.class,
            () -> service.importEnvironment("fresh", null, h("secret-token"), daemonConfig()));

    assertFalse(error.getMessage().contains("secret-token"));
  }

  /** 意图：同名 Environment 更新 token 用 lockById + CAS；过期预期版本冲突，不改写既有行。 */
  @Test
  void updateImportedEnvironmentUsesCasAndConflictsOnStaleVersion() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    Environment env = new Environment();
    env.setId(ENV_ID);
    env.setName("local-env");
    env.setRegistrationTokenHash(h("old-token"));
    env.setVersion(0L);
    env.setCreatedAt(NOW);
    env.setUpdatedAt(NOW);
    when(repo.lockById(ENV_ID)).thenReturn(env);
    when(repo.updateById(any(), eq(0L))).thenReturn(true);
    when(repo.getById(ENV_ID))
        .thenAnswer(
            invocation -> {
              Environment updated = new Environment();
              updated.setId(ENV_ID);
              updated.setName("local-env");
              updated.setRegistrationTokenHash(h("new-token"));
              updated.setVersion(1L);
              updated.setCreatedAt(NOW);
              updated.setUpdatedAt(NOW);
              return updated;
            });
    EnvironmentService service =
        new EnvironmentService(
            repo, mock(EnvironmentRegistry.class), mock(SystemSettingsSnapshot.class), CLOCK);

    EnvironmentDTO card =
        service.updateImportedEnvironment(
                EnvironmentId.of(ENV_ID), null, h("new-token"), daemonConfig(), 0L);
    assertNull(card.getRegistrationToken());
    assertEquals(1L, card.getVersion());

    // 过期版本：既有行版本 2，预期 0，必须冲突。
    Environment stale = new Environment();
    stale.setId(ENV_ID);
    stale.setName("local-env");
    stale.setRegistrationTokenHash(h("newer-token"));
    stale.setVersion(2L);
    when(repo.lockById(ENV_ID)).thenReturn(stale);
    assertThrows(
        DomainException.class,
        () ->
            service.updateImportedEnvironment(
                EnvironmentId.of(ENV_ID), h("other-token"), null, 0L));
  }

  /** 意图：导入 create 返回 false 时冒泡，不留下半成品。 */
  @Test
  void importEnvironmentPropagatesCreateFailure() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    when(repo.existsByName("imported")).thenReturn(false);
    when(repo.getByRegistrationTokenHash(h("token-1"))).thenReturn(null);
    when(repo.create(any())).thenReturn(false);
    EnvironmentService service =
        new EnvironmentService(
            repo, mock(EnvironmentRegistry.class), mock(SystemSettingsSnapshot.class), CLOCK);

    assertThrows(
        IllegalStateException.class,
        () -> service.importEnvironment("imported", null, h("token-1"), daemonConfig()));
  }

  /** 意图：并发插入触发唯一约束竞争时映射为业务重复错误。 */
  @Test
  void importEnvironmentMapsDuplicateKeyToDuplicate() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    when(repo.existsByName("imported")).thenReturn(false);
    when(repo.getByRegistrationTokenHash(h("token-1"))).thenReturn(null);
    when(repo.create(any())).thenThrow(new DuplicateKeyException("dup"));
    EnvironmentService service =
        new EnvironmentService(
            repo, mock(EnvironmentRegistry.class), mock(SystemSettingsSnapshot.class), CLOCK);

    assertThrows(
        DomainException.class, () -> service.importEnvironment("imported", null, h("token-1"), daemonConfig()));
  }

  /** 意图：目标行不存在时 lockById 返回 null 必须 404。 */
  @Test
  void updateImportedEnvironmentRejectsMissingEnvironment() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    when(repo.lockById(ENV_ID)).thenReturn(null);
    EnvironmentService service =
        new EnvironmentService(
            repo, mock(EnvironmentRegistry.class), mock(SystemSettingsSnapshot.class), CLOCK);

    assertThrows(
        DomainException.class,
        () ->
            service.updateImportedEnvironment(
                EnvironmentId.of(ENV_ID), null, h("new-token"), daemonConfig(), 0L));
  }

  /** 意图：即便导入内容与当前值一致也走同一写入与版本推进，不做隐式幂等短路。 */
  @Test
  void updateImportedEnvironmentAlwaysAdvancesTheVersion() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    Environment existing = environment("old-token", 0L);
    Environment updated = environment("old-token", 1L);
    when(repo.lockById(ENV_ID)).thenReturn(existing);
    when(repo.updateById(existing, 0L)).thenReturn(true);
    when(repo.getById(ENV_ID)).thenReturn(updated);
    EnvironmentService service =
        new EnvironmentService(
            repo, mock(EnvironmentRegistry.class), mock(SystemSettingsSnapshot.class), CLOCK);

    EnvironmentDTO card =
        service.updateImportedEnvironment(
            EnvironmentId.of(ENV_ID), null, h("old-token"), daemonConfig(), 0L);

    assertNull(card.getRegistrationToken());
    assertEquals(1L, card.getVersion());
    verify(repo).updateById(existing, 0L);
  }

  /** 意图：CAS 失败且重读发现行已消失时按 404 语义失败。 */
  @Test
  void updateImportedEnvironmentCasLossWithMissingRowThrowsNotFound() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    Environment env = environment("old-token", 0L);
    when(repo.lockById(ENV_ID)).thenReturn(env);
    when(repo.updateById(env, 0L)).thenReturn(false);
    when(repo.getById(ENV_ID)).thenReturn(null);
    EnvironmentService service =
        new EnvironmentService(
            repo, mock(EnvironmentRegistry.class), mock(SystemSettingsSnapshot.class), CLOCK);

    assertThrows(
        DomainException.class,
        () ->
            service.updateImportedEnvironment(
                EnvironmentId.of(ENV_ID), null, h("new-token"), daemonConfig(), 0L));
  }

  /** 意图：CAS 失败但行仍在（版本已前进）时按版本冲突失败。 */
  @Test
  void updateImportedEnvironmentCasLossWithNewerVersionThrowsConflict() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    Environment env = environment("old-token", 0L);
    when(repo.lockById(ENV_ID)).thenReturn(env);
    when(repo.updateById(env, 0L)).thenReturn(false);
    when(repo.getById(ENV_ID)).thenReturn(environment("newer-token", 2L));
    EnvironmentService service =
        new EnvironmentService(
            repo, mock(EnvironmentRegistry.class), mock(SystemSettingsSnapshot.class), CLOCK);

    assertThrows(
        DomainException.class,
        () ->
            service.updateImportedEnvironment(
                EnvironmentId.of(ENV_ID), null, h("new-token"), daemonConfig(), 0L));
  }

  /** 意图：唯一 token 竞争触发约束异常时映射为业务重复错误。 */
  @Test
  void updateImportedEnvironmentMapsDuplicateKeyToDuplicate() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    Environment env = environment("old-token", 0L);
    when(repo.lockById(ENV_ID)).thenReturn(env);
    when(repo.updateById(env, 0L)).thenThrow(new DuplicateKeyException("dup"));
    EnvironmentService service =
        new EnvironmentService(
            repo, mock(EnvironmentRegistry.class), mock(SystemSettingsSnapshot.class), CLOCK);

    assertThrows(
        DomainException.class,
        () ->
            service.updateImportedEnvironment(
                EnvironmentId.of(ENV_ID), null, h("new-token"), daemonConfig(), 0L));
  }

  /** 意图：CRUD 与配置同步共用的静态校验覆盖全部拒绝分支。 */
  @Test
  void staticValidatorsRejectEveryInvalidShape() {
    for (String invalid : new String[] {null, "", "   ", " x", "x ", "a/b", "x".repeat(65)}) {
      assertThrows(DomainException.class, () -> EnvironmentService.validateName(invalid));
    }
    assertEquals("ok", EnvironmentService.validateName("ok"));
    assertNull(EnvironmentService.validateNote(null));
    assertNull(EnvironmentService.validateNote("   "));
    assertEquals("Card note", EnvironmentService.validateNote("  Card note  "));
    assertThrows(
        DomainException.class, () -> EnvironmentService.validateNote("bad\nnote"));
    assertThrows(
        DomainException.class, () -> EnvironmentService.validateNote("x".repeat(513)));
    assertThrows(DomainException.class, () -> EnvironmentService.validateDaemonConfig(null));
    assertEquals(
        "https://studio.example.com",
        EnvironmentService.validateDaemonConfig(daemonConfig()).getStudioUrl());
    for (String invalid : new String[] {null, "", "token", "A".repeat(64), "a".repeat(63)}) {
      assertThrows(
          DomainException.class, () -> EnvironmentService.validateRegistrationTokenHash(invalid));
    }
    assertEquals(h("t"), EnvironmentService.validateRegistrationTokenHash(h("t")));
  }

  /** 意图：create 拒绝 null 请求与重名，并把并发唯一约束竞争映射为重复错误。 */
  @Test
  void createValidatesRequestAndMapsDuplicateConstraints() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettingsTestFixture.DEFAULT);
    EnvironmentService service =
        new EnvironmentService(repo, mock(EnvironmentRegistry.class), snapshot, CLOCK);

    assertThrows(DomainException.class, () -> service.create(null));

    when(repo.existsByName("dup")).thenReturn(true);
    EnvironmentCreateDTO duplicate = new EnvironmentCreateDTO();
    duplicate.setName("dup");
    duplicate.setDaemonConfig(daemonConfig());
    assertThrows(DomainException.class, () -> service.create(duplicate));

    when(repo.existsByName("fresh")).thenReturn(false);
    when(repo.create(any())).thenThrow(new DuplicateKeyException("dup"));
    EnvironmentCreateDTO fresh = new EnvironmentCreateDTO();
    fresh.setName("fresh");
    fresh.setDaemonConfig(daemonConfig());
    assertThrows(DomainException.class, () -> service.create(fresh));
  }

  /** 意图：详情查询未知 id 必须 404。 */
  @Test
  void getUnknownEnvironmentThrowsNotFound() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentService service =
        new EnvironmentService(
            repo, mock(EnvironmentRegistry.class), mock(SystemSettingsSnapshot.class), CLOCK);

    assertThrows(DomainException.class, () -> service.get(EnvironmentId.of(ENV_ID)));
  }

  /** 意图：rotateToken 覆盖缺失行、过期版本与 CAS 丢失（行消失/版本前进）三条失败路径。 */
  @Test
  void rotateTokenFailurePaths() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentService service =
        new EnvironmentService(
            repo, mock(EnvironmentRegistry.class), mock(SystemSettingsSnapshot.class), CLOCK);

    assertThrows(DomainException.class, () -> service.rotateToken(EnvironmentId.of(ENV_ID), 0L));

    when(repo.lockById(ENV_ID)).thenReturn(environment("old", 2L));
    assertThrows(DomainException.class, () -> service.rotateToken(EnvironmentId.of(ENV_ID), 0L));

    when(repo.lockById(ENV_ID)).thenReturn(environment("old", 0L));
    when(repo.updateById(any(), eq(0L))).thenReturn(false);
    when(repo.getById(ENV_ID)).thenReturn(null);
    assertThrows(DomainException.class, () -> service.rotateToken(EnvironmentId.of(ENV_ID), 0L));

    when(repo.getById(ENV_ID)).thenReturn(environment("newer", 3L));
    assertThrows(DomainException.class, () -> service.rotateToken(EnvironmentId.of(ENV_ID), 0L));
  }

  /** 意图：delete 覆盖缺失行、过期版本、CAS 丢失与引用完整性冲突的拒绝路径。 */
  @Test
  void deleteFailurePaths() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    EnvironmentService service =
        new EnvironmentService(repo, registry, mock(SystemSettingsSnapshot.class), CLOCK);

    assertThrows(DomainException.class, () -> service.delete(EnvironmentId.of(ENV_ID), 0L));

    when(repo.lockById(ENV_ID)).thenReturn(environment("token", 2L));
    assertThrows(DomainException.class, () -> service.delete(EnvironmentId.of(ENV_ID), 0L));

    when(repo.lockById(ENV_ID)).thenReturn(environment("token", 0L));
    when(registry.hasActiveLease(EnvironmentId.of(ENV_ID))).thenReturn(false);
    when(repo.hasActiveWork(ENV_ID)).thenReturn(false);

    when(repo.deleteById(ENV_ID, 0L)).thenReturn(false);
    when(repo.getById(ENV_ID)).thenReturn(null);
    assertThrows(DomainException.class, () -> service.delete(EnvironmentId.of(ENV_ID), 0L));

    when(repo.getById(ENV_ID)).thenReturn(environment("token", 5L));
    assertThrows(DomainException.class, () -> service.delete(EnvironmentId.of(ENV_ID), 0L));

    when(repo.deleteById(ENV_ID, 0L)).thenThrow(new DataIntegrityViolationException("ref"));
    assertThrows(DomainException.class, () -> service.delete(EnvironmentId.of(ENV_ID), 0L));
  }

  /** 测试意图：租约/心跳窗口已过期的连接用同一时钟派生为 OFFLINE，不对外暴露可用能力。 */
  @Test
  void expiredConnectionReadsAsOfflineWithoutUsableCapabilities() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettingsTestFixture.DEFAULT);
    when(repo.getById(ENV_ID)).thenReturn(environment("token", 0L));
    when(registry.find(EnvironmentId.of(ENV_ID)))
        .thenReturn(
            Optional.of(
                connectionAt(
                    LiveEnvironmentStatus.READY, NOW.minusSeconds(120), NOW.minusSeconds(60))));

    EnvironmentService service = new EnvironmentService(repo, registry, snapshot, CLOCK);

    EnvironmentDTO card = service.get(EnvironmentId.of(ENV_ID));

    assertEquals(EnvironmentStatus.OFFLINE, card.getStatus());
    assertFalse(card.isReady());
    assertNull(card.getStatusExpiresAt(), "失效连接没有未来的状态截止时间");
    assertTrue(card.getCapabilities().isEmpty(), "过期连接的能力不得再当作可用");
  }

  /** 测试意图：statusExpiresAt 是租约与心跳窗口两个上界中更早者；未过期的 CONNECTING 同样给出截止时间。 */
  @Test
  void statusExpiresAtFollowsTheEarlierOfLeaseAndHeartbeatWindow() {
    EnvironmentRepository repo = mock(EnvironmentRepository.class);
    EnvironmentRegistry registry = mock(EnvironmentRegistry.class);
    SystemSettingsSnapshot snapshot = mock(SystemSettingsSnapshot.class);
    when(snapshot.get()).thenReturn(SystemSettingsTestFixture.DEFAULT);
    when(repo.getById(ENV_ID)).thenReturn(environment("token", 0L));

    EnvironmentService service = new EnvironmentService(repo, registry, snapshot, CLOCK);

    // 心跳窗口（默认 60s）先到：截止时间取 lastSeen + heartbeatTimeout，而不是更晚的租约。
    when(registry.find(EnvironmentId.of(ENV_ID)))
        .thenReturn(
            Optional.of(connectionAt(LiveEnvironmentStatus.READY, NOW, NOW.plusSeconds(300))));
    EnvironmentDTO heartbeatBound = service.get(EnvironmentId.of(ENV_ID));
    assertEquals(EnvironmentStatus.READY, heartbeatBound.getStatus());
    assertEquals(NOW.plusSeconds(60), heartbeatBound.getStatusExpiresAt());

    // 租约先到：截止时间取 leaseUntil。
    when(registry.find(EnvironmentId.of(ENV_ID)))
        .thenReturn(
            Optional.of(connectionAt(LiveEnvironmentStatus.READY, NOW, NOW.plusSeconds(10))));
    EnvironmentDTO leaseBound = service.get(EnvironmentId.of(ENV_ID));
    assertEquals(NOW.plusSeconds(10), leaseBound.getStatusExpiresAt());

    // 未过期的 CONNECTING 也必须给出截止时间：否则一个永远不 READY 的连接会让浏览器无限期保留旧状态。
    when(registry.find(EnvironmentId.of(ENV_ID)))
        .thenReturn(
            Optional.of(connectionAt(LiveEnvironmentStatus.CONNECTING, NOW, NOW.plusSeconds(30))));
    EnvironmentDTO connecting = service.get(EnvironmentId.of(ENV_ID));
    assertEquals(EnvironmentStatus.CONNECTING, connecting.getStatus());
    assertEquals(NOW.plusSeconds(30), connecting.getStatusExpiresAt());
  }

  private static String h(String token) {
    return RegistrationTokens.hash(token);
  }

  private static DaemonConfigDTO daemonConfig() {
    DaemonConfigDTO config = new DaemonConfigDTO();
    config.setStudioUrl("https://studio.example.com");
    return config;
  }

  /** 从脚本中找出摘要与 {@code hash} 相符的明文凭证（UUID 形态）。 */
  private static String tokenFor(String hash, String script) {
    Matcher matcher =
        Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            .matcher(script);
    while (matcher.find()) {
      if (h(matcher.group()).equals(hash)) {
        return matcher.group();
      }
    }
    throw new AssertionError("script does not carry the issued token");
  }

  private static Environment environment(String registrationToken, long version) {
    Environment env = new Environment();
    env.setId(ENV_ID);
    env.setName("local-env");
    env.setRegistrationTokenHash(h(registrationToken));
    env.setDaemonConfig(daemonConfig());
    env.setVersion(version);
    env.setCreatedAt(NOW);
    env.setUpdatedAt(NOW);
    return env;
  }

  private static EnvironmentConnection connection(List<EnvironmentEvent> events) {
    return new EnvironmentConnection(
        EnvironmentId.of(ENV_ID),
        UUID.randomUUID(),
        UUID.randomUUID(),
        LiveEnvironmentStatus.READY,
        new DaemonCapabilities(
            "1.0.9",
            new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX,
                "Asia/Shanghai",
                "dev",
                "/home/dev",
                "Note",
                "/tmp/kk-studio",
                "amd64")),
        List.of(),
        events,
        NOW,
        NOW.plusSeconds(60));
  }

  private static EnvironmentConnection connectionAt(
      LiveEnvironmentStatus status, Instant lastSeenAt, Instant leaseUntil) {
    return new EnvironmentConnection(
        EnvironmentId.of(ENV_ID),
        UUID.randomUUID(),
        UUID.randomUUID(),
        status,
        new DaemonCapabilities(
            "1.0.9",
            new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX,
                "Asia/Shanghai",
                "dev",
                "/home/dev",
                "Note",
                "/tmp/kk-studio",
                "amd64")),
        List.of(),
        List.of(),
        lastSeenAt,
        leaseUntil);
  }
}
