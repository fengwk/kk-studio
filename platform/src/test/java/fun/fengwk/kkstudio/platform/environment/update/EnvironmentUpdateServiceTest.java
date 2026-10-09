package fun.fengwk.kkstudio.platform.environment.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import fun.fengwk.kkstudio.harness.environment.EnvironmentId;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateArtifact;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateCommand;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateResult;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.environment.gateway.EnvironmentDaemonGateway;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentUpdateDTO;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 意图：验证受管更新的单一产品路径不变量——固定官方目标下发、一个 Environment 至多一次活动更新、忙拒绝、阶段推进、 断开派生 UNKNOWN，以及只有目标版本重新 READY
 * 才算成功。
 */
class EnvironmentUpdateServiceTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private static final String TARGET = "1.0.9";

  private static final String SHA256 = "a".repeat(64);

  private EnvironmentDaemonServer server;
  private EnvironmentRegistry registry;
  private EnvironmentRepository environmentRepository;
  private InMemoryUpdateRepository updateRepository;
  private DaemonReleaseProvider releaseProvider;
  private EnvironmentUpdateService service;

  @BeforeEach
  void setUp() {
    server = mock(EnvironmentDaemonServer.class);
    registry = mock(EnvironmentRegistry.class);
    environmentRepository = mock(EnvironmentRepository.class);
    updateRepository = new InMemoryUpdateRepository();
    releaseProvider =
        () ->
            Optional.of(
                new DaemonReleaseTarget(TARGET, DaemonUpdateArtifact.artifactUrl(TARGET), SHA256));
    service = newService();
  }

  private EnvironmentUpdateService newService() {
    return new EnvironmentUpdateService(
        new EnvironmentDaemonGateway(server, registry),
        registry,
        environmentRepository,
        updateRepository,
        releaseProvider,
        new SystemSettingsSnapshot(SystemSettings.DEFAULT),
        mock(JdbcTemplate.class),
        CLOCK);
  }

  @Test
  void startUpdatePersistsRunningAndSendsFixedOfficialTarget() {
    stubReady(TARGET);

    EnvironmentUpdateDTO dto = service.startUpdate(ENV);

    assertEquals("RUNNING", dto.getPhase());
    assertEquals(TARGET, dto.getTargetVersion());
    ArgumentCaptor<DaemonUpdateCommand> command =
        ArgumentCaptor.forClass(DaemonUpdateCommand.class);
    InOrder order = inOrder(server);
    order.verify(server).beginUpdate(eq(ENV), eq(dto.getOperationId()));
    order.verify(server).sendUpdate(eq(ENV), command.capture());
    assertEquals(TARGET, command.getValue().targetVersion());
    assertEquals(DaemonUpdateArtifact.artifactUrl(TARGET), command.getValue().artifactUrl());
    assertEquals(SHA256, command.getValue().artifactSha256());
  }

  @Test
  void secondUpdateIsRejectedWhileAnotherIsActive() {
    stubReady(TARGET);
    service.startUpdate(ENV);

    assertThrows(AiInUseException.class, () -> service.startUpdate(ENV));

    verify(server, times(1)).beginUpdate(any(), any());
  }

  @Test
  void startUpdateIsRejectedWhenInvocationsAreInFlight() {
    stubReady(TARGET);
    when(server.activeInvocationCount(ENV)).thenReturn(2);

    assertThrows(AiInUseException.class, () -> service.startUpdate(ENV));

    verify(server, never()).beginUpdate(any(), any());
    assertTrue(service.describe(ENV).isEmpty());
  }

  @Test
  void startUpdateIsRejectedWhenEnvironmentIsNotReady() {
    when(environmentRepository.getById(ENV.value())).thenReturn(new Environment());
    when(registry.find(ENV)).thenReturn(Optional.empty());

    assertThrows(AiInUseException.class, () -> service.startUpdate(ENV));

    assertTrue(service.describe(ENV).isEmpty());
  }

  @Test
  void startUpdateIsRejectedWhenOfficialReleaseIsUnavailable() {
    stubReady(TARGET);
    releaseProvider = () -> Optional.empty();
    service = newService();

    assertThrows(AiInUseException.class, () -> service.startUpdate(ENV));

    verify(server, never()).beginUpdate(any(), any());
    assertTrue(service.describe(ENV).isEmpty());
  }

  @Test
  void updateResultAdvancesPhaseAndFailureReleasesAdmission() {
    stubReady(TARGET);
    String operationId = service.startUpdate(ENV).getOperationId();

    service.onUpdateResult(ENV, DaemonUpdateResult.prepared(operationId));
    assertEquals("PREPARED", service.describe(ENV).orElseThrow().getPhase());

    service.onUpdateResult(ENV, DaemonUpdateResult.failed(operationId, "checksum mismatch"));
    EnvironmentUpdateDTO failed = service.describe(ENV).orElseThrow();
    assertEquals("FAILED", failed.getPhase());
    assertEquals("checksum mismatch", failed.getError());
    verify(server).endUpdate(ENV, operationId);
  }

  @Test
  void updateResultForUnknownOperationIsIgnored() {
    stubReady(TARGET);
    service.startUpdate(ENV);

    service.onUpdateResult(
        ENV, DaemonUpdateResult.accepted("99999999-9999-9999-9999-999999999999"));

    assertEquals("RUNNING", service.describe(ENV).orElseThrow().getPhase());
    verify(server, never()).endUpdate(any(), any());
  }

  @Test
  void onlyTargetVersionReadyConfirmsSuccess() {
    stubReady("1.0.8");
    String operationId = service.startUpdate(ENV).getOperationId();

    service.onEnvironmentReady(ENV);
    assertEquals("RUNNING", service.describe(ENV).orElseThrow().getPhase());
    verify(server, never()).endUpdate(any(), any());

    when(registry.find(ENV)).thenReturn(Optional.of(connection(TARGET)));

    service.onEnvironmentReady(ENV);
    assertEquals("SUCCEEDED", service.describe(ENV).orElseThrow().getPhase());
    verify(server).endUpdate(ENV, operationId);
  }

  @Test
  void activeOperationIsDerivedAsUnknownWhileEnvironmentIsUnreachable() {
    stubReady(TARGET);
    service.startUpdate(ENV);

    when(registry.find(ENV)).thenReturn(Optional.empty());

    assertEquals("UNKNOWN", service.describe(ENV).orElseThrow().getPhase());
  }

  private void stubReady(String daemonVersion) {
    when(environmentRepository.getById(ENV.value())).thenReturn(new Environment());
    when(registry.find(ENV)).thenReturn(Optional.of(connection(daemonVersion)));
    when(server.activeInvocationCount(ENV)).thenReturn(0);
  }

  private static EnvironmentConnection connection(String daemonVersion) {
    return new EnvironmentConnection(
        ENV,
        UUID.randomUUID(),
        UUID.randomUUID(),
        LiveEnvironmentStatus.READY,
        new DaemonCapabilities(
            DaemonCapabilities.VERSION,
            daemonVersion,
            new DaemonEnvironmentInfo(
                DaemonOperatingSystem.LINUX, "Asia/Shanghai", "dev", "/home/dev", "Note")),
        List.of(),
        List.of(),
        NOW,
        NOW.plusSeconds(600));
  }

  /** 内存版准入仓库：用「同 Environment 至多一条活动行」表达与数据库部分唯一索引等价的不变量。 */
  private static final class InMemoryUpdateRepository implements EnvironmentUpdateRepository {

    private final Map<String, EnvironmentUpdateOperation> rows = new LinkedHashMap<>();

    @Override
    public Optional<EnvironmentUpdateOperation> find(String operationId) {
      return Optional.ofNullable(rows.get(operationId));
    }

    @Override
    public Optional<EnvironmentUpdateOperation> findActive(EnvironmentId environmentId) {
      return rows.values().stream()
          .filter(operation -> operation.environmentId().equals(environmentId))
          .filter(operation -> operation.phase().isActive())
          .findFirst();
    }

    @Override
    public Optional<EnvironmentUpdateOperation> findLatest(EnvironmentId environmentId) {
      List<EnvironmentUpdateOperation> matched = new ArrayList<>();
      for (EnvironmentUpdateOperation operation : rows.values()) {
        if (operation.environmentId().equals(environmentId)) {
          matched.add(operation);
        }
      }
      return matched.isEmpty() ? Optional.empty() : Optional.of(matched.get(matched.size() - 1));
    }

    @Override
    public List<EnvironmentUpdateOperation> list(EnvironmentId environmentId, int limit) {
      return findLatest(environmentId).stream().toList();
    }

    @Override
    public boolean insertPending(EnvironmentUpdateOperation operation) {
      if (rows.containsKey(operation.operationId())
          || findActive(operation.environmentId()).isPresent()) {
        return false;
      }
      rows.put(operation.operationId(), operation);
      return true;
    }

    @Override
    public boolean advance(
        String operationId,
        EnvironmentUpdatePhase next,
        String error,
        Set<EnvironmentUpdatePhase> allowedFrom) {
      EnvironmentUpdateOperation current = rows.get(operationId);
      if (current == null || !allowedFrom.contains(current.phase())) {
        return false;
      }
      rows.put(
          operationId,
          new EnvironmentUpdateOperation(
              current.operationId(),
              current.environmentId(),
              current.targetVersion(),
              next,
              error,
              current.createdAt(),
              current.updatedAt()));
      return true;
    }
  }
}
