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
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ClaimedToolInvocation;
import fun.fengwk.kkstudio.harness.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Unit contracts for Platform claim contention, Thread scoping, and lease delegation. */
class DatabasePlatformToolInvocationWorkerStoreTest {

  private static final long INVOCATION_ID = 99L;
  private static final long THREAD_ID = 101L;
  private static final Instant NOW = Instant.parse("2026-07-23T00:00:00Z");
  private static final Duration LEASE_DURATION = Duration.ofSeconds(30);

  /** A failed global CAS is retried and a reclaimed RUNNING row retains its recovery marker. */
  @Test
  void retriesGlobalContentionAndMarksRecoveredLease() {
    ToolInvocationMapper mapper = mock(ToolInvocationMapper.class);
    MysqlToolInvocationStore invocationStore = mock(MysqlToolInvocationStore.class);
    DatabasePlatformToolInvocationWorkerStore store =
        new DatabasePlatformToolInvocationWorkerStore(mapper, invocationStore);
    ToolInvocationDO candidate = candidate(ToolInvocationStatus.RUNNING);
    ToolInvocation invocation = invocation();
    when(mapper.findPlatformClaimCandidate(any())).thenReturn(candidate);
    when(mapper.claim(eq(INVOCATION_ID), eq("worker"), any(), any())).thenReturn(0, 1);
    when(invocationStore.find(INVOCATION_ID)).thenReturn(Optional.of(invocation));

    Optional<ClaimedToolInvocation> claimed = store.claimDue("worker", NOW, LEASE_DURATION);

    assertTrue(claimed.isPresent());
    assertTrue(claimed.orElseThrow().recoveredLease());
    assertEquals(invocation, claimed.orElseThrow().invocation());
    verify(mapper, times(2)).findPlatformClaimCandidate(any());
    verify(mapper, times(2)).claim(eq(INVOCATION_ID), eq("worker"), any(), any());
  }

  /** Thread-scoped claims use their dedicated candidate query and preserve a fresh lease marker. */
  @Test
  void claimsThreadScopedCandidate() {
    ToolInvocationMapper mapper = mock(ToolInvocationMapper.class);
    MysqlToolInvocationStore invocationStore = mock(MysqlToolInvocationStore.class);
    DatabasePlatformToolInvocationWorkerStore store =
        new DatabasePlatformToolInvocationWorkerStore(mapper, invocationStore);
    ToolInvocationDO candidate = candidate(ToolInvocationStatus.QUEUED);
    ToolInvocation invocation = invocation();
    when(mapper.findPlatformClaimCandidateForThread(eq(THREAD_ID), any())).thenReturn(candidate);
    when(mapper.claim(eq(INVOCATION_ID), eq("worker"), any(), any())).thenReturn(1);
    when(invocationStore.find(INVOCATION_ID)).thenReturn(Optional.of(invocation));

    ClaimedToolInvocation claimed =
        store.claimDueForThread("worker", THREAD_ID, NOW, LEASE_DURATION).orElseThrow();

    assertFalse(claimed.recoveredLease());
    assertEquals(invocation, claimed.invocation());
    verify(mapper).findPlatformClaimCandidateForThread(eq(THREAD_ID), any());
  }

  /** Missing candidates return empty without attempting a compare-and-set. */
  @Test
  void returnsEmptyWhenNoCandidateExists() {
    ToolInvocationMapper mapper = mock(ToolInvocationMapper.class);
    MysqlToolInvocationStore invocationStore = mock(MysqlToolInvocationStore.class);
    DatabasePlatformToolInvocationWorkerStore store =
        new DatabasePlatformToolInvocationWorkerStore(mapper, invocationStore);

    assertFalse(store.claimDue("worker", NOW, LEASE_DURATION).isPresent());
    assertFalse(store.claimDueForThread("worker", THREAD_ID, NOW, LEASE_DURATION).isPresent());
  }

  /** Bounded contention gives up deterministically instead of spinning forever. */
  @Test
  void stopsAfterBoundedContentionRetries() {
    ToolInvocationMapper mapper = mock(ToolInvocationMapper.class);
    MysqlToolInvocationStore invocationStore = mock(MysqlToolInvocationStore.class);
    DatabasePlatformToolInvocationWorkerStore store =
        new DatabasePlatformToolInvocationWorkerStore(mapper, invocationStore);
    ToolInvocationDO candidate = candidate(ToolInvocationStatus.QUEUED);
    when(mapper.findPlatformClaimCandidate(any())).thenReturn(candidate);
    when(mapper.claim(eq(INVOCATION_ID), eq("worker"), any(), any())).thenReturn(0);

    assertFalse(store.claimDue("worker", NOW, LEASE_DURATION).isPresent());
    verify(mapper, times(64)).findPlatformClaimCandidate(any());
    verify(mapper, times(64)).claim(eq(INVOCATION_ID), eq("worker"), any(), any());
  }

  /** Lease input validation runs before candidate lookup for both global and Thread claims. */
  @Test
  void validatesClaimRequests() {
    ToolInvocationMapper mapper = mock(ToolInvocationMapper.class);
    MysqlToolInvocationStore invocationStore = mock(MysqlToolInvocationStore.class);
    DatabasePlatformToolInvocationWorkerStore store =
        new DatabasePlatformToolInvocationWorkerStore(mapper, invocationStore);

    assertThrows(IllegalArgumentException.class, () -> store.claimDue(" ", NOW, LEASE_DURATION));
    assertThrows(
        IllegalArgumentException.class, () -> store.claimDue("worker", NOW, Duration.ZERO));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.claimDueForThread("worker", 0L, NOW, LEASE_DURATION));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.claimDueForThread(" ", THREAD_ID, NOW, LEASE_DURATION));
    assertThrows(
        IllegalArgumentException.class,
        () -> store.claimDueForThread("worker", THREAD_ID, NOW, Duration.ofSeconds(-1)));
  }

  /** Heartbeat and lookup delegate the durable owner identity without changing the invocation. */
  @Test
  void delegatesHeartbeatAndLookup() {
    ToolInvocationMapper mapper = mock(ToolInvocationMapper.class);
    MysqlToolInvocationStore invocationStore = mock(MysqlToolInvocationStore.class);
    DatabasePlatformToolInvocationWorkerStore store =
        new DatabasePlatformToolInvocationWorkerStore(mapper, invocationStore);
    ToolInvocation invocation = invocation();
    ClaimedToolInvocation claimed = new ClaimedToolInvocation(invocation, false);
    when(mapper.heartbeat(eq(INVOCATION_ID), eq("worker"), any(), any())).thenReturn(1);
    when(invocationStore.find(INVOCATION_ID)).thenReturn(Optional.of(invocation));

    assertTrue(store.heartbeat(claimed, NOW, LEASE_DURATION));
    assertEquals(invocation, store.find(INVOCATION_ID).orElseThrow());
    assertThrows(
        IllegalArgumentException.class, () -> store.heartbeat(claimed, NOW, Duration.ZERO));
  }

  private static ToolInvocationDO candidate(ToolInvocationStatus status) {
    ToolInvocationDO candidate = new ToolInvocationDO();
    candidate.setId(INVOCATION_ID);
    candidate.setStatus(status.name());
    return candidate;
  }

  private static ToolInvocation invocation() {
    return new ToolInvocation(
        INVOCATION_ID,
        THREAD_ID,
        102L,
        0,
        "tool-call",
        "read",
        "1",
        ToolExecutionLocation.PLATFORM,
        null,
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
