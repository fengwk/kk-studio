package fun.fengwk.kkstudio.core.harness.tool.worker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.core.harness.tool.store.MysqlToolInvocationStore;
import fun.fengwk.kkstudio.core.harness.tool.store.mapper.ToolInvocationMapper;
import fun.fengwk.kkstudio.core.harness.tool.store.model.ToolInvocationDO;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionAction;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolTargetType;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ClaimedToolInvocation;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Unit contracts for Environment-specific contention, validation, and lease predicates. */
class DatabaseEnvironmentToolInvocationWorkerStoreTest {

  private static final String ENVIRONMENT_NAME = "env-42";
  private static final long INVOCATION_ID = 99L;
  private static final Instant NOW = Instant.parse("2026-07-17T00:00:00Z");
  private static final Duration LEASE_DURATION = Duration.ofSeconds(30);

  /** A failed compare-and-set is retried and a recovered RUNNING row keeps its recovery marker. */
  @Test
  void retriesContentionAndMarksRecoveredLease() {
    ToolInvocationMapper mapper = mock(ToolInvocationMapper.class);
    MysqlToolInvocationStore invocationStore = mock(MysqlToolInvocationStore.class);
    DatabaseEnvironmentToolInvocationWorkerStore store =
        new DatabaseEnvironmentToolInvocationWorkerStore(mapper, invocationStore);
    ToolInvocationDO candidate = new ToolInvocationDO();
    candidate.setId(INVOCATION_ID);
    candidate.setStatus(ToolInvocationStatus.RUNNING.name());
    ToolInvocation invocation = environmentInvocation();
    when(mapper.findEnvironmentClaimCandidate(eq(ENVIRONMENT_NAME), any())).thenReturn(candidate);
    when(mapper.claimEnvironment(
            eq(INVOCATION_ID), eq(ENVIRONMENT_NAME), eq("worker"), any(), any()))
        .thenReturn(0, 1);
    when(invocationStore.find(INVOCATION_ID)).thenReturn(Optional.of(invocation));

    Optional<ClaimedToolInvocation> claimed =
        store.claimDue(ENVIRONMENT_NAME, "worker", NOW, LEASE_DURATION);

    assertTrue(claimed.isPresent());
    assertTrue(claimed.orElseThrow().recoveredLease());
    assertEquals(INVOCATION_ID, claimed.orElseThrow().invocation().id());
    verify(mapper, times(2)).findEnvironmentClaimCandidate(eq(ENVIRONMENT_NAME), any());
    verify(mapper, times(2))
        .claimEnvironment(eq(INVOCATION_ID), eq(ENVIRONMENT_NAME), eq("worker"), any(), any());
  }

  /** Absence is distinct from contention and does not perform a claim compare-and-set. */
  @Test
  void returnsEmptyWhenNoEnvironmentCandidateExists() {
    ToolInvocationMapper mapper = mock(ToolInvocationMapper.class);
    MysqlToolInvocationStore invocationStore = mock(MysqlToolInvocationStore.class);
    DatabaseEnvironmentToolInvocationWorkerStore store =
        new DatabaseEnvironmentToolInvocationWorkerStore(mapper, invocationStore);
    when(mapper.findEnvironmentClaimCandidate(eq(ENVIRONMENT_NAME), any())).thenReturn(null);

    assertFalse(store.claimDue(ENVIRONMENT_NAME, "worker", NOW, LEASE_DURATION).isPresent());
  }

  /** Invalid lease inputs and a non-Environment claimed row are rejected before mapper mutation. */
  @Test
  void validatesLeaseRequestsAndHeartbeatScope() {
    ToolInvocationMapper mapper = mock(ToolInvocationMapper.class);
    MysqlToolInvocationStore invocationStore = mock(MysqlToolInvocationStore.class);
    DatabaseEnvironmentToolInvocationWorkerStore store =
        new DatabaseEnvironmentToolInvocationWorkerStore(mapper, invocationStore);

    assertThrows(
        IllegalArgumentException.class, () -> store.claimDue(" ", "worker", NOW, LEASE_DURATION));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.claimDue(ENVIRONMENT_NAME, " ", NOW, LEASE_DURATION));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.claimDue(ENVIRONMENT_NAME, "worker", NOW, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            store.heartbeat(
                new ClaimedToolInvocation(cloudInvocation(), false), NOW, LEASE_DURATION));

    ClaimedToolInvocation environmentClaimed =
        new ClaimedToolInvocation(environmentInvocation(), false);
    when(mapper.heartbeatEnvironment(
            eq(INVOCATION_ID), eq(ENVIRONMENT_NAME), eq("worker"), any(), any()))
        .thenReturn(1);
    assertTrue(store.heartbeat(environmentClaimed, NOW, LEASE_DURATION));
  }

  /** The query facade rejects invalid ids and forwards a valid durable lookup unchanged. */
  @Test
  void validatesFindIdAndDelegatesValidLookup() {
    ToolInvocationMapper mapper = mock(ToolInvocationMapper.class);
    MysqlToolInvocationStore invocationStore = mock(MysqlToolInvocationStore.class);
    DatabaseEnvironmentToolInvocationWorkerStore store =
        new DatabaseEnvironmentToolInvocationWorkerStore(mapper, invocationStore);
    ToolInvocation invocation = environmentInvocation();
    when(invocationStore.find(INVOCATION_ID)).thenReturn(Optional.of(invocation));

    assertThrows(IllegalArgumentException.class, () -> store.find(0));
    assertEquals(invocation, store.find(INVOCATION_ID).orElseThrow());
    verify(invocationStore).find(INVOCATION_ID);
  }

  private static ToolInvocation environmentInvocation() {
    return invocation(ToolTargetType.ENVIRONMENT, ENVIRONMENT_NAME);
  }

  private static ToolInvocation cloudInvocation() {
    return invocation(ToolTargetType.CLOUD, null);
  }

  private static ToolInvocation invocation(ToolTargetType targetType, String environmentName) {
    return new ToolInvocation(
        INVOCATION_ID,
        101L,
        102L,
        0,
        "tool-call",
        "read",
        "1",
        targetType,
        environmentName,
        "{}",
        ToolInvocationStatus.RUNNING,
        PermissionAction.ALLOW,
        null,
        ToolSideEffect.READ_ONLY,
        NOW.plusSeconds(60),
        "worker",
        NOW.plusSeconds(30),
        null,
        null,
        null,
        NOW,
        NOW,
        null,
        NOW);
  }
}
