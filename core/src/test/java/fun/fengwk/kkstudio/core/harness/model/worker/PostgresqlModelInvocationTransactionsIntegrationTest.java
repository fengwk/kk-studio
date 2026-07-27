package fun.fengwk.kkstudio.core.harness.model.worker;

import static fun.fengwk.kkstudio.core.harness.persistence.postgresql.PostgresSchemaSupport.newConnection;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import fun.fengwk.kkstudio.core.persistence.test.PostgresSpringTestSupport;
import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.execution.Lease;
import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.PromptCachePolicy;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderRequestJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderResponseJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ClaimedModelInvocation;
import fun.fengwk.kkstudio.harness.runtime.model.worker.ModelInvocationUpdateOutcome;

import java.math.BigDecimal;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 真实 PostgreSQL 17 集成测试：覆盖 claim/heartbeat/activity/terminal/retry/due scan 全部 fence 与状态机的负例。
 *
 * <p>每个测试构造合法 Session+Main Thread+ROOT Entry 与合法 QUEUED Invocation；{@link
 * PostgresqlModelInvocationTransactions} 在事务内完成 fence。运行依赖 Docker（{@link
 * fun.fengwk.kkstudio.core.harness.persistence.postgresql.PostgresSchemaSupport}）。
 */
public class PostgresqlModelInvocationTransactionsIntegrationTest
    extends PostgresSpringTestSupport {

  private static final ProviderRequestJsonCodec REQUEST_CODEC = new ProviderRequestJsonCodec();
  private static final ProviderResponseJsonCodec RESPONSE_CODEC = new ProviderResponseJsonCodec();
  private static final ModelInvocationErrorJsonCodec ERROR_CODEC =
      new ModelInvocationErrorJsonCodec();

  private static final Duration LONG_LEASE = Duration.ofMinutes(5);
  private static final Duration SHORT_LEASE = Duration.ofMillis(50);

  @Autowired private PostgresqlModelInvocationTransactions transactions;

  private final AtomicLong ids = new AtomicLong(20_000_000L);

  // ---------- success path baseline ----------

  /**
   * QUEUED -> RUNNING 完整建立 started/deadline/lastActivity/lease；attempts 不变；Thread executionEpoch
   * 不变。
   */
  @Test
  void claimQueuedEstablishesRunningAndPreservesEpoch() throws Exception {
    Fixture fx = newQueued();
    Instant now = now();
    String token = "tok-" + UUID.randomUUID();

    Optional<ClaimedModelInvocation> claimed =
        transactions.claim(fx.invocationId, token, ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, now);

    assertTrue(claimed.isPresent(), "claim must succeed for fresh QUEUED row");
    ClaimedModelInvocation ownership = claimed.get();
    assertSame(InvocationStatus.RUNNING, ownership.invocation().status());
    assertEquals(fx.executionEpoch, ownership.invocation().executionEpoch());
    assertEquals(1, ownership.invocation().attempt());
    assertFalse(ownership.recoveredLease());
    Lease lease = ownership.invocation().workerLease();
    assertNotNull(lease);
    assertEquals(token, lease.token());
    assertTrue(lease.until().isAfter(now), "lease must be strictly after now");
    assertTrue(lease.until().isAfter(ownership.invocation().startedAt()));
    assertTrue(ownership.invocation().deadlineAt().isAfter(ownership.invocation().startedAt()));
    assertEquals(ownership.invocation().lastActivityAt(), ownership.invocation().startedAt());

    ModelInvocationDO row = rowFor(fx.invocationId);
    assertEquals("RUNNING", row.getStatus());
    assertEquals(1, row.getAttempt());
    assertEquals(token, row.getWorkerToken());
    assertNotNull(row.getStartedAt());
    assertNotNull(row.getDeadlineAt());
    assertNotNull(row.getLastActivityAt());
    assertNotNull(row.getWorkerUntil());
    assertNull(row.getNextAttemptAt());
    assertNull(row.getFinishedAt());
    assertNull(row.getResultJson());
    assertNull(row.getErrorJson());
    assertEquals(row.getStartedAt(), row.getLastActivityAt());
  }

  /** Due RETRY_WAIT -> RUNNING：attempt +1，保留首次 startedAt/deadlineAt，lastActivityAt 单调刷新。 */
  @Test
  void claimRetryWaitIncrementsAttemptAndPreservesClocks() throws Exception {
    Fixture fx = newQueued();
    Instant now = now();
    Optional<ClaimedModelInvocation> firstClaim =
        transactions.claim(
            fx.invocationId, "tok-A", ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, now);
    assertTrue(firstClaim.isPresent());
    Instant startedAt = firstClaim.get().invocation().startedAt();
    Instant deadlineAt = firstClaim.get().invocation().deadlineAt();
    Instant leaseUntil = firstClaim.get().invocation().workerLease().until();

    Instant retryStart = now.plus(Duration.ofMillis(20));
    // scheduleRetry 把 due 设置在 retryStart+1s 之内的近期时间，让下一轮 claim 必然 due
    Instant nextAttemptAt = retryStart.plus(Duration.ofSeconds(1));
    // 用 retryStart 模拟活动时刻，刷新 lastActivityAt
    Instant observedActivity = retryStart.plus(Duration.ofMillis(5));
    ModelInvocationUpdateOutcome retry =
        transactions.scheduleRetry(firstClaim.get(), nextAttemptAt, observedActivity, retryStart);
    assertSame(ModelInvocationUpdateOutcome.APPLIED, retry);

    ModelInvocationDO retryRow = rowFor(fx.invocationId);
    assertEquals("RETRY_WAIT", retryRow.getStatus());

    String token = "tok-B";
    Instant claimNow = nextAttemptAt.plus(Duration.ofMillis(50));
    Optional<ClaimedModelInvocation> secondClaim =
        transactions.claim(
            fx.invocationId, token, ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, claimNow);
    assertTrue(secondClaim.isPresent(), "due RETRY_WAIT must be claimable");
    ClaimedModelInvocation retryClaim = secondClaim.get();
    assertEquals(2, retryClaim.invocation().attempt(), "attempt must increment");
    assertEquals(startedAt, retryClaim.invocation().startedAt(), "startedAt must not change");
    assertEquals(deadlineAt, retryClaim.invocation().deadlineAt(), "deadlineAt must not change");
    assertEquals(
        claimNow,
        retryClaim.invocation().lastActivityAt(),
        "lastActivityAt must be reset to this attempt start");
    assertEquals(token, retryClaim.invocation().workerLease().token());
    assertTrue(retryClaim.invocation().workerLease().until().isAfter(claimNow));
    assertFalse(retryClaim.recoveredLease());

    ModelInvocationDO row = rowFor(fx.invocationId);
    assertEquals("RUNNING", row.getStatus());
    assertEquals(2, row.getAttempt());
    assertEquals(token, row.getWorkerToken());
    // sanity check lease expiry respected retry-preparation
    assertNotNull(leaseUntil);
    assertTrue(row.getWorkerUntil().toInstant().isAfter(leaseUntil.minusSeconds(1)));
  }

  /** expired RUNNING lease 被接管：保留 attempt/clocks；recoveredLease=true；返回新 lease。 */
  @Test
  void claimExpiredRunningRecoversLease() throws Exception {
    Fixture fx = newQueued();
    Instant now = now();
    String oldToken = "tok-old";
    Optional<ClaimedModelInvocation> firstClaim =
        transactions.claim(
            fx.invocationId, oldToken, ModelCallTimeoutPolicy.DEFAULT, SHORT_LEASE, now);
    assertTrue(firstClaim.isPresent());
    Instant startedAt = firstClaim.get().invocation().startedAt();
    Instant deadlineAt = firstClaim.get().invocation().deadlineAt();
    Instant oldLastActivity = firstClaim.get().invocation().lastActivityAt();

    Instant later = now.plusMillis(80L);
    String newToken = "tok-new";
    Optional<ClaimedModelInvocation> recovered =
        transactions.claim(
            fx.invocationId, newToken, ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, later);
    assertTrue(recovered.isPresent(), "expired RUNNING must be reclaimable");
    ClaimedModelInvocation ownership = recovered.get();
    assertTrue(ownership.recoveredLease(), "must mark recoveredLease=true");
    assertEquals(1, ownership.invocation().attempt(), "attempt must not increment on recovery");
    assertEquals(startedAt, ownership.invocation().startedAt());
    assertEquals(deadlineAt, ownership.invocation().deadlineAt());
    assertEquals(oldLastActivity, ownership.invocation().lastActivityAt());
    assertEquals(newToken, ownership.invocation().workerLease().token());
    assertTrue(ownership.invocation().workerLease().until().isAfter(later));

    ModelInvocationDO row = rowFor(fx.invocationId);
    assertEquals("RUNNING", row.getStatus());
    assertEquals(1, row.getAttempt());
    assertEquals(newToken, row.getWorkerToken());
  }

  // ---------- exclusion ----------

  /** 持有 lease 的 active RUNNING 不在 findClaimable 内；not-due RETRY_WAIT 也不在。 */
  @Test
  void findClaimableExcludesActiveAndNotDue() throws Exception {
    Fixture fx = newQueued();
    String token = "tok-active";
    Optional<ClaimedModelInvocation> active =
        transactions.claim(
            fx.invocationId, token, ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, Instant.now());
    assertTrue(active.isPresent());

    Optional<ModelInvocation> claimable =
        transactions.findClaimable(fx.invocationId, Instant.now());
    assertTrue(claimable.isEmpty(), "active lease must exclude dispatch");

    Fixture other = newQueued();
    Instant now = Instant.now();
    String token2 = "tok-2";
    Optional<ClaimedModelInvocation> first =
        transactions.claim(
            other.invocationId, token2, ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, now);
    assertTrue(first.isPresent());
    ModelInvocationUpdateOutcome out =
        transactions.scheduleRetry(
            first.get(),
            now.plus(Duration.ofMinutes(10)),
            first.get().invocation().lastActivityAt(),
            now);
    assertSame(ModelInvocationUpdateOutcome.APPLIED, out);
    Optional<ModelInvocation> notDue =
        transactions.findClaimable(other.invocationId, now.plus(Duration.ofSeconds(5)));
    assertTrue(notDue.isEmpty(), "future-due RETRY_WAIT must exclude dispatch");

    Optional<ModelInvocation> notFound = transactions.findClaimable(99_999_999L, Instant.now());
    assertTrue(notFound.isEmpty(), "unknown id must be empty");
  }

  /** 公共 claim 边界拒绝非法 identity/token/precision，并能返回完整 QUEUED candidate。 */
  @Test
  void validatesClaimInputsAndPersistencePrecision() throws Exception {
    Fixture fx = newQueued();
    Instant now = now();

    Optional<ModelInvocation> candidate = transactions.findClaimable(fx.invocationId, now);
    assertTrue(candidate.isPresent());
    assertSame(InvocationStatus.QUEUED, candidate.orElseThrow().status());
    assertThrows(IllegalArgumentException.class, () -> transactions.findClaimable(0L, now));
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.claim(0L, "token", ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, now));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            transactions.claim(
                fx.invocationId, " ", ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, now));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            transactions.claim(
                fx.invocationId, "x".repeat(129), ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, now));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            transactions.claim(
                fx.invocationId,
                "token",
                ModelCallTimeoutPolicy.DEFAULT,
                Duration.ofNanos(999_999L),
                now));
    ModelCallTimeoutPolicy subMillisecondTimeout =
        new ModelCallTimeoutPolicy(Duration.ofNanos(1L), Duration.ofSeconds(1L));
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.claim(fx.invocationId, "token", subMillisecondTimeout, LONG_LEASE, now));

    ClaimedModelInvocation claimed =
        transactions
            .claim(fx.invocationId, "token", ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, now)
            .orElseThrow();
    assertThrows(
        IllegalArgumentException.class,
        () -> transactions.renew(claimed, Duration.ofNanos(999_999L), now));
  }

  // ---------- renew / activity ----------

  /** renew 延长时间且不改 last_activity_at；调用方持有的 ClaimedModelInvocation 不变（renew 不重发）。 */
  @Test
  void renewMonotonicExtensionLeavesActivityUntouched() throws Exception {
    Fixture fx = newQueued();
    Instant now = Instant.now();
    String token = "tok-renew";
    Optional<ClaimedModelInvocation> claimed =
        transactions.claim(
            fx.invocationId, token, ModelCallTimeoutPolicy.DEFAULT, Duration.ofSeconds(2), now);
    assertTrue(claimed.isPresent());
    ClaimedModelInvocation ownership = claimed.get();
    Lease firstLease = ownership.invocation().workerLease();
    Instant firstActivity = ownership.invocation().lastActivityAt();
    Instant firstDeadline = ownership.invocation().deadlineAt();

    Instant renewNow = now.plus(Duration.ofMillis(10));
    ModelInvocationUpdateOutcome out =
        transactions.renew(ownership, Duration.ofMinutes(3), renewNow);
    assertSame(ModelInvocationUpdateOutcome.APPLIED, out);

    ModelInvocationDO row = rowFor(fx.invocationId);
    assertTrue(
        row.getWorkerUntil().toInstant().isAfter(firstLease.until()),
        "renew must extend lease beyond original until");
    assertTrue(row.getWorkerUntil().toInstant().isAfter(renewNow));
    assertEquals(
        firstActivity,
        row.getLastActivityAt().toInstant(),
        "lastActivityAt must not change on renew");
    assertEquals(
        firstDeadline, row.getDeadlineAt().toInstant(), "deadlineAt must not change on renew");
  }

  /** recordActivity 单调上推：旧值较大时保持不变；新值较大时刷新；lease heart-beat 不允许调用方推进。 */
  @Test
  void recordActivityIsMonotonicNonDecreasing() throws Exception {
    Fixture fx = newQueued();
    Instant now = Instant.now();
    String token = "tok-act";
    Optional<ClaimedModelInvocation> claimed =
        transactions.claim(fx.invocationId, token, ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, now);
    assertTrue(claimed.isPresent());
    ClaimedModelInvocation ownership = claimed.get();

    Instant older = now.minus(Duration.ofMinutes(1));
    Instant newer = now.plus(Duration.ofMinutes(2));
    ModelInvocationUpdateOutcome back = transactions.recordActivity(ownership, older, now);
    assertSame(ModelInvocationUpdateOutcome.APPLIED, back);
    ModelInvocationDO rowMid = rowFor(fx.invocationId);
    assertEquals(
        now.truncatedTo(ChronoUnit.MILLIS),
        rowMid.getLastActivityAt().toInstant().truncatedTo(ChronoUnit.MILLIS),
        "older activity must not move lastActivityAt backwards");

    Instant forwardNow = now.plus(Duration.ofSeconds(1));
    ModelInvocationUpdateOutcome forward =
        transactions.recordActivity(ownership, newer, forwardNow);
    assertSame(ModelInvocationUpdateOutcome.APPLIED, forward);
    ModelInvocationDO rowLate = rowFor(fx.invocationId);
    assertEquals(
        newer.truncatedTo(ChronoUnit.MILLIS),
        rowLate.getLastActivityAt().toInstant().truncatedTo(ChronoUnit.MILLIS),
        "newer activity must refresh lastActivityAt");
  }

  // ---------- terminals ----------

  /** success 把 invocation 变 SUCCEEDED；Thread runnable=true；finished_at >= lastObserved。 */
  @Test
  void completeSuccessWritesPayloadAndMarksRunnable() throws Exception {
    Fixture fx = newQueued();
    Instant now = now();
    String token = "tok-success";
    Optional<ClaimedModelInvocation> claimed =
        transactions.claim(fx.invocationId, token, ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, now);
    assertTrue(claimed.isPresent());
    ClaimedModelInvocation ownership = claimed.get();
    ProviderResponse response = sampleResponse();

    Instant finishedInstant = now.plus(Duration.ofSeconds(3));
    Instant lastObserved = now.plus(Duration.ofSeconds(2));
    ModelInvocationUpdateOutcome out =
        transactions.completeSuccess(ownership, response, lastObserved, finishedInstant);
    assertSame(ModelInvocationUpdateOutcome.APPLIED, out);

    ModelInvocationDO row = rowFor(fx.invocationId);
    assertEquals("SUCCEEDED", row.getStatus());
    assertEquals(
        lastObserved.truncatedTo(ChronoUnit.MILLIS),
        row.getLastActivityAt().toInstant().truncatedTo(ChronoUnit.MILLIS),
        "lastActivityAt must hold lastObserved");
    assertEquals(
        finishedInstant.truncatedTo(ChronoUnit.MILLIS),
        row.getFinishedAt().toInstant().truncatedTo(ChronoUnit.MILLIS),
        "finishedAt must equal max(now, lastObserved)");
    assertNull(row.getWorkerToken());
    assertNull(row.getWorkerUntil());
    assertEquals(
        response,
        RESPONSE_CODEC.decode(row.getResultJson()),
        "result jsonb must round-trip to the strict codec output");
    assertNull(row.getErrorJson());

    HarnessModelInvocationThreadDO thread = threadRow(fx.threadId);
    assertEquals(Boolean.TRUE, thread.getRunnable(), "Thread runnable must flip to true");

    // 幂等：再次 success 必须返回 LOST_OWNERSHIP，不再次 markRunnable。
    ModelInvocationUpdateOutcome second =
        transactions.completeSuccess(ownership, response, lastObserved, finishedInstant);
    assertSame(ModelInvocationUpdateOutcome.LOST_OWNERSHIP, second);
  }

  /** failure：写 FAILED + error；Thread runnable=true；幂等损失租赁。 */
  @Test
  void completeFailureWritesErrorAndMarksRunnable() throws Exception {
    Fixture fx = newQueued();
    Instant now = now();
    String token = "tok-fail";
    Optional<ClaimedModelInvocation> claimed =
        transactions.claim(fx.invocationId, token, ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, now);
    assertTrue(claimed.isPresent());
    ClaimedModelInvocation ownership = claimed.get();
    ModelInvocationError error =
        new ModelInvocationError(ProviderErrorKind.TRANSIENT, "stub provider timeout");

    Instant finishedInstant = now.plus(Duration.ofSeconds(2));
    Instant lastObserved = now.plus(Duration.ofSeconds(1));
    ModelInvocationUpdateOutcome out =
        transactions.completeFailure(ownership, error, lastObserved, finishedInstant);
    assertSame(ModelInvocationUpdateOutcome.APPLIED, out);

    ModelInvocationDO row = rowFor(fx.invocationId);
    assertEquals("FAILED", row.getStatus());
    assertNull(row.getResultJson());
    assertEquals(
        error,
        ERROR_CODEC.decode(row.getErrorJson()),
        "error jsonb must round-trip via strict codec");
    assertNull(row.getWorkerToken());
    assertNull(row.getWorkerUntil());
    assertEquals(
        lastObserved.truncatedTo(ChronoUnit.MILLIS),
        row.getLastActivityAt().toInstant().truncatedTo(ChronoUnit.MILLIS));
    assertEquals(
        finishedInstant.truncatedTo(ChronoUnit.MILLIS),
        row.getFinishedAt().toInstant().truncatedTo(ChronoUnit.MILLIS));

    HarnessModelInvocationThreadDO thread = threadRow(fx.threadId);
    assertEquals(Boolean.TRUE, thread.getRunnable());

    ModelInvocationUpdateOutcome dup =
        transactions.completeFailure(ownership, error, lastObserved, finishedInstant);
    assertSame(ModelInvocationUpdateOutcome.LOST_OWNERSHIP, dup);
  }

  /** cancel 不写 payload；Thread runnable=true；finished_at 取 max；幂等 terminal 即 LOST。 */
  @Test
  void completeCancelWritesNoPayloadAndMarksRunnable() throws Exception {
    Fixture fx = newQueued();
    Instant now = now();
    String token = "tok-cancel";
    Optional<ClaimedModelInvocation> claimed =
        transactions.claim(fx.invocationId, token, ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, now);
    assertTrue(claimed.isPresent());
    ClaimedModelInvocation ownership = claimed.get();

    Instant lastObserved = now.plus(Duration.ofMillis(500));
    Instant finishedInstant = now.plus(Duration.ofMillis(800));
    ModelInvocationUpdateOutcome out =
        transactions.completeCancelled(ownership, lastObserved, finishedInstant);
    assertSame(ModelInvocationUpdateOutcome.APPLIED, out);

    ModelInvocationDO row = rowFor(fx.invocationId);
    assertEquals("CANCELLED", row.getStatus());
    assertNull(row.getResultJson());
    assertNull(row.getErrorJson());
    assertNull(row.getWorkerToken());
    assertNull(row.getWorkerUntil());
    assertEquals(
        lastObserved.truncatedTo(ChronoUnit.MILLIS),
        row.getLastActivityAt().toInstant().truncatedTo(ChronoUnit.MILLIS));
    assertEquals(
        finishedInstant.truncatedTo(ChronoUnit.MILLIS),
        row.getFinishedAt().toInstant().truncatedTo(ChronoUnit.MILLIS));

    HarnessModelInvocationThreadDO thread = threadRow(fx.threadId);
    assertEquals(Boolean.TRUE, thread.getRunnable());
  }

  /** unknown：与 FAILED 同形状，但 kind == UNKNOWN；幂等 terminal 即 LOST。 */
  @Test
  void completeUnknownWritesErrorAndMarksRunnable() throws Exception {
    Fixture fx = newQueued();
    Instant now = now();
    String token = "tok-unknown";
    Optional<ClaimedModelInvocation> claimed =
        transactions.claim(fx.invocationId, token, ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, now);
    assertTrue(claimed.isPresent());
    ClaimedModelInvocation ownership = claimed.get();
    ModelInvocationError error =
        new ModelInvocationError(
            ProviderErrorKind.TRANSIENT, "model worker lease expired; provider outcome unknown");

    Instant finishedInstant = now.plus(Duration.ofSeconds(4));
    Instant lastObserved = now.plus(Duration.ofSeconds(1));
    ModelInvocationUpdateOutcome out =
        transactions.completeUnknown(ownership, error, lastObserved, finishedInstant);
    assertSame(ModelInvocationUpdateOutcome.APPLIED, out);

    ModelInvocationDO row = rowFor(fx.invocationId);
    assertEquals("UNKNOWN", row.getStatus());
    assertEquals(error, ERROR_CODEC.decode(row.getErrorJson()));
    assertNull(row.getResultJson());
    HarnessModelInvocationThreadDO thread = threadRow(fx.threadId);
    assertEquals(Boolean.TRUE, thread.getRunnable());
  }

  // ---------- retry ----------

  /** RETRY_WAIT 不激活 Thread；后续时间到期可再 claim 并标记 attempt+1。 */
  @Test
  void retryLeavesThreadNotRunnableAndClaimableAfterDue() throws Exception {
    Fixture fx = newQueued();
    Instant now = Instant.now();
    String token = "tok-retry";
    Optional<ClaimedModelInvocation> claimed =
        transactions.claim(fx.invocationId, token, ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, now);
    assertTrue(claimed.isPresent());
    ClaimedModelInvocation ownership = claimed.get();
    Instant deadline = ownership.invocation().deadlineAt();

    Instant nextAttemptAt = now.plus(Duration.ofMinutes(2));
    Instant lastObserved = now.plus(Duration.ofSeconds(1));
    ModelInvocationUpdateOutcome out =
        transactions.scheduleRetry(
            ownership, nextAttemptAt, lastObserved, now.plus(Duration.ofMillis(500)));
    assertSame(ModelInvocationUpdateOutcome.APPLIED, out);

    ModelInvocationDO retryRow = rowFor(fx.invocationId);
    assertEquals("RETRY_WAIT", retryRow.getStatus());
    assertNull(retryRow.getWorkerToken());
    assertNull(retryRow.getWorkerUntil());
    assertNotNull(retryRow.getNextAttemptAt());
    assertTrue(retryRow.getNextAttemptAt().toInstant().isBefore(deadline));
    assertTrue(
        retryRow.getNextAttemptAt().toInstant().isAfter(retryRow.getLastActivityAt().toInstant()));
    assertEquals(
        ownership.invocation().startedAt(),
        retryRow.getStartedAt().toInstant(),
        "startedAt must be preserved");
    assertEquals(deadline, retryRow.getDeadlineAt().toInstant(), "deadlineAt must be preserved");

    HarnessModelInvocationThreadDO thread = threadRow(fx.threadId);
    assertFalse(thread.getRunnable(), "RETRY_WAIT must NOT flip Thread runnable");

    // 再 claim：到期时间通过，attempt +1，Thread runnable 仍未被改写。
    Instant claimNow = nextAttemptAt.plus(Duration.ofMillis(50));
    Optional<ClaimedModelInvocation> second =
        transactions.claim(
            fx.invocationId, "tok-retry-2", ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, claimNow);
    assertTrue(second.isPresent());
    assertEquals(2, second.get().invocation().attempt());

    HarnessModelInvocationThreadDO threadAfter = threadRow(fx.threadId);
    assertFalse(
        threadAfter.getRunnable(),
        "retry + reclaim cycle must leave Thread runnable=false until terminal");
  }

  /** 错误构造：nextAttemptAt <= deadlineAt 或 <= lastActivityAt 时 scheduleRetry 直接拒绝（不写库）。 */
  @Test
  void scheduleRetryRejectsInvalidBoundsBeforeTouchingDatabase() throws Exception {
    Fixture fx = newQueued();
    Instant now = Instant.now();
    Optional<ClaimedModelInvocation> claimed =
        transactions.claim(fx.invocationId, "tok", ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, now);
    assertTrue(claimed.isPresent());
    ClaimedModelInvocation ownership = claimed.get();

    Instant deadline = ownership.invocation().deadlineAt();
    Instant lastActivity = ownership.invocation().lastActivityAt();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            transactions.scheduleRetry(
                ownership, deadline, lastActivity, now.plus(Duration.ofMillis(10))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            transactions.scheduleRetry(
                ownership,
                lastActivity.minus(Duration.ofSeconds(1)),
                lastActivity,
                now.plus(Duration.ofMillis(10))));
  }

  // ---------- due scan order ----------

  /** expired RUNNING > due RETRY_WAIT > QUEUED。每次 pick 之后调用方都 claim 该行，模拟 worker 真实节奏。 */
  @Test
  void findNextClaimablePicksExpiredRunningBeforeDueRetryBeforeQueued() throws Exception {
    Instant base = now();
    Fixture expired = newQueued(base);
    Optional<ClaimedModelInvocation> firstExpired =
        transactions.claim(
            expired.invocationId, "tok-expired", ModelCallTimeoutPolicy.DEFAULT, SHORT_LEASE, base);
    assertTrue(firstExpired.isPresent());

    Fixture retryFixture = newQueued(base);
    Instant retryFirstNow = base.plus(Duration.ofMillis(5));
    Optional<ClaimedModelInvocation> firstRetry =
        transactions.claim(
            retryFixture.invocationId,
            "tok-retry-1",
            ModelCallTimeoutPolicy.DEFAULT,
            LONG_LEASE,
            retryFirstNow);
    assertTrue(firstRetry.isPresent());
    Instant retryNextAt = retryFirstNow.plus(Duration.ofSeconds(1));
    transactions.scheduleRetry(
        firstRetry.get(),
        retryNextAt,
        firstRetry.get().invocation().lastActivityAt(),
        retryFirstNow);

    Fixture queuedFixture = newQueued(base);

    Instant now = base.plus(Duration.ofSeconds(3));

    // 1st pick: expired RUNNING
    Optional<ModelInvocation> first = transactions.findNextClaimable(now);
    assertTrue(first.isPresent());
    assertEquals(
        expired.invocationId, first.get().id(), "expired RUNNING must win over due RETRY_WAIT");
    transactions.claim(
        expired.invocationId, "tok-take-1", ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, now);

    // 2nd pick: due RETRY_WAIT
    Optional<ModelInvocation> second = transactions.findNextClaimable(now);
    assertTrue(second.isPresent());
    assertEquals(retryFixture.invocationId, second.get().id(), "due RETRY_WAIT must come next");
    transactions.claim(
        retryFixture.invocationId, "tok-take-2", ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, now);

    // 3rd pick: QUEUED
    Optional<ModelInvocation> third = transactions.findNextClaimable(now);
    assertTrue(third.isPresent());
    assertEquals(queuedFixture.invocationId, third.get().id(), "QUEUED must come last");
    transactions.claim(
        queuedFixture.invocationId, "tok-take-3", ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, now);

    // 4th pick: empty
    Optional<ModelInvocation> fourth = transactions.findNextClaimable(now);
    assertTrue(fourth.isEmpty(), "no more claimable work");
  }

  // ---------- fence negatives ----------

  /** Thread executionEpoch 被并发推进后 claim 返回 empty；持有旧 claim 的 mutation 返回 LOST_OWNERSHIP。 */
  @Test
  void staleThreadEpochOnClaimAndFollowupMutation() throws Exception {
    Fixture fx = newQueued();
    Instant now = Instant.now();
    String token = "tok-epoch";
    Optional<ClaimedModelInvocation> claimed =
        transactions.claim(fx.invocationId, token, ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, now);
    assertTrue(claimed.isPresent());
    ClaimedModelInvocation ownership = claimed.get();

    bumpThreadExecutionEpoch(fx.threadId, fx.executionEpoch + 1);

    ModelInvocationUpdateOutcome after =
        transactions.recordActivity(ownership, now.plus(Duration.ofMillis(50)), now);
    assertSame(ModelInvocationUpdateOutcome.LOST_OWNERSHIP, after, "stale epoch must LOST");

    Fixture other = newQueued();
    String token2 = "tok-reclaim";
    Optional<ClaimedModelInvocation> reclaim =
        transactions.claim(
            other.invocationId, token2, ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, Instant.now());
    assertTrue(reclaim.isPresent());
    bumpThreadExecutionEpoch(other.threadId, other.executionEpoch + 1);
    ModelInvocationUpdateOutcome secondClaim =
        transactions.scheduleRetry(
            reclaim.get(),
            reclaim.get().invocation().lastActivityAt().plus(Duration.ofMinutes(1)),
            reclaim.get().invocation().lastActivityAt(),
            Instant.now());
    assertSame(
        ModelInvocationUpdateOutcome.LOST_OWNERSHIP, secondClaim, "stale epoch on retry must LOST");

    bumpThreadExecutionEpoch(fx.threadId, fx.executionEpoch + 2);
    Optional<ModelInvocation> noClaim = transactions.findClaimable(fx.invocationId, Instant.now());
    assertTrue(noClaim.isEmpty(), "stale epoch must exclude dispatch");
  }

  /** Invocation executionEpoch 被推过 claim 后，所有 claim-following mutation 返回 LOST。 */
  @Test
  void staleInvocationEpochLosesOwnership() throws Exception {
    Fixture fx = newQueued();
    Instant now = Instant.now();
    String token = "tok-iE";
    Optional<ClaimedModelInvocation> claimed =
        transactions.claim(fx.invocationId, token, ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, now);
    assertTrue(claimed.isPresent());
    ClaimedModelInvocation ownership = claimed.get();

    bumpInvocationExecutionEpoch(fx.invocationId, fx.executionEpoch + 7);
    ModelInvocationUpdateOutcome out =
        transactions.recordActivity(ownership, now.plus(Duration.ofMillis(20)), now);
    assertSame(ModelInvocationUpdateOutcome.LOST_OWNERSHIP, out);

    ModelInvocationUpdateOutcome retry =
        transactions.scheduleRetry(
            ownership,
            ownership.invocation().lastActivityAt().plus(Duration.ofSeconds(10)),
            ownership.invocation().lastActivityAt(),
            now);
    assertSame(ModelInvocationUpdateOutcome.LOST_OWNERSHIP, retry);

    ModelInvocationUpdateOutcome renew = transactions.renew(ownership, Duration.ofMinutes(2), now);
    assertSame(ModelInvocationUpdateOutcome.LOST_OWNERSHIP, renew);
  }

  /** 错误的 workerToken 在 mutation 中返回 LOST。 */
  @Test
  void staleTokenIsLost() throws Exception {
    Fixture fx = newQueued();
    Instant now = Instant.now();
    String token = "tok-real";
    Optional<ClaimedModelInvocation> claimed =
        transactions.claim(fx.invocationId, token, ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, now);
    assertTrue(claimed.isPresent());
    ClaimedModelInvocation ownership = claimed.get();

    ClaimedModelInvocation forged =
        new ClaimedModelInvocation(
            new ModelInvocation(
                ownership.invocation().id(),
                ownership.invocation().threadId(),
                ownership.invocation().sourceHeadEntryId(),
                ownership.invocation().executionEpoch(),
                ownership.invocation().request(),
                InvocationStatus.RUNNING,
                ownership.invocation().attempt(),
                null,
                new Lease("tok-forged", ownership.invocation().workerLease().until()),
                ownership.invocation().deadlineAt(),
                ownership.invocation().lastActivityAt(),
                null,
                null,
                null,
                now.minus(Duration.ofMinutes(1)),
                ownership.invocation().startedAt(),
                null),
            false);

    ModelInvocationUpdateOutcome out =
        transactions.recordActivity(forged, now.plus(Duration.ofMillis(50)), now);
    assertSame(ModelInvocationUpdateOutcome.LOST_OWNERSHIP, out);
  }

  /** attempt 不匹配：CAS 0；renew/recordActivity 返回 LOST。 */
  @Test
  void staleAttemptLosesOwnership() throws Exception {
    Fixture fx = newQueued();
    Instant now = Instant.now();
    Optional<ClaimedModelInvocation> claimed =
        transactions.claim(fx.invocationId, "tok", ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, now);
    assertTrue(claimed.isPresent());
    ClaimedModelInvocation ownership = claimed.get();
    bumpInvocationAttempt(fx.invocationId, ownership.invocation().attempt() + 5);

    ModelInvocationUpdateOutcome out =
        transactions.recordActivity(ownership, now.plus(Duration.ofMillis(20)), now);
    assertSame(ModelInvocationUpdateOutcome.LOST_OWNERSHIP, out);

    ModelInvocationUpdateOutcome renew = transactions.renew(ownership, Duration.ofMinutes(2), now);
    assertSame(ModelInvocationUpdateOutcome.LOST_OWNERSHIP, renew);
  }

  /** lease 已过期：renew/recordActivity 都 LOST；terminal 已写入时 completeXxx 也 LOST。 */
  @Test
  void expiredLeaseAndTerminalTwiceBothLoseOwnership() throws Exception {
    Fixture fx = newQueued();
    Instant now = now();
    Optional<ClaimedModelInvocation> claimed =
        transactions.claim(
            fx.invocationId, "tok", ModelCallTimeoutPolicy.DEFAULT, SHORT_LEASE, now);
    assertTrue(claimed.isPresent());
    ClaimedModelInvocation ownership = claimed.get();

    Instant later = now.plusMillis(80L);

    ModelInvocationUpdateOutcome renew =
        transactions.renew(ownership, Duration.ofMinutes(2), later);
    assertSame(ModelInvocationUpdateOutcome.LOST_OWNERSHIP, renew);
    ModelInvocationUpdateOutcome act =
        transactions.recordActivity(ownership, later.plus(Duration.ofMillis(50)), later);
    assertSame(ModelInvocationUpdateOutcome.LOST_OWNERSHIP, act);

    // 这里 lease 已过期，completeXxx 应当同样 LOST；SQL CAS 自身守住。
    ProviderResponse response = sampleResponse();
    ModelInvocationUpdateOutcome lost =
        transactions.completeSuccess(ownership, response, later, later);
    assertSame(ModelInvocationUpdateOutcome.LOST_OWNERSHIP, lost);

    // 重新 claim 接管并写入 terminal，之后用旧 Claimed 再调一次必 LOST 且不变状态。
    String token2 = "tok-takeover";
    Optional<ClaimedModelInvocation> takeover =
        transactions.claim(
            fx.invocationId, token2, ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, later);
    assertTrue(takeover.isPresent());
    assertTrue(takeover.get().recoveredLease());
    ClaimedModelInvocation finalOwner = takeover.get();

    ModelInvocationUpdateOutcome done =
        transactions.completeSuccess(
            finalOwner, response, later, later.plus(Duration.ofSeconds(1)));
    assertSame(ModelInvocationUpdateOutcome.APPLIED, done);
    ModelInvocationUpdateOutcome dup =
        transactions.completeSuccess(ownership, response, later, later.plus(Duration.ofSeconds(1)));
    assertSame(ModelInvocationUpdateOutcome.LOST_OWNERSHIP, dup);
  }

  /** 状态被外部改成 RETRY_WAIT 后，再用原 Claimed 调 mutation：CAS 0，返回 LOST。 */
  @Test
  void statusChangedLosesOwnership() throws Exception {
    Fixture fx = newQueued();
    Instant now = Instant.now();
    Optional<ClaimedModelInvocation> claimed =
        transactions.claim(fx.invocationId, "tok", ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, now);
    assertTrue(claimed.isPresent());
    ClaimedModelInvocation ownership = claimed.get();
    forceRetryWait(fx.invocationId, now.plus(Duration.ofMinutes(1)));
    ModelInvocationUpdateOutcome out =
        transactions.recordActivity(ownership, now.plus(Duration.ofMillis(50)), now);
    assertSame(ModelInvocationUpdateOutcome.LOST_OWNERSHIP, out);
  }

  /** 错误身份（id 与 claim 不匹配）必须 LOST，不会重写另一行。 */
  @Test
  void identityMismatchLosesOwnership() throws Exception {
    Fixture fx = newQueued();
    Instant now = Instant.now();
    Optional<ClaimedModelInvocation> claimed =
        transactions.claim(
            fx.invocationId, "tok-real", ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, now);
    assertTrue(claimed.isPresent());
    ClaimedModelInvocation ownership = claimed.get();
    ModelInvocationDO rowBefore = rowFor(fx.invocationId);
    long originalLastActivityNanos = rowBefore.getLastActivityAt().toInstant().toEpochMilli();

    ClaimedModelInvocation forged =
        new ClaimedModelInvocation(
            new ModelInvocation(
                ownership.invocation().id() + 1L,
                ownership.invocation().threadId(),
                ownership.invocation().sourceHeadEntryId(),
                ownership.invocation().executionEpoch(),
                ownership.invocation().request(),
                InvocationStatus.RUNNING,
                ownership.invocation().attempt(),
                null,
                ownership.invocation().workerLease(),
                ownership.invocation().deadlineAt(),
                ownership.invocation().lastActivityAt(),
                null,
                null,
                null,
                now.minus(Duration.ofMinutes(1)),
                ownership.invocation().startedAt(),
                null),
            false);
    ModelInvocationUpdateOutcome out =
        transactions.recordActivity(forged, now.plus(Duration.ofMillis(50)), now);
    assertSame(ModelInvocationUpdateOutcome.LOST_OWNERSHIP, out);

    ModelInvocationDO rowAfter = rowFor(fx.invocationId);
    assertEquals(
        originalLastActivityNanos,
        rowAfter.getLastActivityAt().toInstant().toEpochMilli(),
        "mismatched identity must not touch another row");
  }

  /** findClaimable 必须 join Thread 并校验 Thread.current.executionEpoch = invocation.executionEpoch。 */
  @Test
  void findClaimableRequiresThreadEpochMatch() throws Exception {
    Fixture fx = newQueued();
    bumpThreadExecutionEpoch(fx.threadId, fx.executionEpoch + 1);
    Optional<ModelInvocation> claimable =
        transactions.findClaimable(fx.invocationId, Instant.now());
    assertTrue(
        claimable.isEmpty(), "findClaimable must filter out when Thread epoch != Invocation epoch");
  }

  // ---------- invariant breaches ----------

  /** Thread runnable 更新被数据库拒绝时，terminal Invocation 必须随同一事务一起回滚。 */
  @Test
  void terminalRollsBackWhenRunnableUpdateCannotBeApplied() throws Exception {
    Fixture fx = newQueued();
    Instant now = now();
    Optional<ClaimedModelInvocation> claimed =
        transactions.claim(
            fx.invocationId, "tok-rollback", ModelCallTimeoutPolicy.DEFAULT, LONG_LEASE, now);
    assertTrue(claimed.isPresent());

    try (Connection conn = newConnection();
        Statement st = conn.createStatement()) {
      st.execute(
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
      st.execute(
          """
          create trigger suppress_thread_runnable_update
          before update on harness_thread
          for each row execute function suppress_thread_runnable_update()
          """);
    }

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () ->
                transactions.completeSuccess(
                    claimed.orElseThrow(),
                    sampleResponse(),
                    now.plusSeconds(1),
                    now.plusSeconds(2)));
    assertTrue(
        ex.getMessage() != null && ex.getMessage().contains("cannot mark thread"),
        "adapter must surface the failed control-plane update");

    ModelInvocationDO invocation = rowFor(fx.invocationId);
    assertEquals("RUNNING", invocation.getStatus(), "terminal update must be rolled back");
    assertEquals("tok-rollback", invocation.getWorkerToken(), "lease must be restored by rollback");
    assertFalse(threadRow(fx.threadId).getRunnable(), "Thread must remain non-runnable");
  }

  // ---------- helpers ----------

  private Fixture newQueued(Instant createdAt) throws SQLException {
    return newQueued(createdAt, 1L);
  }

  private Fixture newQueued() throws SQLException {
    return newQueued(now(), 1L);
  }

  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MILLIS);
  }

  private Fixture newQueued(Instant createdAt, long executionEpoch) throws SQLException {
    long sessionId = ids.incrementAndGet();
    long threadId = ids.incrementAndGet();
    long rootEntryId = ids.incrementAndGet();
    long invocationId = ids.incrementAndGet();

    try (Connection conn = newConnection()) {
      boolean prevAuto = conn.getAutoCommit();
      conn.setAutoCommit(false);
      try {
        try (PreparedStatement ps =
            conn.prepareStatement(
                "insert into harness_session (id, title, created_at)"
                    + " values (?, 'fixture', current_timestamp)")) {
          ps.setLong(1, sessionId);
          ps.executeUpdate();
        }
        try (PreparedStatement ps =
            conn.prepareStatement(
                "insert into harness_thread (id, head_entry_id, input_sequence,"
                    + " runnable, execution_epoch, created_at, updated_at)"
                    + " values (?, ?, 0, false, ?, current_timestamp, current_timestamp)")) {
          ps.setLong(1, threadId);
          ps.setLong(2, rootEntryId);
          ps.setLong(3, executionEpoch);
          ps.executeUpdate();
        }
        try (PreparedStatement ps =
            conn.prepareStatement(
                "insert into harness_entry (id, session_id, parent_entry_id, entry_type, payload,"
                    + " created_at) values (?, ?, null, 'ROOT', '{}'::jsonb,"
                    + " current_timestamp)")) {
          ps.setLong(1, rootEntryId);
          ps.setLong(2, sessionId);
          ps.executeUpdate();
        }
        OffsetDateTime createdOffset = OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC);
        try (PreparedStatement ps =
            conn.prepareStatement(
                "insert into harness_model_invocation (id, thread_id,"
                    + " source_head_entry_id, execution_epoch, request, status, attempt,"
                    + " created_at) values (?, ?, ?, ?, cast(? as jsonb), 'QUEUED', 1, ?)")) {
          ps.setLong(1, invocationId);
          ps.setLong(2, threadId);
          ps.setLong(3, rootEntryId);
          ps.setLong(4, executionEpoch);
          ps.setString(5, REQUEST_CODEC.encode(sampleRequest()));
          ps.setObject(6, createdOffset);
          ps.executeUpdate();
        }
        conn.commit();
      } catch (SQLException | RuntimeException ex) {
        conn.rollback();
        throw ex;
      } finally {
        if (prevAuto) {
          conn.setAutoCommit(true);
        }
      }
    }
    return new Fixture(sessionId, threadId, rootEntryId, invocationId, executionEpoch);
  }

  private void bumpThreadExecutionEpoch(long threadId, long newEpoch) throws SQLException {
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "update harness_thread set execution_epoch = ?, updated_at = current_timestamp"
                    + " where id = ?")) {
      ps.setLong(1, newEpoch);
      ps.setLong(2, threadId);
      ps.executeUpdate();
    }
  }

  private void bumpInvocationExecutionEpoch(long invocationId, long newEpoch) throws SQLException {
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "update harness_model_invocation set execution_epoch = ? where id = ?")) {
      ps.setLong(1, newEpoch);
      ps.setLong(2, invocationId);
      ps.executeUpdate();
    }
  }

  private void bumpInvocationAttempt(long invocationId, int newAttempt) throws SQLException {
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement("update harness_model_invocation set attempt = ? where id = ?")) {
      ps.setInt(1, newAttempt);
      ps.setLong(2, invocationId);
      ps.executeUpdate();
    }
  }

  private void forceRetryWait(long invocationId, Instant nextAttemptAt) throws SQLException {
    OffsetDateTime next = OffsetDateTime.ofInstant(nextAttemptAt, ZoneOffset.UTC);
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "update harness_model_invocation set status = 'RETRY_WAIT',"
                    + " worker_token = null, worker_until = null, next_attempt_at = ?,"
                    + " started_at = coalesce(started_at, current_timestamp),"
                    + " deadline_at = coalesce(deadline_at, current_timestamp + interval '1 hour'),"
                    + " last_activity_at = coalesce(last_activity_at, current_timestamp)"
                    + " where id = ?")) {
      ps.setObject(1, next);
      ps.setLong(2, invocationId);
      ps.executeUpdate();
    }
  }

  private static ModelInvocationDO rowFor(long invocationId) {
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "select id, thread_id, source_head_entry_id, execution_epoch,"
                    + " request as request, status, attempt, next_attempt_at, worker_token,"
                    + " worker_until, deadline_at, last_activity_at, result as result,"
                    + " error as error, applied_at, created_at, started_at, finished_at"
                    + " from harness_model_invocation where id = ?")) {
      ps.setLong(1, invocationId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new IllegalStateException("invocation " + invocationId + " missing");
        }
        return readInvocation(rs);
      }
    } catch (SQLException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static HarnessModelInvocationThreadDO threadRow(long threadId) {
    try (Connection conn = newConnection();
        PreparedStatement ps =
            conn.prepareStatement(
                "select id, runnable, execution_epoch from harness_thread where id = ?")) {
      ps.setLong(1, threadId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          throw new IllegalStateException("thread " + threadId + " missing");
        }
        HarnessModelInvocationThreadDO row = new HarnessModelInvocationThreadDO();
        row.setId(rs.getLong("id"));
        row.setRunnable((Boolean) rs.getObject("runnable"));
        row.setExecutionEpoch(rs.getLong("execution_epoch"));
        return row;
      }
    } catch (SQLException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private static ModelInvocationDO readInvocation(ResultSet rs) throws SQLException {
    ModelInvocationDO row = new ModelInvocationDO();
    row.setId(rs.getLong("id"));
    row.setThreadId(rs.getLong("thread_id"));
    row.setSourceHeadEntryId(rs.getLong("source_head_entry_id"));
    row.setExecutionEpoch(rs.getLong("execution_epoch"));
    row.setRequestJson(rs.getString("request"));
    row.setStatus(rs.getString("status"));
    row.setAttempt((Integer) rs.getObject("attempt"));
    row.setNextAttemptAt(rs.getObject("next_attempt_at", OffsetDateTime.class));
    row.setWorkerToken(rs.getString("worker_token"));
    row.setWorkerUntil(rs.getObject("worker_until", OffsetDateTime.class));
    row.setDeadlineAt(rs.getObject("deadline_at", OffsetDateTime.class));
    row.setLastActivityAt(rs.getObject("last_activity_at", OffsetDateTime.class));
    row.setResultJson(rs.getString("result"));
    row.setErrorJson(rs.getString("error"));
    row.setAppliedAt(rs.getObject("applied_at", OffsetDateTime.class));
    row.setCreatedAt(rs.getObject("created_at", OffsetDateTime.class));
    row.setStartedAt(rs.getObject("started_at", OffsetDateTime.class));
    row.setFinishedAt(rs.getObject("finished_at", OffsetDateTime.class));
    return row;
  }

  private static ProviderRequest sampleRequest() {
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "acceptance",
            "default",
            new BigDecimal("1"),
            "acceptance-v1",
            new BigDecimal("0"),
            new BigDecimal("0"),
            new BigDecimal("0"),
            new BigDecimal("0"),
            new BigDecimal("0"),
            new BigDecimal("0"));
    ModelDescriptor descriptor =
        new ModelDescriptor(
            1L,
            1L,
            ProviderType.OPENAI,
            "acceptance-stub",
            true,
            false,
            pricing,
            PromptCachePolicy.disabled());
    ModelVariant variant =
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null);
    return new ProviderRequest(
        descriptor,
        variant,
        List.of(
            new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("hello")))),
        List.of(),
        ProviderCacheControl.none());
  }

  private static ProviderResponse sampleResponse() {
    ModelUsage usage = new ModelUsage(10L, 4L, 0L, 0L, 0L, 0L, 14L);
    ModelCost cost = ModelCost.calculate(samplePricing(), usage);
    return new ProviderResponse(
        "done", "", List.of(), ProviderStopReason.COMPLETED, usage, cost, "req-1", null, "{}");
  }

  private static ModelPricing samplePricing() {
    return new ModelPricing(
        "USD",
        "acceptance",
        "default",
        new BigDecimal("1"),
        "acceptance-v1",
        new BigDecimal("2.50"),
        new BigDecimal("10.00"),
        new BigDecimal("1.00"),
        new BigDecimal("5.00"),
        new BigDecimal("8.00"),
        new BigDecimal("15.00"));
  }

  private record Fixture(
      long sessionId, long threadId, long rootEntryId, long invocationId, long executionEpoch) {}
}
