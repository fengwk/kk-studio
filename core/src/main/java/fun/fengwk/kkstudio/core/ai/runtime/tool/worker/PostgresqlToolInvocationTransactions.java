package fun.fengwk.kkstudio.core.ai.runtime.tool.worker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.ai.runtime.execution.EnvironmentToolActivationQueue;
import fun.fengwk.kkstudio.core.ai.runtime.execution.ExecutionActivation;
import fun.fengwk.kkstudio.core.ai.runtime.execution.ExecutionActivationStore;
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
 * PostgreSQL ToolInvocation worker 事务适配器，通过唯一的 {@code harness_execution_activation} 队列驱动持久化调度和 Tool
 * 权限状态机。
 *
 * <p>锁顺序为 Thread → ToolInvocation → 激活记录；每次变更都在同一事务中获取持久化激活行锁。终态变更还会将所属 Thread 标记为
 * runnable，并重新安排持久化 Thread 激活。
 *
 * <p>Tool 权限状态持久化在 invocation 行中，ASK 写入的 OPEN Interaction 也是持久化事实。ASK 必须原子地完成：保存最终计划， 将 permission
 * state 切换为 ASKED，将记录切换为 {@code WAITING_INTERACTION}，清除 worker 时钟，插入恰好一条由 Tool 所有的 OPEN
 * Interaction，并停放持久化激活以保持 FIFO 闸门。ALLOW 必须原子地覆盖最终计划并将 permission state 切换为 ALLOWED。DENY 必须原子地将
 * permission state 切换为 DENIED，将记录以 {@code PERMISSION_DENIED} 错误终止为 FAILED，标记所属 Thread
 * runnable，删除当前激活，安排 Thread 激活，并推进下一个 Environment 队头。
 *
 * <p>目标缺失或尚未到期时，claim 返回 {@link Optional#empty()}；RUNNING/PENDING 记录的 lease 过期时会重置为初始
 * QUEUED/PENDING 状态，将已有激活重新安排到当前时间，并返回 {@link Optional#empty()}，由分发器重新分发。 RUNNING/ALLOWED 的过期 lease
 * 保持现有的保守 UNKNOWN 行为。
 */
@Service
public class PostgresqlToolInvocationTransactions implements ToolInvocationTransactions {

  private static final int MAX_TOKEN_LENGTH = 128;
  private static final Duration DEFAULT_EXECUTION_TIMEOUT = Duration.ofMinutes(5);
  private final PostgresqlToolInvocationMapper invocationMapper;
  private final HarnessModelInvocationThreadMapper threadMapper;
  private final ExecutionActivationStore executionActivationStore;
  private final EnvironmentToolActivationQueue environmentToolActivationQueue;
  private final InteractionMapper interactionMapper;
  private final HarnessIdGenerator idGenerator;
  private final ObjectMapper objectMapper;
  private final ToolDescriptorJsonCodec descriptorCodec = new ToolDescriptorJsonCodec();
  private final ToolInvocationErrorJsonCodec errorCodec = new ToolInvocationErrorJsonCodec();

  public PostgresqlToolInvocationTransactions(
      PostgresqlToolInvocationMapper invocationMapper,
      HarnessModelInvocationThreadMapper threadMapper,
      ExecutionActivationStore executionActivationStore,
      EnvironmentToolActivationQueue environmentToolActivationQueue,
      InteractionMapper interactionMapper,
      HarnessIdGenerator idGenerator,
      ObjectMapper objectMapper) {
    this.invocationMapper = Objects.requireNonNull(invocationMapper, "invocationMapper");
    this.threadMapper = Objects.requireNonNull(threadMapper, "threadMapper");
    this.executionActivationStore =
        Objects.requireNonNull(executionActivationStore, "executionActivationStore");
    this.environmentToolActivationQueue =
        Objects.requireNonNull(environmentToolActivationQueue, "environmentToolActivationQueue");
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

    // 第一步：锁定 Thread 行并预读 epoch。
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

    // 第二步：锁定 ToolInvocation 行并校验，此时不修改任何记录。
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

    // 第三步：校验激活闸门。激活行缺失或尚未到期时，事务不修改任何记录并提交，
    // QUEUED / RETRY_WAIT / RUNNING invocation 保持不变。
    Optional<ExecutionActivation> activation =
        executionActivationStore.lockDue(
            ExecutionTargetKind.TOOL_INVOCATION, invocationId, persistedNow);
    if (activation.isEmpty()) {
      return Optional.empty();
    }
    requireActivationEnvironment(row, activation.orElseThrow());

    // 第四步：状态切换；状态切换现在以激活 lockDue 成功为前提。
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
      // RUNNING/PENDING 的 lease 已过期（或尚未完成 ALLOW 持久化、因此没有成功进行外部 I/O 的预允许 RUNNING）：
      // 可以确定 worker 尚未进入外部 Tool I/O（ALLOW 路径会先持久化 ALLOWED）。将记录重置为初始 QUEUED/PENDING
      // 形态，把激活重新安排到 {@code now}，并返回 Optional.empty()，由分发器重新分发。标准 claim 前置条件会在
      // 下一次尝试时发现刚刚到期的激活。
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
            "PENDING recovery affected " + recoveredAffected + " rows after the activation gate");
      }
      requireActivationAffected(
          executionActivationStore.rescheduleLocked(
              ExecutionTargetKind.TOOL_INVOCATION, invocationId, persistedNow),
          "reschedule tool invocation activation after PENDING recovery");
      return Optional.empty();
    } else {
      // RUNNING/ALLOWED：外部 Tool I/O 可能已经执行；沿用保守的仅 token 重新 claim，并在完成时将记录收敛到 UNKNOWN。
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
          "claim mutated " + affected + " rows after the activation gate passed");
    }

    // 第五步：推进激活并进行最终所有权校验。
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
    requireActivationAffected(
        executionActivationStore.rescheduleLocked(
            ExecutionTargetKind.TOOL_INVOCATION, invocationId, aggregate.workerLease().until()),
        "reschedule tool invocation activation after claim");
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
    requireActivationAffected(
        executionActivationStore.rescheduleLocked(
            ExecutionTargetKind.TOOL_INVOCATION, row.getId(), leaseDeadline),
        "reschedule tool invocation activation after renew");
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
    requireActivationAffected(
        executionActivationStore.rescheduleLocked(
            ExecutionTargetKind.TOOL_INVOCATION, invocation.id(), next),
        "reschedule tool invocation activation after scheduleRetry");
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
    if (!environmentStable(row, finalBinding)) {
      throw new IllegalArgumentException(
          "persistPermissionAllowed rejected environment change: locked row is "
              + row.getEnvironmentName()
              + " but final binding is "
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
            finalBinding.environmentName(),
            offset(persistence(now)));
    if (outcome(affected) != ToolInvocationUpdateOutcome.APPLIED) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    requireActivationAffected(
        executionActivationStore.rescheduleLocked(
            ExecutionTargetKind.TOOL_INVOCATION, invocation.id(), invocation.workerLease().until()),
        "reschedule tool invocation activation after persistPermissionAllowed");
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
    // Environment 稳定性：最终 binding 的 Environment 必须等于已锁定记录的 Environment。
    if (!environmentStable(row, finalBinding)) {
      throw new IllegalArgumentException(
          "awaitPermission rejected environment change: locked row is "
              + row.getEnvironmentName()
              + " but final binding is "
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
            finalBinding.environmentName(),
            offset(persistence(now)));
    if (outcome(affected) != ToolInvocationUpdateOutcome.APPLIED) {
      return ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
    }
    // 插入恰好一条 OPEN Tool 权限 Interaction。数据库中 status = 'OPEN' 条件下按 tool_invocation_id
    // 建立的部分唯一索引保证唯一性；相同 key 的并发插入会表现为受影响行数为 0。
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
    // 权限等待期间将激活记录置为 PARKED。WAITING_INTERACTION 仍是 FIFO 队列成员状态，
    // 专用 FIFO SQL 只允许 QUEUED 队头激活，因此当前队头会继续阻塞后续兄弟。
    Instant originalWakeAt =
        row.getWorkerUntil() == null
            ? persistence(now)
            : max(persistence(now), row.getWorkerUntil().toInstant());
    int parkAffected =
        executionActivationStore.parkLocked(
            ExecutionTargetKind.TOOL_INVOCATION, invocation.id(), originalWakeAt);
    if (parkAffected != 1) {
      throw new IllegalStateException(
          "parkLocked tool invocation activation after awaitPermission affected " + parkAffected);
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
    // 保留有效的实际运行时钟：RUNNING 记录整个生命周期内 startedAt 和 deadlineAt 不可变，finishedAt 取 now
    // 与 lastActivityAt 的较大值，以保持 finishedAt >= lastActivityAt、startedAt <= finishedAt 以及
    // finishedAt >= createdAt。
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
    requireActivationAffected(
        executionActivationStore.deleteLocked(ExecutionTargetKind.TOOL_INVOCATION, invocation.id()),
        "delete tool invocation activation after deny");
    executionActivationStore.schedule(
        ExecutionTargetKind.THREAD, invocation.threadId(), null, persistedNow);
    String environmentName = row.getEnvironmentName();
    if (environmentName != null) {
      environmentToolActivationQueue.activateOldestTool(environmentName, persistedNow);
    }
    return ToolInvocationUpdateOutcome.APPLIED;
  }

  private static boolean environmentStable(ToolInvocationDO row, ToolBinding finalBinding) {
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
    requireActivationAffected(
        executionActivationStore.deleteLocked(ExecutionTargetKind.TOOL_INVOCATION, invocation.id()),
        "delete tool invocation activation after terminal write");
    // THREAD 激活安排遵循 wakeAt 最早优先，已有更早记录时无需报错。
    executionActivationStore.schedule(
        ExecutionTargetKind.THREAD, invocation.threadId(), null, persistedNow);
    String environmentName = row.getEnvironmentName();
    if (environmentName != null) {
      environmentToolActivationQueue.activateOldestTool(environmentName, persistedNow);
    }
    return ToolInvocationUpdateOutcome.APPLIED;
  }

  /**
   * 按 Thread -> ToolInvocation -> 激活记录的顺序加锁，并校验调用方仍拥有该 invocation。token、attempt、epoch、lease 漂移或
   * Thread 不匹配时返回 {@code null}。调用方仍拥有 invocation 但激活行缺失时抛出 {@link
   * IllegalStateException}，使事务回滚而不是静默持久化。
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
    ExecutionActivation activation =
        executionActivationStore
            .lock(ExecutionTargetKind.TOOL_INVOCATION, invocation.id())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "owned invocation is missing its activation: " + invocation.id()));
    requireActivationEnvironment(row, activation);
    return new LockedTool(row);
  }

  private static void requireActivationEnvironment(
      ToolInvocationDO row, ExecutionActivation activation) {
    if (!Objects.equals(row.getEnvironmentName(), activation.environmentName())) {
      throw new IllegalStateException(
          "tool invocation environment does not match activation: " + row.getId());
    }
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

  private static ToolInvocationUpdateOutcome outcome(int affected) {
    return affected == 1
        ? ToolInvocationUpdateOutcome.APPLIED
        : ToolInvocationUpdateOutcome.LOST_OWNERSHIP;
  }

  private static void requireActivationAffected(int affected, String operation) {
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
