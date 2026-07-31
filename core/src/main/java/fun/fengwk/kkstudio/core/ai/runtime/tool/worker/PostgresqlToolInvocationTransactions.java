package fun.fengwk.kkstudio.core.ai.runtime.tool.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.ai.runtime.execution.ExecutionTargetStore;
import fun.fengwk.kkstudio.core.ai.runtime.interaction.store.mapper.InteractionMapper;
import fun.fengwk.kkstudio.core.ai.runtime.interaction.store.model.InteractionDO;
import fun.fengwk.kkstudio.core.ai.runtime.model.worker.HarnessModelInvocationThreadDO;
import fun.fengwk.kkstudio.core.ai.runtime.model.worker.HarnessModelInvocationThreadMapper;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.execution.InvocationStatus;
import fun.fengwk.kkstudio.harness.runtime.permission.PermissionPromptPreview;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolPermissionState;
import fun.fengwk.kkstudio.harness.runtime.port.HarnessIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolBinding;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationErrorJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ClaimedToolInvocation;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationTransactions;
import fun.fengwk.kkstudio.harness.runtime.tool.worker.ToolInvocationUpdateOutcome;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.codec.ToolDescriptorJsonCodec;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * PostgreSQL ToolInvocation worker transaction adapter that drives durable scheduling through the
 * single {@code harness_execution_target} queue and the durable Tool permission state machine.
 *
 * <p>Lock order is Thread → ToolInvocation → target; every mutation acquires the durable target row
 * in the same transaction. Terminal mutations additionally mark the owning Thread runnable and
 * reschedule the durable Thread target.
 *
 * <p>Tool permission state is durable on the invocation row and the OPEN interaction written for
 * ASK. ASK must atomically: persist the final plan, flip permission state to ASKED, transition the
 * row to {@code WAITING_INTERACTION}, clear worker clocks, insert exactly one OPEN interaction
 * owned by the Tool, and park the durable target so the FIFO gate is preserved. ALLOW must
 * atomically overwrite the final plan and flip permission state to ALLOWED. DENY must atomically
 * flip permission state to DENIED, terminalize the row to FAILED with error kind {@code
 * PERMISSION_DENIED}, mark the owning Thread runnable, delete the target, schedule the Thread
 * target, and activate the next environment head.
 *
 * <p>Claim returns {@link Optional#empty()} when the target is absent or not due; a RUNNING/PENDING
 * row whose lease has expired is reset to initial QUEUED/PENDING, the existing target is
 * rescheduled to now, and the call returns {@link Optional#empty()} so the dispatcher can
 * re-dispatch. A RUNNING/ALLOWED expired lease retains the existing conservative UNKNOWN behavior.
 */
@Service
public class PostgresqlToolInvocationTransactions implements ToolInvocationTransactions {

  private static final int MAX_TOKEN_LENGTH = 128;
  private static final Duration DEFAULT_EXECUTION_TIMEOUT = Duration.ofMinutes(5);
  private final PostgresqlToolInvocationMapper invocationMapper;
  private final HarnessModelInvocationThreadMapper threadMapper;
  private final ExecutionTargetStore executionTargetStore;
  private final InteractionMapper interactionMapper;
  private final HarnessIdGenerator idGenerator;
  private final ObjectMapper objectMapper;
  private final ToolDescriptorJsonCodec descriptorCodec = new ToolDescriptorJsonCodec();
  private final ToolInvocationErrorJsonCodec errorCodec = new ToolInvocationErrorJsonCodec();

  public PostgresqlToolInvocationTransactions(
      PostgresqlToolInvocationMapper invocationMapper,
      HarnessModelInvocationThreadMapper threadMapper,
      ExecutionTargetStore executionTargetStore,
      InteractionMapper interactionMapper,
      HarnessIdGenerator idGenerator,
      ObjectMapper objectMapper) {
    this.invocationMapper = Objects.requireNonNull(invocationMapper, "invocationMapper");
    this.threadMapper = Objects.requireNonNull(threadMapper, "threadMapper");
    this.executionTargetStore =
        Objects.requireNonNull(executionTargetStore, "executionTargetStore");
    this.interactionMapper = Objects.requireNonNull(interactionMapper, "interactionMapper");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Optional<ClaimedToolInvocation> claim(
      long invocationId, String workerToken, Duration workerLeaseDuration, Instant now) {
    requirePositive(invocationId, "invocationId");
    requireToken(workerToken);
    requirePositive(workerLeaseDuration, "workerLeaseDuration");
    Instant persistedNow = persistence(now);

    // Step 1: thread row lock + epoch pre-read.
    ToolInvocationDO peek = invocationMapper.findClaimable(invocationId, offset(persistedNow));
    if (peek == null) {
      return Optional.empty();
    }
    HarnessModelInvocationThreadDO thread = threadMapper.findForUpdate(peek.getThreadId());
    if (thread == null) {
      throw new IllegalStateException("owning thread missing for invocation " + invocationId);
    }
    if (!Objects.equals(thread.getExecutionEpoch(), peek.getExecutionEpoch())) {
      return Optional.empty();
    }

    // Step 2: tool invocation row lock + validation (no mutation yet).
    ToolInvocationDO row = invocationMapper.findForUpdate(invocationId, peek.getThreadId());
    if (row == null || !Objects.equals(row.getExecutionEpoch(), thread.getExecutionEpoch())) {
      return Optional.empty();
    }
    InvocationStatus status = InvocationStatus.valueOf(row.getStatus());
    if (row.getPermissionState() == null) {
      throw new IllegalStateException(
          "tool invocation row " + invocationId + " is missing permission_state");
    }
    if (row.getYoloEnabled() == null) {
      throw new IllegalStateException(
          "tool invocation row " + invocationId + " is missing yolo_enabled");
    }
    ToolPermissionState permissionState = ToolPermissionState.fromValue(row.getPermissionState());
    if (status != InvocationStatus.QUEUED
        && status != InvocationStatus.RETRY_WAIT
        && status != InvocationStatus.RUNNING) {
      return Optional.empty();
    }
    if (status == InvocationStatus.RUNNING
        && permissionState != ToolPermissionState.PENDING
        && permissionState != ToolPermissionState.ALLOWED) {
      return Optional.empty();
    }

    // Step 3: target gate. If the target row is absent or not yet due, the transaction commits
    // with no row mutations, leaving the QUEUED / RETRY_WAIT / RUNNING invocation unchanged.
    if (executionTargetStore
        .lockDue(ExecutionTargetKind.TOOL_INVOCATION, invocationId, persistedNow)
        .isEmpty()) {
      return Optional.empty();
    }

    // Step 4: status transition (status transition is now conditional on target lockDue success).
    OffsetDateTime nowOffset = offset(persistedNow);
    OffsetDateTime leaseUntil = advance(persistedNow, workerLeaseDuration, "workerLeaseDuration");
    int affected;
    boolean recovered = false;
    if (status == InvocationStatus.QUEUED) {
      Instant startedAt = max(persistedNow, row.getCreatedAt().toInstant());
      Duration descriptorTimeout =
          ToolInvocationRowConverter.toAggregate(row).descriptor().timeout();
      Duration executionTimeout =
          descriptorTimeout.isZero() ? DEFAULT_EXECUTION_TIMEOUT : descriptorTimeout;
      affected =
          invocationMapper.claimQueued(
              row.getId(),
              row.getThreadId(),
              row.getExecutionEpoch(),
              row.getAttempt(),
              workerToken,
              advance(startedAt, workerLeaseDuration, "workerLeaseDuration"),
              offset(startedAt),
              advance(startedAt, executionTimeout, "executionTimeout"));
    } else if (status == InvocationStatus.RETRY_WAIT) {
      affected =
          invocationMapper.claimRetry(
              row.getId(),
              row.getThreadId(),
              row.getExecutionEpoch(),
              row.getAttempt(),
              workerToken,
              leaseUntil,
              nowOffset);
    } else if (permissionState == ToolPermissionState.PENDING) {
      // RUNNING/PENDING whose lease expired (or any pre-allow RUNNING with no successful
      // external I/O because permission decision had not yet been persisted): the worker has
      // provably not entered external Tool I/O (the ALLOW path persists ALLOWED first). Reset
      // the row to its initial QUEUED/PENDING shape, reschedule the target to {@code now}, and
      // return Optional.empty() so the dispatcher re-dispatches the row. The standard claim
      // precondition will then surface the freshly-due target on the next attempt.
      int recoveredAffected =
          invocationMapper.recoverPendingLease(
              row.getId(),
              row.getThreadId(),
              row.getExecutionEpoch(),
              row.getAttempt(),
              Objects.requireNonNull(row.getWorkerToken(), "workerToken"),
              nowOffset);
      if (recoveredAffected != 1) {
        throw new IllegalStateException(
            "PENDING recovery affected " + recoveredAffected + " rows after the target gate");
      }
      requireTargetAffected(
          executionTargetStore.rescheduleLocked(
              ExecutionTargetKind.TOOL_INVOCATION, invocationId, routeKey(row), persistedNow),
          "reschedule tool invocation target after PENDING recovery");
      return Optional.empty();
    } else {
      // RUNNING/ALLOWED: external Tool I/O may have run; use the existing conservative token-only
      // re-claim and converge the row to UNKNOWN on completion.
      affected =
          invocationMapper.recoverExpired(
              row.getId(),
              row.getThreadId(),
              row.getExecutionEpoch(),
              row.getAttempt(),
              Objects.requireNonNull(row.getWorkerToken(), "workerToken"),
              workerToken,
              leaseUntil,
              nowOffset);
      recovered = true;
    }
    if (affected != 1) {
      throw new IllegalStateException(
          "claim mutated " + affected + " rows after the target gate passed");
    }

    // Step 5: target advance + final ownership check.
    ToolInvocationDO claimed = invocationMapper.findForUpdate(row.getId(), row.getThreadId());
    if (claimed == null) {
      throw new IllegalStateException("claimed invocation disappeared: " + row.getId());
    }
    ToolInvocation aggregate = ToolInvocationRowConverter.toAggregate(claimed);
    if (aggregate.status() != InvocationStatus.RUNNING
        || aggregate.workerLease() == null
        || !aggregate.workerLease().token().equals(workerToken)) {
      throw new IllegalStateException("claim result does not match requested ownership");
    }
    requireTargetAffected(
        executionTargetStore.rescheduleLocked(
            ExecutionTargetKind.TOOL_INVOCATION,
            invocationId,
            routeKey(row),
            aggregate.workerLease().until()),
        "reschedule tool invocation target after claim");
    return Optional.of(new ClaimedToolInvocation(aggregate, status, recovered));
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ToolInvocationUpdateOutcome renew(
      ClaimedToolInvocation claimed, Duration workerLeaseDuration, Instant now) {
    requirePositive(workerLeaseDuration, "workerLeaseDuration");
    LockedTool locked = lockOwned(claimed, now);
    if (locked == null) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    ToolInvocationDO row = locked.row();
    Instant persistedNow = persistence(now);
    Instant leaseBase =
        max(
            persistedNow,
            row.getStartedAt() == null ? persistedNow : row.getStartedAt().toInstant());
    Instant requestedLeaseDeadline =
        advance(leaseBase, workerLeaseDuration, "workerLeaseDuration").toInstant();
    Instant leaseDeadline = max(row.getWorkerUntil().toInstant(), requestedLeaseDeadline);
    int affected =
        invocationMapper.renew(
            row.getId(),
            row.getThreadId(),
            row.getExecutionEpoch(),
            row.getAttempt(),
            claimed.invocation().workerLease().token(),
            offset(leaseDeadline),
            offset(persistedNow));
    if (outcome(affected) != ToolInvocationUpdateOutcome.APPLIED) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    requireTargetAffected(
        executionTargetStore.rescheduleLocked(
            ExecutionTargetKind.TOOL_INVOCATION, row.getId(), routeKey(row), leaseDeadline),
        "reschedule tool invocation target after renew");
    return ToolInvocationUpdateOutcome.APPLIED;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ToolInvocationUpdateOutcome recordActivity(
      ClaimedToolInvocation claimed, Instant activityAt, Instant now) {
    LockedTool locked = lockOwned(claimed, now);
    if (locked == null) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    ToolInvocation invocation = claimed.invocation();
    return outcome(
        invocationMapper.recordActivity(
            invocation.id(),
            invocation.threadId(),
            invocation.executionEpoch(),
            invocation.attempt(),
            invocation.workerLease().token(),
            offset(activityAt),
            offset(persistence(now))));
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ToolInvocationUpdateOutcome releaseUnstarted(
      ClaimedToolInvocation claimed, Instant nextAttemptAt, Instant now) {
    LockedTool locked = lockOwned(claimed, now);
    if (locked == null) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    ToolInvocationDO row = locked.row();
    ToolInvocation invocation = claimed.invocation();
    InvocationStatus previousStatus = claimed.previousStatus();
    int affected;
    Instant persistedNow = persistence(now);
    Instant rescheduleAt;
    if (previousStatus == InvocationStatus.QUEUED) {
      if (nextAttemptAt != null || invocation.attempt() != 1) {
        throw new IllegalArgumentException(
            "QUEUED release requires attempt 1 and no nextAttemptAt");
      }
      affected =
          invocationMapper.releaseUnstartedQueued(
              invocation.id(),
              invocation.threadId(),
              invocation.executionEpoch(),
              invocation.attempt(),
              invocation.workerLease().token(),
              offset(persistedNow));
      rescheduleAt = persistedNow;
    } else if (previousStatus == InvocationStatus.RETRY_WAIT) {
      if (invocation.attempt() <= 1 || nextAttemptAt == null) {
        throw new IllegalArgumentException(
            "RETRY_WAIT release requires a previous attempt and nextAttemptAt");
      }
      Instant next = persistence(nextAttemptAt);
      if (!next.isAfter(row.getLastActivityAt().toInstant())
          || !next.isBefore(row.getDeadlineAt().toInstant())) {
        throw new IllegalArgumentException(
            "nextAttemptAt must be after activity and before deadline");
      }
      affected =
          invocationMapper.releaseUnstartedRetry(
              invocation.id(),
              invocation.threadId(),
              invocation.executionEpoch(),
              invocation.attempt(),
              invocation.attempt() - 1,
              invocation.workerLease().token(),
              offset(next),
              offset(persistedNow));
      rescheduleAt = next;
    } else {
      throw new IllegalArgumentException("previousStatus must be QUEUED or RETRY_WAIT");
    }
    if (outcome(affected) != ToolInvocationUpdateOutcome.APPLIED) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    requireTargetAffected(
        executionTargetStore.rescheduleLocked(
            ExecutionTargetKind.TOOL_INVOCATION, invocation.id(), routeKey(row), rescheduleAt),
        "reschedule tool invocation target after releaseUnstarted");
    return ToolInvocationUpdateOutcome.APPLIED;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ToolInvocationUpdateOutcome completeSuccess(
      ClaimedToolInvocation claimed,
      Supplier<ToolResult> resultSupplier,
      Instant lastObservedActivityAt,
      Instant now) {
    Objects.requireNonNull(resultSupplier, "resultSupplier");
    LockedTool locked = lockOwned(claimed, now);
    if (locked == null) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    ToolResult result =
        Objects.requireNonNull(resultSupplier.get(), "resultSupplier returned null");
    if (!claimed.invocation().toolCallId().equals(result.toolCallId())) {
      throw new IllegalArgumentException("result.toolCallId must match the claimed invocation");
    }
    return applyTerminal(
        claimed,
        locked.row(),
        result,
        null,
        lastObservedActivityAt,
        now,
        InvocationStatus.SUCCEEDED);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ToolInvocationUpdateOutcome completeFailure(
      ClaimedToolInvocation claimed,
      ToolInvocationError error,
      Instant lastObservedActivityAt,
      Instant now) {
    return complete(
        claimed,
        null,
        Objects.requireNonNull(error),
        lastObservedActivityAt,
        now,
        InvocationStatus.FAILED);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ToolInvocationUpdateOutcome completeCancelled(
      ClaimedToolInvocation claimed, Instant lastObservedActivityAt, Instant now) {
    return complete(claimed, null, null, lastObservedActivityAt, now, InvocationStatus.CANCELLED);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ToolInvocationUpdateOutcome completeUnknown(
      ClaimedToolInvocation claimed,
      ToolInvocationError error,
      Instant lastObservedActivityAt,
      Instant now) {
    return complete(
        claimed,
        null,
        Objects.requireNonNull(error),
        lastObservedActivityAt,
        now,
        InvocationStatus.UNKNOWN);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ToolInvocationUpdateOutcome scheduleRetry(
      ClaimedToolInvocation claimed,
      Instant nextAttemptAt,
      Instant lastObservedActivityAt,
      Instant now) {
    LockedTool locked = lockOwned(claimed, now);
    if (locked == null) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    ToolInvocationDO row = locked.row();
    Instant effectiveActivity =
        max(row.getLastActivityAt().toInstant(), persistence(lastObservedActivityAt));
    Instant next = persistence(nextAttemptAt);
    if (!next.isAfter(effectiveActivity) || !next.isBefore(row.getDeadlineAt().toInstant())) {
      throw new IllegalArgumentException(
          "nextAttemptAt must be after effective activity and before deadline");
    }
    ToolInvocation invocation = claimed.invocation();
    ToolInvocationUpdateOutcome outcome =
        outcome(
            invocationMapper.scheduleRetry(
                invocation.id(),
                invocation.threadId(),
                invocation.executionEpoch(),
                invocation.attempt(),
                invocation.workerLease().token(),
                offset(lastObservedActivityAt),
                offset(next),
                offset(persistence(now))));
    if (outcome != ToolInvocationUpdateOutcome.APPLIED) {
      return outcome;
    }
    requireTargetAffected(
        executionTargetStore.rescheduleLocked(
            ExecutionTargetKind.TOOL_INVOCATION, invocation.id(), routeKey(row), next),
        "reschedule tool invocation target after scheduleRetry");
    return ToolInvocationUpdateOutcome.APPLIED;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ToolInvocationUpdateOutcome persistPermissionAllowed(
      ClaimedToolInvocation claimed,
      ToolBinding finalBinding,
      String finalArgumentsJson,
      Instant now) {
    Objects.requireNonNull(finalBinding, "finalBinding");
    Objects.requireNonNull(finalArgumentsJson, "finalArgumentsJson");
    LockedTool locked = lockOwned(claimed, now);
    if (locked == null) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    ToolInvocationDO row = locked.row();
    ToolInvocation invocation = claimed.invocation();
    if (invocation.status() != InvocationStatus.RUNNING
        || invocation.permissionState() != ToolPermissionState.PENDING) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    // Route stability: descriptor/arguments may change but the execution route (location and
    // environment name) must not. A permission boundary that re-routes must be rejected before
    // any state mutation.
    if (!routeStable(row, finalBinding)) {
      throw new IllegalArgumentException(
          "persistPermissionAllowed rejected route change: locked row is "
              + row.getLocation()
              + "/"
              + row.getEnvironmentName()
              + " but final binding is "
              + finalBinding.location()
              + "/"
              + finalBinding.environmentName());
    }
    int affected =
        invocationMapper.persistPermissionAllowed(
            invocation.id(),
            invocation.threadId(),
            invocation.executionEpoch(),
            invocation.attempt(),
            invocation.workerLease().token(),
            descriptorCodec.encode(finalBinding.descriptor()),
            finalArgumentsJson,
            finalBinding.location().name(),
            finalBinding.environmentName(),
            offset(persistence(now)));
    if (outcome(affected) != ToolInvocationUpdateOutcome.APPLIED) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    // The target lease is preserved; rescheduleLocked on the same lease deadline keeps the existing
    // gate (and triggers NOTIFY only on a strictly earlier move). Use the locked row's route key
    // so PLATFORM (null) and ENVIRONMENT rows stay on their declared route.
    requireTargetAffected(
        executionTargetStore.rescheduleLocked(
            ExecutionTargetKind.TOOL_INVOCATION,
            invocation.id(),
            routeKey(row),
            invocation.workerLease().until()),
        "reschedule tool invocation target after persistPermissionAllowed");
    return ToolInvocationUpdateOutcome.APPLIED;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ToolInvocationUpdateOutcome awaitPermission(
      ClaimedToolInvocation claimed,
      ToolBinding finalBinding,
      String finalArgumentsJson,
      PermissionPromptPreview prompt,
      Instant now) {
    Objects.requireNonNull(finalBinding, "finalBinding");
    Objects.requireNonNull(finalArgumentsJson, "finalArgumentsJson");
    Objects.requireNonNull(prompt, "prompt");
    LockedTool locked = lockOwned(claimed, now);
    if (locked == null) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    ToolInvocationDO row = locked.row();
    ToolInvocation invocation = claimed.invocation();
    if (invocation.status() != InvocationStatus.RUNNING
        || invocation.permissionState() != ToolPermissionState.PENDING) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    // Route stability: the final binding's location/environment must equal the locked row's.
    if (!routeStable(row, finalBinding)) {
      throw new IllegalArgumentException(
          "awaitPermission rejected route change: locked row is "
              + row.getLocation()
              + "/"
              + row.getEnvironmentName()
              + " but final binding is "
              + finalBinding.location()
              + "/"
              + finalBinding.environmentName());
    }
    int affected =
        invocationMapper.awaitPermission(
            invocation.id(),
            invocation.threadId(),
            invocation.executionEpoch(),
            invocation.attempt(),
            invocation.workerLease().token(),
            descriptorCodec.encode(finalBinding.descriptor()),
            finalArgumentsJson,
            finalBinding.location().name(),
            finalBinding.environmentName(),
            offset(persistence(now)));
    if (outcome(affected) != ToolInvocationUpdateOutcome.APPLIED) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    // Insert exactly one OPEN Tool permission interaction. The DB partial unique index on
    // tool_invocation_id where status = 'OPEN' guarantees the uniqueness invariant; a
    // concurrent insert with the same key would surface as a 0 affected count.
    long interactionId = idGenerator.nextInteractionId();
    String requestJson = encodeToolPermissionRequest(invocation, prompt);
    InteractionDO interactionRow = new InteractionDO();
    interactionRow.setId(interactionId);
    interactionRow.setToolInvocationId(invocation.id());
    interactionRow.setRequestJson(requestJson);
    interactionRow.setStatus("OPEN");
    interactionRow.setCreatedAt(offset(persistence(now)));
    if (interactionMapper.insertOpen(interactionRow) != 1) {
      throw new IllegalStateException(
          "failed to create tool-permission interaction for invocation " + invocation.id());
    }
    // Park the durable target while the prompt is outstanding. WAITING_INTERACTION is a
    // durable queue-member status: activateOldestEnvironment in the FIFO activation SQL
    // filters on status = 'QUEUED' (not on dispatch_enabled), so a parked ASK row at the
    // FIFO head still blocks later siblings and prevents them from being activated while the
    // user response is pending. The locked row's route key is the canonical one for both
    // PLATFORM (null) and ENVIRONMENT rows.
    Instant originalAvailableAt =
        row.getWorkerUntil() == null
            ? persistence(now)
            : max(persistence(now), row.getWorkerUntil().toInstant());
    int parkAffected =
        executionTargetStore.parkLocked(
            ExecutionTargetKind.TOOL_INVOCATION,
            invocation.id(),
            routeKey(row),
            originalAvailableAt);
    if (parkAffected != 1) {
      throw new IllegalStateException(
          "parkLocked tool invocation target after awaitPermission affected " + parkAffected);
    }
    return ToolInvocationUpdateOutcome.APPLIED;
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public ToolInvocationUpdateOutcome denyPermission(ClaimedToolInvocation claimed, Instant now) {
    LockedTool locked = lockOwned(claimed, now);
    if (locked == null) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    ToolInvocationDO row = locked.row();
    ToolInvocation invocation = claimed.invocation();
    if (invocation.status() != InvocationStatus.RUNNING
        || invocation.permissionState() != ToolPermissionState.PENDING) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    Instant persistedNow = persistence(now);
    // Preserve valid actual running clocks: startedAt and deadlineAt are immutable for the
    // lifetime of a RUNNING row, finishedAt is the max of now and lastActivityAt to keep the
    // invariant finishedAt >= lastActivityAt, startedAt <= finishedAt, and finishedAt >=
    // createdAt.
    Instant startedAt = Objects.requireNonNull(row.getStartedAt(), "startedAt").toInstant();
    Instant deadlineAt = Objects.requireNonNull(row.getDeadlineAt(), "deadlineAt").toInstant();
    Instant lastActivityAt =
        Objects.requireNonNull(row.getLastActivityAt(), "lastActivityAt").toInstant();
    Instant finishedAt = max(persistedNow, max(lastActivityAt, startedAt));
    ToolInvocationError error =
        new ToolInvocationError("PERMISSION_DENIED", "Tool permission was denied.");
    int affected =
        invocationMapper.denyPermission(
            invocation.id(),
            invocation.threadId(),
            invocation.executionEpoch(),
            invocation.attempt(),
            invocation.workerLease().token(),
            errorCodec.encode(error),
            offset(startedAt),
            offset(deadlineAt),
            offset(finishedAt),
            offset(persistedNow));
    if (affected != 1) {
      throw new IllegalStateException("denyPermission affected " + affected + " rows");
    }
    if (threadMapper.markRunnable(
            invocation.threadId(), invocation.executionEpoch(), offset(finishedAt))
        != 1) {
      throw new IllegalStateException("cannot mark owning thread runnable after deny");
    }
    requireTargetAffected(
        executionTargetStore.deleteLocked(ExecutionTargetKind.TOOL_INVOCATION, invocation.id()),
        "delete tool invocation target after deny");
    executionTargetStore.schedule(
        ExecutionTargetKind.THREAD, invocation.threadId(), null, persistedNow);
    String route = routeKey(row);
    if (route != null) {
      executionTargetStore.activateOldestEnvironment(route, persistedNow);
    }
    return ToolInvocationUpdateOutcome.APPLIED;
  }

  private static boolean routeStable(ToolInvocationDO row, ToolBinding finalBinding) {
    String rowLocation = row.getLocation();
    String finalLocation = finalBinding.location().name();
    if (!Objects.equals(rowLocation, finalLocation)) {
      return false;
    }
    return Objects.equals(row.getEnvironmentName(), finalBinding.environmentName());
  }

  private ToolInvocationUpdateOutcome complete(
      ClaimedToolInvocation claimed,
      ToolResult result,
      ToolInvocationError error,
      Instant lastObservedActivityAt,
      Instant now,
      InvocationStatus terminalStatus) {
    LockedTool locked = lockOwned(claimed, now);
    if (locked == null) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    return applyTerminal(
        claimed, locked.row(), result, error, lastObservedActivityAt, now, terminalStatus);
  }

  private ToolInvocationUpdateOutcome applyTerminal(
      ClaimedToolInvocation claimed,
      ToolInvocationDO row,
      ToolResult result,
      ToolInvocationError error,
      Instant lastObservedActivityAt,
      Instant now,
      InvocationStatus terminalStatus) {
    Instant observed = persistence(lastObservedActivityAt);
    Instant persistedNow = persistence(now);
    Instant finished = max(persistedNow, max(observed, row.getLastActivityAt().toInstant()));
    ToolInvocation invocation = claimed.invocation();
    int affected;
    if (terminalStatus == InvocationStatus.SUCCEEDED) {
      affected =
          invocationMapper.completeSuccess(
              invocation.id(),
              invocation.threadId(),
              invocation.executionEpoch(),
              invocation.attempt(),
              invocation.workerLease().token(),
              ToolInvocationRowConverter.encodeResult(result),
              offset(observed),
              offset(finished),
              offset(persistedNow));
    } else if (terminalStatus == InvocationStatus.CANCELLED) {
      affected =
          invocationMapper.completeCancelled(
              invocation.id(),
              invocation.threadId(),
              invocation.executionEpoch(),
              invocation.attempt(),
              invocation.workerLease().token(),
              offset(observed),
              offset(finished),
              offset(persistedNow));
    } else {
      affected =
          invocationMapper.completeError(
              invocation.id(),
              invocation.threadId(),
              invocation.executionEpoch(),
              invocation.attempt(),
              invocation.workerLease().token(),
              terminalStatus.name(),
              ToolInvocationRowConverter.encodeError(error),
              offset(observed),
              offset(finished),
              offset(persistedNow));
    }
    if (affected != 1) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    if (threadMapper.markRunnable(
            invocation.threadId(), invocation.executionEpoch(), offset(finished))
        != 1) {
      throw new IllegalStateException("cannot mark owning thread runnable");
    }
    requireTargetAffected(
        executionTargetStore.deleteLocked(ExecutionTargetKind.TOOL_INVOCATION, invocation.id()),
        "delete tool invocation target after terminal write");
    // THREAD target scheduling is best-effort: an earlier due time wins and is acceptable.
    executionTargetStore.schedule(
        ExecutionTargetKind.THREAD, invocation.threadId(), null, persistedNow);
    String route = routeKey(row);
    if (route != null) {
      executionTargetStore.activateOldestEnvironment(route, persistedNow);
    }
    return ToolInvocationUpdateOutcome.APPLIED;
  }

  /**
   * Acquire the lock chain Thread -> ToolInvocation -> target and verify the caller still owns the
   * invocation. Returns {@code null} when ownership has been lost (token/attempt/epoch/lease drift,
   * thread mismatch). Throws {@link IllegalStateException} when the invocation is owned but its
   * target row is missing, so the transaction rolls back rather than silently persisting.
   */
  private LockedTool lockOwned(ClaimedToolInvocation claimed, Instant now) {
    Objects.requireNonNull(claimed, "claimed");
    ToolInvocation invocation = claimed.invocation();
    HarnessModelInvocationThreadDO thread = threadMapper.findForUpdate(invocation.threadId());
    if (thread == null) {
      throw new IllegalStateException("owning thread missing for invocation " + invocation.id());
    }
    if (!Objects.equals(thread.getExecutionEpoch(), invocation.executionEpoch())) {
      return null;
    }
    ToolInvocationDO row = invocationMapper.findForUpdate(invocation.id(), invocation.threadId());
    if (row == null
        || !Objects.equals(row.getExecutionEpoch(), invocation.executionEpoch())
        || !Objects.equals(row.getAttempt(), invocation.attempt())
        || !InvocationStatus.RUNNING.name().equals(row.getStatus())
        || !Objects.equals(row.getWorkerToken(), invocation.workerLease().token())
        || !row.getWorkerUntil().toInstant().isAfter(persistence(now))) {
      return null;
    }
    if (executionTargetStore.lock(ExecutionTargetKind.TOOL_INVOCATION, invocation.id()).isEmpty()) {
      throw new IllegalStateException("owned invocation is missing its target: " + invocation.id());
    }
    return new LockedTool(row);
  }

  private String encodeToolPermissionRequest(
      ToolInvocation invocation, PermissionPromptPreview prompt) {
    ObjectNode node = objectMapper.createObjectNode();
    node.put("invocationId", invocation.id());
    node.put("threadId", invocation.threadId());
    node.put("tool", prompt.tool());
    node.put("workdir", prompt.workdir());
    node.put("arguments", prompt.arguments());
    try {
      return objectMapper.writeValueAsString(node);
    } catch (JsonProcessingException error) {
      throw new IllegalStateException(
          "failed to encode tool-permission request for invocation " + invocation.id(), error);
    }
  }

  private static String routeKey(ToolInvocationDO row) {
    return ToolExecutionLocation.ENVIRONMENT.name().equals(row.getLocation())
        ? row.getEnvironmentName()
        : null;
  }

  private static ToolInvocationUpdateOutcome outcome(int affected) {
    return affected == 1
        ? ToolInvocationUpdateOutcome.APPLIED
        : ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
  }

  private static void requireTargetAffected(int affected, String operation) {
    if (affected != 1) {
      throw new IllegalStateException(operation + " affected " + affected + " rows");
    }
  }

  private static OffsetDateTime offset(Instant value) {
    return ToolInvocationRowConverter.offset(value);
  }

  private static Instant persistence(Instant value) {
    return ToolInvocationRowConverter.persistenceInstant(value);
  }

  private static OffsetDateTime advance(Instant base, Duration duration, String name) {
    Instant target = persistence(base.plus(duration));
    if (!target.isAfter(persistence(base))) {
      throw new IllegalArgumentException(name + " must advance PostgreSQL time by at least 1ms");
    }
    return offset(target);
  }

  private static Instant max(Instant left, Instant right) {
    return left.isAfter(right) ? left : right;
  }

  private static void requireToken(String token) {
    if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
      throw new IllegalArgumentException("workerToken must be non-blank and <= 128 chars");
    }
  }

  private static void requirePositive(long value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative() || value.toMillis() <= 0) {
      throw new IllegalArgumentException(name + " must be at least 1ms");
    }
  }

  private record LockedTool(ToolInvocationDO row) {}
}
