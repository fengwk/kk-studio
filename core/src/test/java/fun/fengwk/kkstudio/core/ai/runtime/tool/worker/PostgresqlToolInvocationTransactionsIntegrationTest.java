package fun.fengwk.kkstudio.core.ai.runtime.tool.worker;

import static fun.fengwk.kkstudio.core.ai.runtime.persistence.postgresql.PostgresSchemaSupport.newConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fun.fengwk.kkstudio.core.ai.environment.gateway.EnvironmentReadyListener;
import fun.fengwk.kkstudio.core.ai.runtime.execution.ActivationState;
import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionPromptPreview;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolPermissionState;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ArtifactStore;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ClaimedToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationUpdateOutcome;
import fun.fengwk.kkstudio.harness.tool.ArtifactRef;
import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.ToolSideEffect;
import fun.fengwk.kkstudio.harness.tool.ToolType;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;
import fun.fengwk.kkstudio.harness.tool.schema.ToolParamsSchema;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * PostgreSQL 17 激活驱动 ToolInvocation worker 事务的合约测试：锁顺序 （Thread → ToolInvocation →
 * harness_execution_activation）、claim/renew/release/retry/terminal 激活生命周期、终态写入后的 THREAD
 * 激活重新安排，以及激活存在性约束。
 */
class PostgresqlToolInvocationTransactionsIntegrationTest extends PostgresSpringTestSupport {
  private static final ToolDescriptorJsonCodec DESCRIPTOR_CODEC = new ToolDescriptorJsonCodec();
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Instant BASE = Instant.parse("2026-07-24T00:00:00Z");
  private static final Duration LONG_LEASE = Duration.ofMinutes(5);
  private static final Duration SHORT_LEASE = Duration.ofMillis(50);

  @Autowired private PostgresqlToolInvocationTransactions transactions;
  @Autowired private ArtifactStore artifactStore;
  // 禁用 Gateway 的 READY 配线：持久化激活分发切片负责唤醒。
  @MockitoBean private EnvironmentReadyListener environmentReadyListener;

  private final AtomicLong ids = new AtomicLong(40_000_000L);

  @Test
  void claimQueuedEstablishesRunningClocksAndReschedulesActivation() throws Exception {
    Fixture fixture = newQueued(null, ToolSideEffect.READ_ONLY, 1L);

    ClaimedToolInvocation claimed = claim(fixture, "worker-a", BASE, LONG_LEASE);

    assertFalse(claimed.recoveredLease());
    ToolInvocation invocation = claimed.invocation();
    assertEquals(InvocationStatus.RUNNING, invocation.status());
    assertEquals(fixture.descriptor, invocation.descriptor());
    assertEquals(fixture.executionEpoch, invocation.executionEpoch());
    assertEquals(1, invocation.attempt());
    assertEquals("worker-a", invocation.workerLease().token());
    assertEquals(BASE, invocation.startedAt());
    assertEquals(BASE, invocation.lastActivityAt());
    assertEquals(BASE.plus(fixture.descriptor.timeout()), invocation.deadlineAt());
    assertEquals(BASE.plus(LONG_LEASE), invocation.workerLease().until());

    ToolInvocationDO row = rowFor(fixture.invocationId);
    assertEquals("RUNNING", row.getStatus());
    assertNull(row.getNextAttemptAt());
    assertNull(row.getResultJson());
    assertNull(row.getErrorJson());

    ActivationRow activation = activationFor(fixture.invocationId);
    assertEquals(ExecutionTargetKind.TOOL_INVOCATION, activation.targetKind());
    assertNull(activation.environmentName(), "PLATFORM 激活必须不携带 environment_name");
    assertEquals(BASE.plus(LONG_LEASE), activation.wakeAt());
  }

  @Test
  void claimRoutesEnvironmentActivationByEnvironmentName() throws Exception {
    Fixture fixture = newQueued("env-a", ToolSideEffect.READ_ONLY, 1L);

    ClaimedToolInvocation claimed = claim(fixture, "env-owner", BASE, LONG_LEASE);

    assertFalse(claimed.recoveredLease());
    ActivationRow activation = activationFor(fixture.invocationId);
    assertEquals("env-a", activation.environmentName());
    assertEquals(claimed.invocation().workerLease().until(), activation.wakeAt());
  }

  @Test
  void claimRejectsActivationEnvironmentMismatchWithoutMutatingInvocation() throws Exception {
    Fixture fixture = newQueued("env-a", ToolSideEffect.READ_ONLY, 1L);
    setActivationEnvironment(fixture.invocationId, "env-b");

    assertThrows(
        IllegalStateException.class,
        () -> transactions.claim(fixture.invocationId, "worker-a", LONG_LEASE, BASE));
    assertEquals("QUEUED", rowFor(fixture.invocationId).getStatus());
  }

  @Test
  void claimRequiresDueActivation() throws Exception {
    Fixture fixture = newQueued(null, ToolSideEffect.READ_ONLY, 1L);
    setActivationWakeAt(fixture.invocationId, BASE.plus(Duration.ofMinutes(10)));

    assertTrue(transactions.claim(fixture.invocationId, "worker-a", LONG_LEASE, BASE).isEmpty());
    assertEquals("QUEUED", rowFor(fixture.invocationId).getStatus());
  }

  @Test
  void claimReturnsEmptyWhenActivationRowIsAbsent() throws Exception {
    Fixture fixture = newQueued(null, ToolSideEffect.READ_ONLY, 1L);
    deleteActivation(fixture.invocationId);

    assertTrue(transactions.claim(fixture.invocationId, "worker-a", LONG_LEASE, BASE).isEmpty());
    assertEquals("QUEUED", rowFor(fixture.invocationId).getStatus());
  }

  @Test
  void openInteractionBlocksClaimUntilInteractionIsResolved() throws Exception {
    Fixture fixture = newQueued(null, ToolSideEffect.READ_ONLY, 1L);
    long interactionId = insertOpenInteraction(fixture.invocationId);

    assertTrue(transactions.claim(fixture.invocationId, "worker-a", LONG_LEASE, BASE).isEmpty());

    resolveInteraction(interactionId);
    assertTrue(transactions.claim(fixture.invocationId, "worker-a", LONG_LEASE, BASE).isPresent());
  }

  @Test
  void activeRunningAndFutureRetryAreNotClaimable() throws Exception {
    Fixture active = newQueued(null, ToolSideEffect.READ_ONLY, 1L);
    Fixture futureRetry = newQueued(null, ToolSideEffect.IDEMPOTENT, 1L);
    claim(active, "active-owner", BASE, LONG_LEASE);
    ClaimedToolInvocation retryClaim = claim(futureRetry, "retry-owner", BASE, LONG_LEASE);
    transactions.scheduleRetry(
        retryClaim, BASE.plusSeconds(30), BASE.plusSeconds(1), BASE.plusSeconds(1));
    setActivationWakeAt(futureRetry.invocationId, BASE.plusSeconds(30));

    assertTrue(
        transactions
            .claim(active.invocationId, "active-owner", LONG_LEASE, BASE.plusSeconds(2))
            .isEmpty());
    assertTrue(
        transactions
            .claim(futureRetry.invocationId, "retry-owner", LONG_LEASE, BASE.plusSeconds(2))
            .isEmpty());
    assertTrue(
        transactions
            .claim(futureRetry.invocationId, "retry-owner", LONG_LEASE, BASE.plusSeconds(31))
            .isPresent());
  }

  @Test
  void expiredRunningRecoveryPreservesAttemptAndExecutionClocks() throws Exception {
    Fixture fixture = newQueued(null, ToolSideEffect.READ_ONLY, 1L);
    ClaimedToolInvocation first = claim(fixture, "old-owner", BASE, SHORT_LEASE);

    ClaimedToolInvocation recovered =
        transactions
            .claim(fixture.invocationId, "new-owner", LONG_LEASE, BASE.plusMillis(80))
            .orElseThrow();

    assertTrue(recovered.recoveredLease());
    assertEquals(1, recovered.invocation().attempt());
    assertEquals(first.invocation().startedAt(), recovered.invocation().startedAt());
    assertEquals(first.invocation().deadlineAt(), recovered.invocation().deadlineAt());
    assertEquals(first.invocation().lastActivityAt(), recovered.invocation().lastActivityAt());
    assertEquals("new-owner", recovered.invocation().workerLease().token());
  }

  @Test
  void retryClaimIncrementsAttemptAndPreservesDeadline() throws Exception {
    Fixture fixture = newQueued(null, ToolSideEffect.IDEMPOTENT, 1L);
    ClaimedToolInvocation first = claim(fixture, "worker-a", BASE, LONG_LEASE);
    Instant deadline = first.invocation().deadlineAt();
    assertEquals(
        ToolInvocationUpdateOutcome.APPLIED,
        transactions.scheduleRetry(
            first, BASE.plusSeconds(2), BASE.plusSeconds(1), BASE.plusSeconds(1)));
    setActivationWakeAt(fixture.invocationId, BASE.plusSeconds(2));

    ClaimedToolInvocation second =
        transactions
            .claim(fixture.invocationId, "worker-b", LONG_LEASE, BASE.plusSeconds(3))
            .orElseThrow();

    assertFalse(second.recoveredLease());
    assertEquals(2, second.invocation().attempt());
    assertEquals(BASE, second.invocation().startedAt());
    assertEquals(deadline, second.invocation().deadlineAt());
    assertEquals(BASE.plusSeconds(3), second.invocation().lastActivityAt());
    assertEquals("worker-b", second.invocation().workerLease().token());
  }

  @Test
  void concurrentClaimHasExactlyOneWinner() throws Exception {
    Fixture fixture = newQueued(null, ToolSideEffect.READ_ONLY, 1L);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<Optional<ClaimedToolInvocation>> first =
          pool.submit(
              () -> {
                start.await();
                return transactions.claim(fixture.invocationId, "worker-a", LONG_LEASE, BASE);
              });
      Future<Optional<ClaimedToolInvocation>> second =
          pool.submit(
              () -> {
                start.await();
                return transactions.claim(fixture.invocationId, "worker-b", LONG_LEASE, BASE);
              });
      start.countDown();

      List<Optional<ClaimedToolInvocation>> outcomes =
          List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
      assertEquals(1, outcomes.stream().filter(Optional::isPresent).count());
      String durableToken = rowFor(fixture.invocationId).getWorkerToken();
      assertTrue(durableToken.equals("worker-a") || durableToken.equals("worker-b"));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void renewNeverShortensLeaseAndReschedulesActivation() throws Exception {
    Fixture fixture = newQueued(null, ToolSideEffect.READ_ONLY, 1L);
    ClaimedToolInvocation claimed = claim(fixture, "worker-a", BASE, LONG_LEASE);
    Instant originalUntil = claimed.invocation().workerLease().until();
    setActivationWakeAt(fixture.invocationId, BASE.plus(Duration.ofHours(1)));

    assertEquals(
        ToolInvocationUpdateOutcome.APPLIED,
        transactions.renew(claimed, Duration.ofMinutes(1), BASE.plusSeconds(1)));
    assertEquals(originalUntil, rowFor(fixture.invocationId).getWorkerUntil().toInstant());
    assertEquals(originalUntil, activationFor(fixture.invocationId).wakeAt());

    assertEquals(
        ToolInvocationUpdateOutcome.APPLIED,
        transactions.renew(claimed, LONG_LEASE, BASE.plus(Duration.ofMinutes(2))));
    assertEquals(
        BASE.plus(Duration.ofMinutes(7)),
        rowFor(fixture.invocationId).getWorkerUntil().toInstant());
    assertEquals(BASE.plus(Duration.ofMinutes(7)), activationFor(fixture.invocationId).wakeAt());
  }

  @Test
  void ownedMutationRejectsActivationEnvironmentMismatch() throws Exception {
    Fixture fixture = newQueued("env-a", ToolSideEffect.READ_ONLY, 1L);
    ClaimedToolInvocation claimed = claim(fixture, "worker-a", BASE, LONG_LEASE);
    setActivationEnvironment(fixture.invocationId, "env-b");

    assertThrows(
        IllegalStateException.class,
        () -> transactions.renew(claimed, LONG_LEASE, BASE.plusSeconds(1)));
  }

  @Test
  void activityIsMonotonicAndFencedByLeaseOwnership() throws Exception {
    Fixture fixture = newQueued(null, ToolSideEffect.READ_ONLY, 1L);
    ClaimedToolInvocation claimed = claim(fixture, "worker-a", BASE, LONG_LEASE);

    assertEquals(
        ToolInvocationUpdateOutcome.APPLIED,
        transactions.recordActivity(claimed, BASE.minusSeconds(1), BASE.plusSeconds(1)));
    assertEquals(BASE, rowFor(fixture.invocationId).getLastActivityAt().toInstant());

    assertEquals(
        ToolInvocationUpdateOutcome.APPLIED,
        transactions.recordActivity(claimed, BASE.plusSeconds(2), BASE.plusSeconds(2)));
    assertEquals(BASE.plusSeconds(2), rowFor(fixture.invocationId).getLastActivityAt().toInstant());

    setWorkerToken(fixture.invocationId, "worker-b");
    assertEquals(
        ToolInvocationUpdateOutcome.LOST_OWNERSHIP,
        transactions.recordActivity(claimed, BASE.plusSeconds(3), BASE.plusSeconds(3)));
  }

  @Test
  void scheduleRetryReschedulesActivationWithoutWakingThread() throws Exception {
    Fixture fixture = newQueued(null, ToolSideEffect.IDEMPOTENT, 1L);
    ClaimedToolInvocation claimed = claim(fixture, "worker-a", BASE, LONG_LEASE);

    assertEquals(
        ToolInvocationUpdateOutcome.APPLIED,
        transactions.scheduleRetry(
            claimed, BASE.plusSeconds(3), BASE.plusSeconds(2), BASE.plusSeconds(2)));

    ToolInvocationDO row = rowFor(fixture.invocationId);
    assertEquals("RETRY_WAIT", row.getStatus());
    assertNull(row.getWorkerToken());
    assertNull(row.getWorkerUntil());
    assertEquals(BASE.plusSeconds(3), row.getNextAttemptAt().toInstant());
    assertEquals(BASE.plusSeconds(2), row.getLastActivityAt().toInstant());
    assertFalse(threadRunnable(fixture.threadId));
    assertEquals(BASE.plusSeconds(3), activationFor(fixture.invocationId).wakeAt());
  }

  @Test
  void successfulTerminalPersistsResultDeletesToolActivationAndMarksThreadRunnableAtomically()
      throws Exception {
    Fixture fixture = newQueued(null, ToolSideEffect.READ_ONLY, 1L);
    ClaimedToolInvocation claimed = claim(fixture, "worker-a", BASE, LONG_LEASE);
    ToolResult result = result(fixture.toolCallId, "done");

    assertEquals(
        ToolInvocationUpdateOutcome.APPLIED,
        transactions.completeSuccess(
            claimed, () -> result, BASE.plusSeconds(2), BASE.plusSeconds(1)));

    ToolInvocationDO row = rowFor(fixture.invocationId);
    assertEquals("SUCCEEDED", row.getStatus());
    assertNull(row.getWorkerToken());
    assertNull(row.getErrorJson());
    assertNotNull(row.getResultJson());
    assertEquals(BASE.plusSeconds(2), row.getLastActivityAt().toInstant());
    assertEquals(BASE.plusSeconds(2), row.getFinishedAt().toInstant());
    assertTrue(threadRunnable(fixture.threadId));
    assertEquals(result, ToolInvocationRowConverter.toAggregate(row).result());
    assertTrue(toolActivationAbsent(fixture.invocationId));
    ActivationRow threadActivation = threadActivationFor(fixture.threadId);
    assertEquals(BASE.plusSeconds(1), threadActivation.wakeAt());
  }

  @Test
  void terminalEnvironmentToolActivatesNextRouteHead() throws Exception {
    Fixture first = newQueued("env-a", ToolSideEffect.READ_ONLY, 1L);
    Fixture second = newQueued("env-a", ToolSideEffect.READ_ONLY, 1L);
    setInvocationCreatedAt(first.invocationId, BASE);
    setInvocationCreatedAt(second.invocationId, BASE.plusSeconds(1));
    setActivationState(second.invocationId, ActivationState.PARKED);

    ClaimedToolInvocation claimed = claim(first, "env-worker", BASE, LONG_LEASE);
    assertEquals(
        ToolInvocationUpdateOutcome.APPLIED,
        transactions.completeSuccess(
            claimed, () -> result(first.toolCallId, "done"), BASE, BASE.plusSeconds(1)));

    assertTrue(toolActivationAbsent(first.invocationId));
    ActivationRow next = activationFor(second.invocationId);
    assertEquals(
        ActivationState.SCHEDULED,
        next.activationState(),
        "terminal completion must enable the next environment head");
    assertEquals(BASE.plusSeconds(1), next.wakeAt());
    assertEquals("QUEUED", rowFor(second.invocationId).getStatus());
  }

  @Test
  void failureUnknownAndCancellationPersistTheirStrictPayloadShapes() throws Exception {
    Fixture failure = newQueued(null, ToolSideEffect.READ_ONLY, 1L);
    Fixture unknown = newQueued(null, ToolSideEffect.READ_ONLY, 1L);
    Fixture cancelled = newQueued(null, ToolSideEffect.READ_ONLY, 1L);
    ClaimedToolInvocation failureClaim = claim(failure, "failure-owner", BASE, LONG_LEASE);
    ClaimedToolInvocation unknownClaim = claim(unknown, "unknown-owner", BASE, LONG_LEASE);
    ClaimedToolInvocation cancelledClaim = claim(cancelled, "cancel-owner", BASE, LONG_LEASE);

    assertEquals(
        ToolInvocationUpdateOutcome.APPLIED,
        transactions.completeFailure(
            failureClaim,
            new ToolInvocationError("EXECUTION_FAILED", "boom"),
            BASE,
            BASE.plusSeconds(1)));
    assertEquals(
        ToolInvocationUpdateOutcome.APPLIED,
        transactions.completeUnknown(
            unknownClaim,
            new ToolInvocationError("LEASE_EXPIRED", "unknown"),
            BASE,
            BASE.plusSeconds(1)));
    assertEquals(
        ToolInvocationUpdateOutcome.APPLIED,
        transactions.completeCancelled(cancelledClaim, BASE, BASE.plusSeconds(1)));

    ToolInvocationDO failedRow = rowFor(failure.invocationId);
    assertEquals("FAILED", failedRow.getStatus());
    assertNotNull(failedRow.getErrorJson());
    assertNull(failedRow.getResultJson());
    ToolInvocationDO unknownRow = rowFor(unknown.invocationId);
    assertEquals("UNKNOWN", unknownRow.getStatus());
    assertNotNull(unknownRow.getErrorJson());
    assertNull(unknownRow.getResultJson());
    ToolInvocationDO cancelledRow = rowFor(cancelled.invocationId);
    assertEquals("CANCELLED", cancelledRow.getStatus());
    assertNull(cancelledRow.getErrorJson());
    assertNull(cancelledRow.getResultJson());
    assertTrue(threadRunnable(failure.threadId));
    assertTrue(threadRunnable(unknown.threadId));
    assertTrue(threadRunnable(cancelled.threadId));
    assertTrue(toolActivationAbsent(failure.invocationId));
    assertTrue(toolActivationAbsent(unknown.invocationId));
    assertTrue(toolActivationAbsent(cancelled.invocationId));
  }

  @Test
  void terminalMutationWithoutOwnedActivationRollsBack() throws Exception {
    Fixture fixture = newQueued(null, ToolSideEffect.READ_ONLY, 1L);
    ClaimedToolInvocation claimed = claim(fixture, "worker-a", BASE, LONG_LEASE);
    deleteActivation(fixture.invocationId);

    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.completeSuccess(
                claimed, () -> result(fixture.toolCallId, "done"), BASE, BASE.plusSeconds(1)));
    assertEquals("RUNNING", rowFor(fixture.invocationId).getStatus());
    assertFalse(threadRunnable(fixture.threadId));
  }

  @Test
  void renewMutationWithoutOwnedActivationRollsBack() throws Exception {
    Fixture fixture = newQueued(null, ToolSideEffect.READ_ONLY, 1L);
    ClaimedToolInvocation claimed = claim(fixture, "worker-a", BASE, LONG_LEASE);
    deleteActivation(fixture.invocationId);

    assertThrows(
        IllegalStateException.class,
        () -> transactions.renew(claimed, LONG_LEASE, BASE.plusSeconds(1)));
  }

  @Test
  void tokenAttemptEpochAndExpiredLeaseAllFenceTerminalWrites() throws Exception {
    Fixture token = newQueued(null, ToolSideEffect.READ_ONLY, 1L);
    Fixture attempt = newQueued(null, ToolSideEffect.READ_ONLY, 1L);
    Fixture epoch = newQueued(null, ToolSideEffect.READ_ONLY, 1L);
    Fixture expired = newQueued(null, ToolSideEffect.READ_ONLY, 1L);
    ClaimedToolInvocation tokenClaim = claim(token, "token-owner", BASE, LONG_LEASE);
    ClaimedToolInvocation attemptClaim = claim(attempt, "attempt-owner", BASE, LONG_LEASE);
    ClaimedToolInvocation epochClaim = claim(epoch, "epoch-owner", BASE, LONG_LEASE);
    ClaimedToolInvocation expiredClaim = claim(expired, "expired-owner", BASE, SHORT_LEASE);
    setWorkerToken(token.invocationId, "other-owner");
    setAttempt(attempt.invocationId, 2);
    setThreadEpoch(epoch.threadId, 2L);

    assertEquals(
        ToolInvocationUpdateOutcome.LOST_OWNERSHIP,
        transactions.completeSuccess(
            tokenClaim, () -> result(token.toolCallId, "late"), BASE, BASE));
    assertEquals(
        ToolInvocationUpdateOutcome.LOST_OWNERSHIP,
        transactions.completeSuccess(
            attemptClaim, () -> result(attempt.toolCallId, "late"), BASE, BASE));
    assertEquals(
        ToolInvocationUpdateOutcome.LOST_OWNERSHIP,
        transactions.completeSuccess(
            epochClaim, () -> result(epoch.toolCallId, "late"), BASE, BASE));
    assertEquals(
        ToolInvocationUpdateOutcome.LOST_OWNERSHIP,
        transactions.completeSuccess(
            expiredClaim, () -> result(expired.toolCallId, "late"), BASE, BASE.plusMillis(80)));

    assertEquals("RUNNING", rowFor(token.invocationId).getStatus());
    assertEquals("RUNNING", rowFor(attempt.invocationId).getStatus());
    assertEquals("RUNNING", rowFor(epoch.invocationId).getStatus());
    assertEquals("RUNNING", rowFor(expired.invocationId).getStatus());
  }

  @Test
  void lostOwnershipDoesNotEvaluateLazyTerminalResult() throws Exception {
    Fixture fixture = newQueued(null, ToolSideEffect.READ_ONLY, 1L);
    ClaimedToolInvocation claimed = claim(fixture, "worker-a", BASE, LONG_LEASE);
    setWorkerToken(fixture.invocationId, "other-worker");
    AtomicInteger supplierCalls = new AtomicInteger();

    assertEquals(
        ToolInvocationUpdateOutcome.LOST_OWNERSHIP,
        transactions.completeSuccess(
            claimed,
            () -> {
              supplierCalls.incrementAndGet();
              return result(fixture.toolCallId, "late");
            },
            BASE,
            BASE));
    assertEquals(0, supplierCalls.get());
  }

  @Test
  void executionEpochMismatchPreventsClaim() throws Exception {
    Fixture fixture = newQueued(null, ToolSideEffect.READ_ONLY, 1L);
    setThreadEpoch(fixture.threadId, 2L);

    assertTrue(transactions.claim(fixture.invocationId, "worker-a", LONG_LEASE, BASE).isEmpty());
    assertEquals("QUEUED", rowFor(fixture.invocationId).getStatus());
  }

  @Test
  void successfulResultMustMatchFrozenToolCallId() throws Exception {
    Fixture fixture = newQueued(null, ToolSideEffect.READ_ONLY, 1L);
    ClaimedToolInvocation claimed = claim(fixture, "worker-a", BASE, LONG_LEASE);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            transactions.completeSuccess(
                claimed, () -> result("different-call", "invalid"), BASE, BASE.plusSeconds(1)));

    assertEquals("RUNNING", rowFor(fixture.invocationId).getStatus());
    assertFalse(threadRunnable(fixture.threadId));
  }

  @Test
  void terminalUpdateRollsBackWhenOwningThreadCannotBeMarkedRunnable() throws Exception {
    Fixture fixture = newQueued(null, ToolSideEffect.READ_ONLY, 1L);
    ClaimedToolInvocation claimed = claim(fixture, "worker-a", BASE, LONG_LEASE);
    suppressRunnableUpdates();
    AtomicReference<ArtifactRef> artifact = new AtomicReference<>();

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () ->
                transactions.completeSuccess(
                    claimed,
                    () -> {
                      artifact.set(
                          artifactStore.save(
                              "text/plain", "utf-8", "artifact".getBytes(StandardCharsets.UTF_8)));
                      return result(fixture.toolCallId, "done");
                    },
                    BASE,
                    BASE.plusSeconds(1)));
    assertTrue(error.getMessage().contains("cannot mark owning thread runnable"));

    ToolInvocationDO row = rowFor(fixture.invocationId);
    assertEquals("RUNNING", row.getStatus());
    assertEquals("worker-a", row.getWorkerToken());
    assertNull(row.getResultJson());
    assertFalse(threadRunnable(fixture.threadId));
    assertNotNull(artifact.get());
    assertTrue(artifactStore.find(artifact.get().artifactId()).isEmpty());
  }

  @Test
  void validatesWorkerArgumentsBeforeSqlMutation() throws Exception {
    Fixture fixture = newQueued(null, ToolSideEffect.READ_ONLY, 1L);

    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.claim(fixture.invocationId, " ", LONG_LEASE, BASE));
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.claim(fixture.invocationId, "worker-a", Duration.ZERO, BASE));
    assertEquals("QUEUED", rowFor(fixture.invocationId).getStatus());
  }

  // ---- durable Tool permission state machine ----

  @Test
  void persistPermissionAllowedFlipsStateAndOverwritesFinalPlan() throws Exception {
    Fixture fixture = newPendingQueued(null, ToolSideEffect.READ_ONLY, 1L);
    ClaimedToolInvocation claimed = claim(fixture, "worker-a", BASE, LONG_LEASE);
    ToolBinding finalBinding = ToolBinding.of(fixture.descriptor);
    String finalArgs = "{\"rewritten\":true}";

    assertEquals(
        ToolInvocationUpdateOutcome.APPLIED,
        transactions.persistPermissionAllowed(
            claimed, finalBinding, finalArgs, BASE.plusSeconds(1)));

    ToolInvocationDO row = rowFor(fixture.invocationId);
    assertEquals("RUNNING", row.getStatus());
    assertEquals("ALLOWED", row.getPermissionState());
    assertEquals(JSON.readTree(finalArgs), JSON.readTree(row.getArgumentsJson()));
    assertFalse(row.getYoloEnabled(), "yolo_enabled stays as materialization default");
  }

  @Test
  void persistPermissionAllowedRejectsRouteChangesWithoutMutatingTheInvocation() throws Exception {
    Fixture fixture = newPendingQueued("env-a", ToolSideEffect.READ_ONLY, 1L);
    ClaimedToolInvocation claimed = claim(fixture, "env-owner", BASE, LONG_LEASE);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            transactions.persistPermissionAllowed(
                claimed, ToolBinding.of(fixture.descriptor, "env-b"), "{\"changed\":true}", BASE));

    ToolInvocationDO row = rowFor(fixture.invocationId);
    assertEquals("RUNNING", row.getStatus());
    assertEquals("PENDING", row.getPermissionState());
    assertEquals("env-a", row.getEnvironmentName());
    assertEquals("{}", row.getArgumentsJson());
    assertEquals(
        claimed.invocation().workerLease().until(), activationFor(fixture.invocationId).wakeAt());
  }

  @Test
  void awaitPermissionCreatesOpenInteractionAndParksActivation() throws Exception {
    Fixture fixture = newPendingQueued(null, ToolSideEffect.READ_ONLY, 1L);
    ClaimedToolInvocation claimed = claim(fixture, "worker-a", BASE, LONG_LEASE);
    PermissionPromptPreview prompt =
        new PermissionPromptPreview("platformTool", "/work", "{\"k\":\"v\"}");

    assertEquals(
        ToolInvocationUpdateOutcome.APPLIED,
        transactions.awaitPermission(
            claimed,
            ToolBinding.of(fixture.descriptor),
            "{\"k\":\"v\"}",
            prompt,
            BASE.plusSeconds(1)));

    ToolInvocationDO row = rowFor(fixture.invocationId);
    assertEquals("WAITING_INTERACTION", row.getStatus());
    assertEquals("ASKED", row.getPermissionState());
    assertNull(row.getWorkerToken());
    assertNull(row.getWorkerUntil());
    assertNull(row.getStartedAt());
    assertNull(row.getDeadlineAt());
    assertNull(row.getLastActivityAt());
    assertNull(row.getResultJson());
    assertNull(row.getErrorJson());

    // 该 Tool 只关联恰好一条 OPEN Tool 权限 Interaction。
    assertEquals(1, countOpenInteractionsForTool(fixture.invocationId));
    JsonNode request = JSON.readTree(onlyOpenInteractionRequestJson(fixture.invocationId));
    assertEquals(fixture.invocationId, request.required("invocationId").asLong());
    assertEquals(fixture.threadId, request.required("threadId").asLong());
    assertEquals("platformTool", request.required("tool").asText());
    assertEquals("/work", request.required("workdir").asText());
    assertEquals("{\"k\":\"v\"}", request.required("arguments").asText());

    // 激活记录已 PARKED，且 PLATFORM Tool 的 environment_name 仍为 NULL。
    ActivationRow activation = activationFor(fixture.invocationId);
    assertEquals(
        ActivationState.PARKED, activation.activationState(), "ASK activation must be parked");
    assertNull(activation.environmentName(), "PLATFORM activation carries a null environment");
  }

  @Test
  void awaitPermissionMissingActivationRollsBackAndLeavesNoOpenInteraction() throws Exception {
    Fixture fixture = newPendingQueued(null, ToolSideEffect.READ_ONLY, 1L);
    ClaimedToolInvocation claimed = claim(fixture, "worker-a", BASE, LONG_LEASE);
    deleteActivation(fixture.invocationId);
    PermissionPromptPreview prompt = new PermissionPromptPreview("platformTool", "/work", "{}");

    assertThrows(
        IllegalStateException.class,
        () ->
            transactions.awaitPermission(
                claimed, ToolBinding.of(fixture.descriptor), "{}", prompt, BASE.plusSeconds(1)));

    // 没有 Interaction，也没有记录变更：整个 awaitPermission 都会回滚。
    assertEquals(0, countOpenInteractionsForTool(fixture.invocationId));
    assertEquals("RUNNING", rowFor(fixture.invocationId).getStatus());
    assertEquals("PENDING", rowFor(fixture.invocationId).getPermissionState());
  }

  @Test
  void awaitPermissionOnPlatformActivationWithNullEnvironmentParks() throws Exception {
    Fixture fixture = newPendingQueued(null, ToolSideEffect.READ_ONLY, 1L);
    ClaimedToolInvocation claimed = claim(fixture, "worker-a", BASE, LONG_LEASE);
    PermissionPromptPreview prompt = new PermissionPromptPreview("platformTool", "/work", "{}");

    assertEquals(
        ToolInvocationUpdateOutcome.APPLIED,
        transactions.awaitPermission(
            claimed, ToolBinding.of(fixture.descriptor), "{}", prompt, BASE.plusSeconds(1)));

    ActivationRow activation = activationFor(fixture.invocationId);
    assertEquals(
        ActivationState.PARKED, activation.activationState(), "PLATFORM ASK must be parked");
    assertNull(activation.environmentName());
  }

  @Test
  void awaitPermissionRejectsRouteChange() throws Exception {
    Fixture fixture = newPendingQueued("env-a", ToolSideEffect.READ_ONLY, 1L);
    ClaimedToolInvocation claimed = claim(fixture, "env-worker", BASE, LONG_LEASE);
    PermissionPromptPreview prompt = new PermissionPromptPreview("environmentTool", "/work", "{}");
    // 最终 binding 修改了 environment，因此必须被拒绝。
    ToolBinding badBinding = ToolBinding.of(fixture.descriptor, "env-b");

    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.awaitPermission(claimed, badBinding, "{}", prompt, BASE.plusSeconds(1)));
  }

  @Test
  void denyPermissionTerminatesWakesThreadAndActivatesNextEnvironmentHead() throws Exception {
    Fixture first = newPendingQueued("env-a", ToolSideEffect.READ_ONLY, 1L);
    Fixture second = newQueued("env-a", ToolSideEffect.READ_ONLY, 1L);
    setInvocationCreatedAt(first.invocationId, BASE);
    setInvocationCreatedAt(second.invocationId, BASE.plusSeconds(1));
    setActivationState(second.invocationId, ActivationState.PARKED);

    ClaimedToolInvocation claimed = claim(first, "env-worker", BASE, LONG_LEASE);
    assertEquals(
        ToolInvocationUpdateOutcome.APPLIED,
        transactions.denyPermission(claimed, BASE.plusSeconds(1)));

    ToolInvocationDO deniedRow = rowFor(first.invocationId);
    assertEquals("FAILED", deniedRow.getStatus());
    assertEquals("DENIED", deniedRow.getPermissionState());
    assertNotNull(deniedRow.getErrorJson());
    assertNull(deniedRow.getWorkerToken());
    assertNotNull(deniedRow.getFinishedAt());
    // 时钟保持不变：deny 没有创建新的 startedAt/deadlineAt。
    assertNotNull(deniedRow.getStartedAt());
    assertNotNull(deniedRow.getDeadlineAt());

    assertTrue(toolActivationAbsent(first.invocationId), "deny must delete the tool activation");
    assertTrue(threadRunnable(first.threadId), "deny must mark the owning thread runnable");

    // 同一事务必须激活下一个 Environment 队头。
    ActivationRow nextActivation = activationFor(second.invocationId);
    assertEquals(
        ActivationState.SCHEDULED,
        nextActivation.activationState(),
        "deny must activate the next environment head");
    assertEquals("env-a", nextActivation.environmentName());
  }

  @Test
  void denyPermissionRollsBackWhenItCannotWakeTheOwningThread() throws Exception {
    Fixture fixture = newPendingQueued(null, ToolSideEffect.READ_ONLY, 1L);
    ClaimedToolInvocation claimed = claim(fixture, "worker-a", BASE, LONG_LEASE);
    suppressRunnableUpdates();

    assertThrows(
        IllegalStateException.class,
        () -> transactions.denyPermission(claimed, BASE.plusSeconds(1)));

    ToolInvocationDO row = rowFor(fixture.invocationId);
    assertEquals("RUNNING", row.getStatus());
    assertEquals("PENDING", row.getPermissionState());
    assertEquals("worker-a", row.getWorkerToken());
    assertFalse(threadRunnable(fixture.threadId));
    assertFalse(
        toolActivationAbsent(fixture.invocationId), "activation delete must roll back with denial");
  }

  @Test
  void concurrentPermissionRequestsHaveOneWinnerAndOneDurableOpenInteraction() throws Exception {
    Fixture fixture = newPendingQueued(null, ToolSideEffect.READ_ONLY, 1L);
    ClaimedToolInvocation claimed = claim(fixture, "worker-a", BASE, LONG_LEASE);
    PermissionPromptPreview prompt = new PermissionPromptPreview("platformTool", "/work", "{}");
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<ToolInvocationUpdateOutcome> first =
          pool.submit(
              () -> {
                start.await();
                return transactions.awaitPermission(
                    claimed, ToolBinding.of(fixture.descriptor), "{}", prompt, BASE.plusSeconds(1));
              });
      Future<ToolInvocationUpdateOutcome> second =
          pool.submit(
              () -> {
                start.await();
                return transactions.awaitPermission(
                    claimed, ToolBinding.of(fixture.descriptor), "{}", prompt, BASE.plusSeconds(1));
              });
      start.countDown();

      List<ToolInvocationUpdateOutcome> outcomes =
          List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
      assertEquals(
          1,
          outcomes.stream()
              .filter(outcome -> outcome == ToolInvocationUpdateOutcome.APPLIED)
              .count());
      assertEquals(
          1,
          outcomes.stream()
              .filter(outcome -> outcome == ToolInvocationUpdateOutcome.LOST_OWNERSHIP)
              .count());
    } finally {
      pool.shutdownNow();
    }

    ToolInvocationDO row = rowFor(fixture.invocationId);
    assertEquals("WAITING_INTERACTION", row.getStatus());
    assertEquals("ASKED", row.getPermissionState());
    assertEquals(1, countOpenInteractionsForTool(fixture.invocationId));
    assertEquals(ActivationState.PARKED, activationFor(fixture.invocationId).activationState());
  }

  @Test
  void pendingExpiredLeaseRecoverResetsRowToQueuedAndReschedulesActivation() throws Exception {
    Fixture fixture = newPendingQueued(null, ToolSideEffect.READ_ONLY, 1L);
    // 先以极短 lease 执行第一次 claim，再在 lease 过期后执行第二次 claim。
    ClaimedToolInvocation first = claim(fixture, "old-worker", BASE, SHORT_LEASE);
    Instant before = BASE.plusMillis(100);
    // lease 已过期；第二次 claim 应进入 PENDING 恢复路径。
    Optional<ClaimedToolInvocation> second =
        transactions.claim(fixture.invocationId, "new-worker", LONG_LEASE, before);
    assertTrue(second.isEmpty(), "PENDING recovery must return Optional.empty()");
    // 记录恢复为初始 QUEUED + PENDING，激活重新安排到 {@code before}。
    ToolInvocationDO row = rowFor(fixture.invocationId);
    assertEquals("QUEUED", row.getStatus());
    assertEquals("PENDING", row.getPermissionState());
    assertNull(row.getWorkerToken());
    assertNull(row.getStartedAt());
    assertNull(row.getDeadlineAt());
    assertNull(row.getLastActivityAt());
    ActivationRow activation = activationFor(fixture.invocationId);
    assertEquals(before, activation.wakeAt());
  }

  private ClaimedToolInvocation claim(
      Fixture fixture, String token, Instant now, Duration leaseDuration) {
    return transactions.claim(fixture.invocationId, token, leaseDuration, now).orElseThrow();
  }

  private Fixture newQueued(String environmentName, ToolSideEffect sideEffect, long executionEpoch)
      throws SQLException {
    return newQueued(environmentName, sideEffect, executionEpoch, ToolPermissionState.ALLOWED);
  }

  private Fixture newPendingQueued(
      String environmentName, ToolSideEffect sideEffect, long executionEpoch) throws SQLException {
    return newQueued(environmentName, sideEffect, executionEpoch, ToolPermissionState.PENDING);
  }

  private Fixture newQueued(
      String environmentName,
      ToolSideEffect sideEffect,
      long executionEpoch,
      ToolPermissionState permissionState)
      throws SQLException {
    long sessionId = ids.incrementAndGet();
    long threadId = ids.incrementAndGet();
    long rootEntryId = ids.incrementAndGet();
    long assistantEntryId = ids.incrementAndGet();
    long modelInvocationId = ids.incrementAndGet();
    long invocationId = ids.incrementAndGet();
    String toolCallId = "call-" + invocationId;
    ToolDescriptor descriptor = descriptor(environmentName, sideEffect);

    try (Connection connection = newConnection()) {
      boolean previousAutoCommit = connection.getAutoCommit();
      connection.setAutoCommit(false);
      try {
        try (PreparedStatement statement =
            connection.prepareStatement(
                "insert into harness_session (id, title, created_at)"
                    + " values (?, 'fixture', current_timestamp)")) {
          statement.setLong(1, sessionId);
          statement.executeUpdate();
        }
        try (PreparedStatement statement =
            connection.prepareStatement(
                "insert into harness_thread (id, head_entry_id, input_sequence,"
                    + " runnable, execution_epoch, created_at, updated_at)"
                    + " values (?, ?, 0, false, ?, current_timestamp, current_timestamp)")) {
          statement.setLong(1, threadId);
          statement.setLong(2, rootEntryId);
          statement.setLong(3, executionEpoch);
          statement.executeUpdate();
        }
        try (PreparedStatement statement =
            connection.prepareStatement(
                "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
                    + " created_at) values (?, ?, null, 'ROOT', '{}'::jsonb, current_timestamp)")) {
          statement.setLong(1, rootEntryId);
          statement.setLong(2, sessionId);
          statement.executeUpdate();
        }
        try (PreparedStatement statement =
            connection.prepareStatement(
                "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
                    + " created_at) values (?, ?, ?, 'MESSAGE', '{}'::jsonb, current_timestamp)")) {
          statement.setLong(1, assistantEntryId);
          statement.setLong(2, sessionId);
          statement.setLong(3, rootEntryId);
          statement.executeUpdate();
        }
        try (PreparedStatement statement =
            connection.prepareStatement(
                "insert into harness_model_invocation (id, thread_id, source_head_entry_id,"
                    + " execution_epoch, request, status, attempt)"
                    + " values (?, ?, ?, ?, '{\"model\":\"stub\"}'::jsonb, 'QUEUED', 1)")) {
          statement.setLong(1, modelInvocationId);
          statement.setLong(2, threadId);
          statement.setLong(3, rootEntryId);
          statement.setLong(4, executionEpoch);
          statement.executeUpdate();
        }
        try (PreparedStatement statement =
            connection.prepareStatement(
                "insert into harness_tool_invocation (id, thread_id, session_id,"
                    + " assistant_entry_id, model_invocation_id, ordinal, tool_call_id, descriptor, arguments,"
                    + " environment_name, execution_epoch, status, attempt, permission_state,"
                    + " yolo_enabled, created_at)"
                    + " values (?, ?, ?, ?, ?, 0, ?, cast(? as jsonb), '{}'::jsonb, ?, ?,"
                    + " 'QUEUED', 1, ?, false, ?)")) {
          statement.setLong(1, invocationId);
          statement.setLong(2, threadId);
          statement.setLong(3, sessionId);
          statement.setLong(4, assistantEntryId);
          statement.setLong(5, modelInvocationId);
          statement.setString(6, toolCallId);
          statement.setString(7, DESCRIPTOR_CODEC.encode(descriptor));
          statement.setString(8, environmentName);
          statement.setLong(9, executionEpoch);
          statement.setString(10, permissionState.name());
          statement.setObject(11, offset(BASE));
          statement.executeUpdate();
        }
        try (PreparedStatement statement =
            connection.prepareStatement(
                "insert into harness_execution_activation"
                    + " (target_kind, target_id, environment_name, activation_state, wake_at)"
                    + " values ('TOOL_INVOCATION', ?, ?, 'SCHEDULED', ?)")) {
          statement.setLong(1, invocationId);
          statement.setString(2, environmentName);
          statement.setObject(3, offset(BASE));
          statement.executeUpdate();
        }
        connection.commit();
      } catch (SQLException | RuntimeException error) {
        connection.rollback();
        throw error;
      } finally {
        if (previousAutoCommit) {
          connection.setAutoCommit(true);
        }
      }
    }
    return new Fixture(
        sessionId,
        threadId,
        rootEntryId,
        assistantEntryId,
        modelInvocationId,
        invocationId,
        toolCallId,
        executionEpoch,
        descriptor);
  }

  private static ToolDescriptor descriptor(String environmentName, ToolSideEffect sideEffect) {
    return new ToolDescriptor(
        environmentName == null ? "platformTool" : "environmentTool",
        "1",
        environmentName == null ? ToolType.PLATFORM : ToolType.ENVIRONMENT,
        "test tool",
        null,
        new ToolParamsSchema("", Map.of(), Set.of(), false),
        sideEffect,
        Duration.ofMinutes(30));
  }

  private static ToolResult result(String toolCallId, String text) {
    return new ToolResult(toolCallId, List.of(new TextToolContent(text)), false, "{}", false);
  }

  private long insertOpenInteraction(long invocationId) throws SQLException {
    long interactionId = ids.incrementAndGet();
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "insert into harness_interaction (id, tool_invocation_id, request, status, version,"
                    + " created_at) values (?, ?, '{}'::jsonb, 'OPEN', 0, current_timestamp)")) {
      statement.setLong(1, interactionId);
      statement.setLong(2, invocationId);
      statement.executeUpdate();
    }
    return interactionId;
  }

  private static void resolveInteraction(long interactionId) throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "update harness_interaction set status = 'RESOLVED', response = '{\"approved\":false}'"
                    + "::jsonb, resolved_at = current_timestamp where id = ?")) {
      statement.setLong(1, interactionId);
      statement.executeUpdate();
    }
  }

  private static int countOpenInteractionsForTool(long invocationId) throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from harness_interaction where tool_invocation_id = ?"
                    + " and status = 'OPEN'")) {
      statement.setLong(1, invocationId);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        return (int) rs.getLong(1);
      }
    }
  }

  private static String onlyOpenInteractionRequestJson(long invocationId) throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select request::text from harness_interaction where tool_invocation_id = ?"
                    + " and status = 'OPEN' limit 1")) {
      statement.setLong(1, invocationId);
      try (ResultSet rs = statement.executeQuery()) {
        if (!rs.next()) {
          throw new IllegalStateException(
              "no open tool-permission interaction for invocation " + invocationId);
        }
        return rs.getString(1);
      }
    }
  }

  private static void setWorkerToken(long invocationId, String token) throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "update harness_tool_invocation set worker_token = ? where id = ?")) {
      statement.setString(1, token);
      statement.setLong(2, invocationId);
      statement.executeUpdate();
    }
  }

  private static void setAttempt(long invocationId, int attempt) throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "update harness_tool_invocation set attempt = ? where id = ?")) {
      statement.setInt(1, attempt);
      statement.setLong(2, invocationId);
      statement.executeUpdate();
    }
  }

  private static void setThreadEpoch(long threadId, long executionEpoch) throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "update harness_thread set execution_epoch = ?, updated_at = current_timestamp"
                    + " where id = ?")) {
      statement.setLong(1, executionEpoch);
      statement.setLong(2, threadId);
      statement.executeUpdate();
    }
  }

  private static void setInvocationCreatedAt(long invocationId, Instant value) throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "update harness_tool_invocation set created_at = ? where id = ?")) {
      statement.setObject(1, offset(value));
      statement.setLong(2, invocationId);
      assertEquals(1, statement.executeUpdate());
    }
  }

  private static void setActivationState(long invocationId, ActivationState state)
      throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "update harness_execution_activation set activation_state = ?"
                    + " where target_kind = 'TOOL_INVOCATION' and target_id = ?")) {
      statement.setString(1, state.name());
      statement.setLong(2, invocationId);
      assertEquals(1, statement.executeUpdate());
    }
  }

  private static void setActivationWakeAt(long invocationId, Instant value) throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "update harness_execution_activation set wake_at = ?"
                    + " where target_kind = 'TOOL_INVOCATION' and target_id = ?")) {
      statement.setObject(1, offset(value));
      statement.setLong(2, invocationId);
      statement.executeUpdate();
    }
  }

  private static void setActivationEnvironment(long invocationId, String environmentName)
      throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "update harness_execution_activation set environment_name = ?"
                    + " where target_kind = 'TOOL_INVOCATION' and target_id = ?")) {
      statement.setString(1, environmentName);
      statement.setLong(2, invocationId);
      assertEquals(1, statement.executeUpdate());
    }
  }

  private static void deleteActivation(long invocationId) throws SQLException {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "delete from harness_execution_activation"
                    + " where target_kind = 'TOOL_INVOCATION' and target_id = ?")) {
      statement.setLong(1, invocationId);
      statement.executeUpdate();
    }
  }

  private static void suppressRunnableUpdates() throws SQLException {
    try (Connection connection = newConnection();
        Statement statement = connection.createStatement()) {
      statement.execute(
          """
          create function suppress_thread_runnable_update() returns trigger
          language plpgsql as $$
          begin
            if new.runnable then
              return null;
            end if;
            return new;
          end
          $$
          """);
      statement.execute(
          """
          create trigger suppress_thread_runnable_update
          before update on harness_thread
          for each row execute function suppress_thread_runnable_update()
          """);
    }
  }

  private static ToolInvocationDO rowFor(long invocationId) {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select id, thread_id, session_id, assistant_entry_id, model_invocation_id,"
                    + " ordinal, tool_call_id,"
                    + " descriptor::text as descriptor_json, arguments::text as arguments_json,"
                    + " environment_name, execution_epoch, status, attempt,"
                    + " next_attempt_at, worker_token, worker_until, deadline_at, last_activity_at,"
                    + " result::text as result_json, error::text as error_json, applied_at,"
                    + " created_at, started_at, finished_at, permission_state, yolo_enabled"
                    + " from harness_tool_invocation where id = ?")) {
      statement.setLong(1, invocationId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new IllegalStateException("invocation missing: " + invocationId);
        }
        ToolInvocationDO row = new ToolInvocationDO();
        row.setId(resultSet.getLong("id"));
        row.setThreadId(resultSet.getLong("thread_id"));
        row.setSessionId(resultSet.getLong("session_id"));
        row.setAssistantEntryId(resultSet.getLong("assistant_entry_id"));
        row.setModelInvocationId(resultSet.getLong("model_invocation_id"));
        row.setOrdinal(resultSet.getInt("ordinal"));
        row.setToolCallId(resultSet.getString("tool_call_id"));
        row.setDescriptorJson(resultSet.getString("descriptor_json"));
        row.setArgumentsJson(resultSet.getString("arguments_json"));
        row.setEnvironmentName(resultSet.getString("environment_name"));
        row.setExecutionEpoch(resultSet.getLong("execution_epoch"));
        row.setStatus(resultSet.getString("status"));
        row.setAttempt(resultSet.getInt("attempt"));
        row.setNextAttemptAt(resultSet.getObject("next_attempt_at", OffsetDateTime.class));
        row.setWorkerToken(resultSet.getString("worker_token"));
        row.setWorkerUntil(resultSet.getObject("worker_until", OffsetDateTime.class));
        row.setDeadlineAt(resultSet.getObject("deadline_at", OffsetDateTime.class));
        row.setLastActivityAt(resultSet.getObject("last_activity_at", OffsetDateTime.class));
        row.setResultJson(resultSet.getString("result_json"));
        row.setErrorJson(resultSet.getString("error_json"));
        row.setAppliedAt(resultSet.getObject("applied_at", OffsetDateTime.class));
        row.setCreatedAt(resultSet.getObject("created_at", OffsetDateTime.class));
        row.setStartedAt(resultSet.getObject("started_at", OffsetDateTime.class));
        row.setFinishedAt(resultSet.getObject("finished_at", OffsetDateTime.class));
        row.setPermissionState(resultSet.getString("permission_state"));
        row.setYoloEnabled(resultSet.getBoolean("yolo_enabled"));
        return row;
      }
    } catch (SQLException error) {
      throw new IllegalStateException(error);
    }
  }

  private static ActivationRow activationFor(long invocationId) {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select target_kind, target_id, environment_name, activation_state, wake_at"
                    + " from harness_execution_activation"
                    + " where target_kind = 'TOOL_INVOCATION' and target_id = ?")) {
      statement.setLong(1, invocationId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new IllegalStateException("tool activation missing: " + invocationId);
        }
        ExecutionTargetKind kind = ExecutionTargetKind.valueOf(resultSet.getString("target_kind"));
        long targetId = resultSet.getLong("target_id");
        String environmentName = resultSet.getString("environment_name");
        ActivationState activationState =
            ActivationState.valueOf(resultSet.getString("activation_state"));
        Instant wakeAt = resultSet.getObject("wake_at", OffsetDateTime.class).toInstant();
        return new ActivationRow(kind, targetId, environmentName, activationState, wakeAt);
      }
    } catch (SQLException error) {
      throw new IllegalStateException(error);
    }
  }

  private static boolean toolActivationAbsent(long invocationId) {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select count(*) from harness_execution_activation"
                    + " where target_kind = 'TOOL_INVOCATION' and target_id = ?")) {
      statement.setLong(1, invocationId);
      try (ResultSet resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1) == 0;
      }
    } catch (SQLException error) {
      throw new IllegalStateException(error);
    }
  }

  private static ActivationRow threadActivationFor(long threadId) {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select target_kind, target_id, environment_name, activation_state, wake_at"
                    + " from harness_execution_activation"
                    + " where target_kind = 'THREAD' and target_id = ?")) {
      statement.setLong(1, threadId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new IllegalStateException("thread activation missing: " + threadId);
        }
        ExecutionTargetKind kind = ExecutionTargetKind.valueOf(resultSet.getString("target_kind"));
        long targetId = resultSet.getLong("target_id");
        String environmentName = resultSet.getString("environment_name");
        ActivationState activationState =
            ActivationState.valueOf(resultSet.getString("activation_state"));
        Instant wakeAt = resultSet.getObject("wake_at", OffsetDateTime.class).toInstant();
        return new ActivationRow(kind, targetId, environmentName, activationState, wakeAt);
      }
    } catch (SQLException error) {
      throw new IllegalStateException(error);
    }
  }

  private static boolean threadRunnable(long threadId) {
    try (Connection connection = newConnection();
        PreparedStatement statement =
            connection.prepareStatement("select runnable from harness_thread where id = ?")) {
      statement.setLong(1, threadId);
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new IllegalStateException("thread missing: " + threadId);
        }
        return resultSet.getBoolean("runnable");
      }
    } catch (SQLException error) {
      throw new IllegalStateException(error);
    }
  }

  private static OffsetDateTime offset(Instant value) {
    return OffsetDateTime.ofInstant(value.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
  }

  private record Fixture(
      long sessionId,
      long threadId,
      long rootEntryId,
      long assistantEntryId,
      long modelInvocationId,
      long invocationId,
      String toolCallId,
      long executionEpoch,
      ToolDescriptor descriptor) {}

  private record ActivationRow(
      ExecutionTargetKind targetKind,
      long targetId,
      String environmentName,
      ActivationState activationState,
      Instant wakeAt) {}
}
