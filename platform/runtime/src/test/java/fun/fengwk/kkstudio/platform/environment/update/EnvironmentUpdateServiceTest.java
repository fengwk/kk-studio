package fun.fengwk.kkstudio.platform.environment.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityBusyException;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityUnavailableException;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonArchitecture;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonCapabilities;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonEnvironmentInfo;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonOperatingSystem;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonRuntimeSnapshot;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonRuntimeStatus;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateArtifact;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateCommand;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateProgress;
import fun.fengwk.kkstudio.harness.environment.daemon.DaemonUpdateResult;
import fun.fengwk.kkstudio.harness.environment.server.EnvironmentDaemonServer;
import fun.fengwk.kkstudio.platform.environment.gateway.EnvironmentDaemonGateway;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentConnection;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentEvent;
import fun.fengwk.kkstudio.platform.environment.registry.EnvironmentRegistry;
import fun.fengwk.kkstudio.platform.environment.registry.LiveEnvironmentStatus;
import fun.fengwk.kkstudio.platform.environment.repo.EnvironmentRepository;
import fun.fengwk.kkstudio.platform.environment.service.model.Environment;
import fun.fengwk.kkstudio.platform.error.AiEnvironmentUnavailableException;
import fun.fengwk.kkstudio.platform.error.AiInUseException;
import fun.fengwk.kkstudio.platform.settings.SystemSettings;
import fun.fengwk.kkstudio.platform.settings.SystemSettingsSnapshot;
import fun.fengwk.kkstudio.share.ai.environment.EnvironmentUpdateStartDTO;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 意图：验证更新路径只依赖当前 runtimeStatus，不持久任务；发送窗口结束后释放准入，回执失败只产生有界事件，目标版本 READY 才是最终事实。 */
class EnvironmentUpdateServiceTest {

  private static final EnvironmentId ENV =
      EnvironmentId.parse("11111111-1111-1111-1111-111111111111");

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String CURRENT = "1.0.8";
  private static final String TARGET = "1.0.9";
  private static final String SHA256 = "a".repeat(64);

  private EnvironmentDaemonServer server;
  private EnvironmentRegistry registry;
  private EnvironmentRepository environmentRepository;
  private DaemonReleaseProvider releaseProvider;
  private JdbcTemplate jdbcTemplate;
  private EnvironmentUpdateService service;

  @BeforeEach
  void setUp() {
    server = mock(EnvironmentDaemonServer.class);
    registry = mock(EnvironmentRegistry.class);
    environmentRepository = mock(EnvironmentRepository.class);
    jdbcTemplate = mock(JdbcTemplate.class);
    releaseProvider =
        (operatingSystem, architecture) ->
            Optional.of(
                new DaemonReleaseTarget(
                    TARGET,
                    DaemonUpdateArtifact.artifactUrl(TARGET, operatingSystem, architecture),
                    SHA256));
    service = newService();
    when(environmentRepository.getById(ENV.value())).thenReturn(new Environment());
    when(registry.find(ENV)).thenReturn(Optional.of(connection(CURRENT)));
    when(registry.ownerNodeId())
        .thenReturn(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
    when(server.activeInvocationCount(ENV)).thenReturn(0);
    when(server.runtimeStatus(ENV)).thenReturn(Optional.of(snapshot(CURRENT, null)));
  }

  private EnvironmentUpdateService newService() {
    return new EnvironmentUpdateService(
        new EnvironmentDaemonGateway(server, registry),
        registry,
        environmentRepository,
        releaseProvider,
        new SystemSettingsSnapshot(SystemSettings.DEFAULT),
        jdbcTemplate,
        CLOCK);
  }

  @Test
  void sendsFixedTargetWithoutPersistingAndMarksDeliveryUnconfirmed() {
    EnvironmentUpdateStartDTO result = service.startUpdate(ENV);

    assertEquals(EnvironmentUpdateStartDTO.SENT_UNCONFIRMED, result.getCommandStatus());
    assertEquals(CURRENT, result.getRuntimeStatus().getStatus().getDaemonVersion());
    ArgumentCaptor<DaemonUpdateCommand> command =
        ArgumentCaptor.forClass(DaemonUpdateCommand.class);
    InOrder order = inOrder(server);
    order.verify(server).beginUpdate(eq(ENV), any());
    order.verify(server).sendUpdate(eq(ENV), command.capture());
    order.verify(server).endUpdate(eq(ENV), any());
    assertEquals(TARGET, command.getValue().targetVersion());
    assertEquals(
        DaemonUpdateArtifact.artifactUrl(
            TARGET, DaemonOperatingSystem.LINUX, DaemonArchitecture.AMD64),
        command.getValue().artifactUrl());
    assertEquals(SHA256, command.getValue().artifactSha256());
  }

  @Test
  void currentTargetReturnsCurrentWithoutSending() {
    when(server.runtimeStatus(ENV)).thenReturn(Optional.of(snapshot(TARGET, null)));

    EnvironmentUpdateStartDTO result = service.startUpdate(ENV);

    assertEquals(EnvironmentUpdateStartDTO.CURRENT, result.getCommandStatus());
    assertEquals(TARGET, result.getRuntimeStatus().getStatus().getDaemonVersion());
    verify(server, never()).beginUpdate(any(), any());
    verify(server, never()).sendUpdate(any(), any());
  }

  @Test
  void sameTargetProgressReturnsSnapshotWithoutStartingSecondCommand() {
    when(server.runtimeStatus(ENV))
        .thenReturn(Optional.of(snapshot(CURRENT, new DaemonUpdateProgress(TARGET, 1, 2L))));

    EnvironmentUpdateStartDTO result = service.startUpdate(ENV);

    assertEquals(EnvironmentUpdateStartDTO.IN_PROGRESS, result.getCommandStatus());
    assertEquals(TARGET, result.getRuntimeStatus().getStatus().getUpdate().getTargetVersion());
    verify(server, never()).beginUpdate(any(), any());
  }

  @Test
  void differentTargetRejectsWithoutSending() {
    when(server.runtimeStatus(ENV))
        .thenReturn(Optional.of(snapshot(CURRENT, new DaemonUpdateProgress("1.0.7", 1, 2L))));

    AiInUseException error = assertThrows(AiInUseException.class, () -> service.startUpdate(ENV));

    assertTrue(error.getMessage().contains("1.0.7"), error.getMessage());
    verify(server, never()).beginUpdate(any(), any());
  }

  @Test
  void rejectsWhenInvocationsAreInFlight() {
    when(server.activeInvocationCount(ENV)).thenReturn(2);

    assertThrows(AiInUseException.class, () -> service.startUpdate(ENV));

    verify(server, never()).beginUpdate(any(), any());
  }

  @Test
  void rejectsWhenDurableWorkRequiresEnvironment() {
    when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(ENV.value())))
        .thenReturn(1);

    assertThrows(AiInUseException.class, () -> service.startUpdate(ENV));

    verify(server, never()).beginUpdate(any(), any());
  }

  @Test
  void rejectsWhenRuntimeSnapshotIsUnavailable() {
    when(server.runtimeStatus(ENV)).thenReturn(Optional.empty());

    assertThrows(AiEnvironmentUnavailableException.class, () -> service.startUpdate(ENV));

    verify(server, never()).beginUpdate(any(), any());
  }

  @Test
  void rejectsWhenEnvironmentIsNotReady() {
    when(registry.find(ENV)).thenReturn(Optional.empty());

    assertThrows(AiEnvironmentUnavailableException.class, () -> service.startUpdate(ENV));

    verify(server, never()).beginUpdate(any(), any());
  }

  @Test
  void rejectsWhenOfficialReleaseIsUnavailable() {
    releaseProvider = (operatingSystem, architecture) -> Optional.empty();
    service = newService();

    assertThrows(AiInUseException.class, () -> service.startUpdate(ENV));

    verify(server, never()).beginUpdate(any(), any());
  }

  @Test
  void rejectsUnsupportedPlatform() {
    when(registry.find(ENV))
        .thenReturn(
            Optional.of(
                connection(
                    CURRENT, DaemonOperatingSystem.WINDOWS, DaemonArchitecture.ARM64.wireValue())));

    AiInUseException error = assertThrows(AiInUseException.class, () -> service.startUpdate(ENV));

    assertTrue(error.getMessage().contains("windows-arm64"), error.getMessage());
    verify(server, never()).beginUpdate(any(), any());
  }

  @Test
  void rejectsUnknownOrNonCanonicalArchitecture() {
    for (String reported : Arrays.asList(null, "x86_64", "AMD64")) {
      when(registry.find(ENV))
          .thenReturn(Optional.of(connection(CURRENT, DaemonOperatingSystem.LINUX, reported)));

      AiInUseException error = assertThrows(AiInUseException.class, () -> service.startUpdate(ENV));

      assertFalse(error.getMessage().contains("x86_64"), error.getMessage());
    }
    verify(server, never()).beginUpdate(any(), any());
  }

  @Test
  void failedReceiptRecordsOneBoundedOperationalEventAndDoesNotAdvanceTaskState() {
    EnvironmentUpdateStartDTO started = service.startUpdate(ENV);
    assertEquals(EnvironmentUpdateStartDTO.SENT_UNCONFIRMED, started.getCommandStatus());

    service.onUpdateResult(
        ENV, DaemonUpdateResult.failed(UUID.randomUUID().toString(), "checksum mismatch"));

    ArgumentCaptor<EnvironmentEvent> event = ArgumentCaptor.forClass(EnvironmentEvent.class);
    verify(registry).recordEvent(eq(ENV), any(), event.capture());
    assertEquals(EnvironmentEvent.TYPE_UPDATE_FAILED, event.getValue().type());
    assertEquals(EnvironmentEvent.LEVEL_WARN, event.getValue().level());
    assertEquals("daemon update failed", event.getValue().message());
    verify(server, times(1)).endUpdate(eq(ENV), any());
  }

  @Test
  void preparedReceiptIsBoundedAndCreatesNoEventOrTaskState() {
    service.startUpdate(ENV);

    service.onUpdateResult(ENV, DaemonUpdateResult.prepared(UUID.randomUUID().toString()));

    verify(registry, never()).recordEvent(any(), any(), any());
  }

  @Test
  void beginRejectionDoesNotReleaseAnAdmissionOwnedByAnotherOperation() {
    doThrow(new EnvironmentCapabilityBusyException("another operation"))
        .when(server)
        .beginUpdate(eq(ENV), any());

    assertThrows(AiInUseException.class, () -> service.startUpdate(ENV));

    verify(server, never()).sendUpdate(any(), any());
    verify(server, never()).endUpdate(any(), any());
  }

  @Test
  void sendBusyRecordsFailureAndAlwaysReleasesTransientAdmission() {
    doThrow(new EnvironmentCapabilityBusyException("outbound queue full"))
        .when(server)
        .sendUpdate(eq(ENV), any());

    assertThrows(AiInUseException.class, () -> service.startUpdate(ENV));

    verify(server).beginUpdate(eq(ENV), any());
    verify(server).endUpdate(eq(ENV), any());
    verify(registry).recordEvent(eq(ENV), any(), any());
  }

  @Test
  void sendFailureReleasesTransientAdmissionAndRecordsFailure() {
    doThrow(new EnvironmentCapabilityUnavailableException("connection lost"))
        .when(server)
        .sendUpdate(eq(ENV), any());

    assertThrows(AiEnvironmentUnavailableException.class, () -> service.startUpdate(ENV));

    verify(server).beginUpdate(eq(ENV), any());
    verify(server).endUpdate(eq(ENV), any());
    verify(registry).recordEvent(eq(ENV), any(), any());
  }

  private static DaemonRuntimeSnapshot snapshot(String daemonVersion, DaemonUpdateProgress update) {
    return new DaemonRuntimeSnapshot(1L, new DaemonRuntimeStatus(daemonVersion, update));
  }

  private static EnvironmentConnection connection(String daemonVersion) {
    return connection(
        daemonVersion, DaemonOperatingSystem.LINUX, DaemonArchitecture.AMD64.wireValue());
  }

  private static EnvironmentConnection connection(
      String daemonVersion, DaemonOperatingSystem operatingSystem, String architecture) {
    boolean windows = operatingSystem == DaemonOperatingSystem.WINDOWS;
    return new EnvironmentConnection(
        ENV,
        UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
        UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"),
        LiveEnvironmentStatus.READY,
        new DaemonCapabilities(
            DaemonCapabilities.VERSION,
            daemonVersion,
            new DaemonEnvironmentInfo(
                operatingSystem,
                "Asia/Shanghai",
                "dev",
                windows ? "C:\\Users\\dev" : "/home/dev",
                "Note",
                windows ? "C:\\Temp" : "/tmp/kk-studio",
                architecture)),
        List.of(),
        List.of(),
        NOW,
        NOW.plusSeconds(600));
  }
}
