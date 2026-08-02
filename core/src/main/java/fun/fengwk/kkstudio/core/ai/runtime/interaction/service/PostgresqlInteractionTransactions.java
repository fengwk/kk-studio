package fun.fengwk.kkstudio.core.ai.runtime.interaction.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.ai.runtime.execution.ActivationState;
import fun.fengwk.kkstudio.core.ai.runtime.execution.EnvironmentToolActivationQueue;
import fun.fengwk.kkstudio.core.ai.runtime.execution.ExecutionActivation;
import fun.fengwk.kkstudio.core.ai.runtime.execution.ExecutionActivationStore;
import fun.fengwk.kkstudio.core.ai.runtime.interaction.store.mapper.InteractionMapper;
import fun.fengwk.kkstudio.core.ai.runtime.interaction.store.mapper.InteractionThreadMapper;
import fun.fengwk.kkstudio.core.ai.runtime.interaction.store.mapper.InteractionToolOwnerMapper;
import fun.fengwk.kkstudio.core.ai.runtime.interaction.store.model.InteractionDO;
import fun.fengwk.kkstudio.core.ai.runtime.interaction.store.model.InteractionToolOwnerDO;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.interaction.Interaction;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionResponse;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionStatus;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionTransactions;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionTransition;
import fun.fengwk.kkstudio.harness.runtime.interaction.ToolPermissionDecision;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationErrorJsonCodec;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** PostgreSQL Tool 权限 Interaction 持久化事务适配器。 */
@Service
public class PostgresqlInteractionTransactions implements InteractionTransactions {
  static final String TOOL_PERMISSION_DENIED_ERROR_JSON =
      new ToolInvocationErrorJsonCodec()
          .encode(new ToolInvocationError("PERMISSION_DENIED", "Tool permission was denied."));

  private final InteractionMapper interactionMapper;
  private final InteractionThreadMapper threadMapper;
  private final InteractionToolOwnerMapper toolMapper;
  private final ExecutionActivationStore executionActivationStore;
  private final EnvironmentToolActivationQueue environmentToolActivationQueue;

  public PostgresqlInteractionTransactions(
      InteractionMapper interactionMapper,
      InteractionThreadMapper threadMapper,
      InteractionToolOwnerMapper toolMapper,
      ExecutionActivationStore executionActivationStore,
      EnvironmentToolActivationQueue environmentToolActivationQueue) {
    this.interactionMapper = Objects.requireNonNull(interactionMapper, "interactionMapper");
    this.threadMapper = Objects.requireNonNull(threadMapper, "threadMapper");
    this.toolMapper = Objects.requireNonNull(toolMapper, "toolMapper");
    this.executionActivationStore =
        Objects.requireNonNull(executionActivationStore, "executionActivationStore");
    this.environmentToolActivationQueue =
        Objects.requireNonNull(environmentToolActivationQueue, "environmentToolActivationQueue");
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Interaction> find(long interactionId) {
    validateId(interactionId);
    InteractionDO row = interactionMapper.find(interactionId);
    return row == null ? Optional.empty() : Optional.of(InteractionRowConverter.toAggregate(row));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Interaction> findOpenByToolInvocation(long toolInvocationId) {
    validateToolInvocationId(toolInvocationId);
    InteractionDO row = interactionMapper.findOpenByToolInvocation(toolInvocationId);
    return row == null ? Optional.empty() : Optional.of(InteractionRowConverter.toAggregate(row));
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public InteractionTransition resolve(
      long interactionId,
      long expectedVersion,
      InteractionResponse response,
      ToolPermissionDecision decision,
      Instant resolvedAt) {
    validateIdAndVersion(interactionId, expectedVersion);
    Objects.requireNonNull(response, "response");
    Objects.requireNonNull(decision, "decision");
    Instant terminalAt = Objects.requireNonNull(resolvedAt, "resolvedAt");

    // 在获取约定的全局锁顺序前，只读取不可变的所有者标识。
    Interaction unlocked = peekInteraction(interactionId);
    PermissionOwnerLock ownerLock = lockPermissionOwner(unlocked.toolInvocationId());
    Interaction current = lockOpen(interactionId, expectedVersion);
    if (current.toolInvocationId() != ownerLock.tool().getId()) {
      throw new IllegalStateException("interaction tool invocation changed: " + interactionId);
    }

    if (decision == ToolPermissionDecision.APPROVE) {
      approveToolPermission(ownerLock, terminalAt);
    } else {
      denyToolPermission(ownerLock, terminalAt);
    }
    if (interactionMapper.resolve(
            interactionId,
            expectedVersion,
            response.json(),
            InteractionRowConverter.toUtcOffsetDateTime(terminalAt))
        != 1) {
      throw new IllegalStateException("interaction resolution lost race: " + interactionId);
    }
    return new InteractionTransition(resolved(current, response, terminalAt));
  }

  private Interaction peekInteraction(long interactionId) {
    InteractionDO row = interactionMapper.find(interactionId);
    if (row == null) {
      throw new IllegalArgumentException("unknown interaction: " + interactionId);
    }
    return InteractionRowConverter.toAggregate(row);
  }

  /**
   * 按 Thread → ToolInvocation → ExecutionActivation 顺序加锁，只使用数据库中的持久化事实。 Request JSON
   * 仅用于审计，不参与选择变更对象。
   */
  private PermissionOwnerLock lockPermissionOwner(long toolInvocationId) {
    InteractionToolOwnerDO peek = toolMapper.find(toolInvocationId);
    if (peek == null) {
      throw new IllegalStateException("owning tool invocation missing: " + toolInvocationId);
    }
    long threadId = Objects.requireNonNull(peek.getThreadId(), "tool threadId");
    long threadEpoch = lockThread(threadId);
    InteractionToolOwnerDO tool = toolMapper.findForUpdate(toolInvocationId, threadId);
    if (tool == null) {
      throw new IllegalStateException("owning tool invocation changed: " + toolInvocationId);
    }
    if (!Objects.equals(tool.getExecutionEpoch(), threadEpoch)) {
      throw new IllegalStateException(
          "tool permission owner execution epoch no longer matches its thread: "
              + toolInvocationId);
    }
    if (!"WAITING_INTERACTION".equals(tool.getStatus())
        || !"ASKED".equals(tool.getPermissionState())) {
      throw new IllegalStateException(
          "tool permission owner is not WAITING_INTERACTION/ASKED: " + toolInvocationId);
    }
    ExecutionActivation activation =
        executionActivationStore
            .lock(ExecutionTargetKind.TOOL_INVOCATION, toolInvocationId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "tool permission owner is missing its activation: " + toolInvocationId));
    requireParkedPermissionActivation(tool, activation);
    return new PermissionOwnerLock(tool, activation);
  }

  private void approveToolPermission(PermissionOwnerLock ownerLock, Instant transitionAt) {
    InteractionToolOwnerDO tool = ownerLock.tool();
    if (toolMapper.approveAsked(tool.getId()) != 1) {
      throw new IllegalStateException("tool permission approval lost race: " + tool.getId());
    }
    Instant wakeAt = persisted(transitionAt);
    ExecutionActivation activation = ownerLock.activation();
    requireActivationAffected(
        executionActivationStore.rescheduleLocked(
            ExecutionTargetKind.TOOL_INVOCATION, tool.getId(), wakeAt),
        "reschedule approved tool permission activation");
    if (activation.environmentName() == null) {
      requireActivationAffected(
          executionActivationStore.activateLocked(
              ExecutionTargetKind.TOOL_INVOCATION, tool.getId(), wakeAt),
          "activate approved platform tool activation");
      return;
    }
    environmentToolActivationQueue.activateOldestTool(activation.environmentName(), wakeAt);
  }

  private void denyToolPermission(PermissionOwnerLock ownerLock, Instant transitionAt) {
    InteractionToolOwnerDO tool = ownerLock.tool();
    Instant terminalAt = persisted(transitionAt);
    Instant startedAt = max(terminalAt, tool.getCreatedAt().toInstant());
    Instant deadlineAt = startedAt.plus(Duration.ofMillis(1));
    if (toolMapper.denyAsked(
            tool.getId(),
            InteractionRowConverter.toUtcOffsetDateTime(startedAt),
            InteractionRowConverter.toUtcOffsetDateTime(deadlineAt),
            InteractionRowConverter.toUtcOffsetDateTime(startedAt),
            TOOL_PERMISSION_DENIED_ERROR_JSON)
        != 1) {
      throw new IllegalStateException("tool permission denial lost race: " + tool.getId());
    }
    setThreadRunnable(tool.getThreadId(), true, terminalAt);
    requireActivationAffected(
        executionActivationStore.deleteLocked(ExecutionTargetKind.TOOL_INVOCATION, tool.getId()),
        "delete denied tool permission activation");
    executionActivationStore.schedule(
        ExecutionTargetKind.THREAD, tool.getThreadId(), null, terminalAt);
    String environmentName = ownerLock.activation().environmentName();
    if (environmentName != null) {
      environmentToolActivationQueue.activateOldestTool(environmentName, terminalAt);
    }
  }

  private long lockThread(long threadId) {
    Long executionEpoch = threadMapper.findExecutionEpochForUpdate(threadId);
    if (executionEpoch == null) {
      throw new IllegalStateException("owning thread missing: " + threadId);
    }
    return executionEpoch;
  }

  private void setThreadRunnable(long threadId, boolean runnable, Instant transitionAt) {
    if (threadMapper.setRunnable(
            threadId, runnable, InteractionRowConverter.toUtcOffsetDateTime(transitionAt))
        != 1) {
      throw new IllegalStateException("owning thread changed: " + threadId);
    }
  }

  private static void requireParkedPermissionActivation(
      InteractionToolOwnerDO tool, ExecutionActivation activation) {
    if (activation.activationState() != ActivationState.PARKED) {
      throw new IllegalStateException(
          "tool permission activation must be parked while interaction is open: " + tool.getId());
    }
    String expectedEnvironment = tool.getEnvironmentName();
    if (!Objects.equals(expectedEnvironment, activation.environmentName())) {
      throw new IllegalStateException(
          "tool permission activation environment does not match invocation: " + tool.getId());
    }
  }

  private static void requireActivationAffected(int affected, String operation) {
    if (affected != 1) {
      throw new IllegalStateException(operation + " affected " + affected + " rows");
    }
  }

  private static Instant persisted(Instant instant) {
    return InteractionRowConverter.toUtcOffsetDateTime(instant).toInstant();
  }

  private static Instant max(Instant first, Instant second) {
    return first.isAfter(second) ? first : second;
  }

  private record PermissionOwnerLock(InteractionToolOwnerDO tool, ExecutionActivation activation) {}

  private Interaction lockOpen(long interactionId, long expectedVersion) {
    InteractionDO row = interactionMapper.findForUpdate(interactionId);
    if (row == null) {
      throw new IllegalArgumentException("unknown interaction: " + interactionId);
    }
    Interaction current = InteractionRowConverter.toAggregate(row);
    if (current.version() != expectedVersion) {
      throw new IllegalStateException("interaction version mismatch");
    }
    if (current.status() != InteractionStatus.OPEN) {
      throw new IllegalStateException("interaction is not open");
    }
    return current;
  }

  private static Interaction resolved(
      Interaction current, InteractionResponse response, Instant resolvedAt) {
    return new Interaction(
        current.id(),
        current.toolInvocationId(),
        current.request(),
        InteractionStatus.RESOLVED,
        response,
        current.version() + 1,
        current.createdAt(),
        resolvedAt);
  }

  private static void validateId(long interactionId) {
    if (interactionId <= 0) {
      throw new IllegalArgumentException("interactionId must be positive");
    }
  }

  private static void validateToolInvocationId(long toolInvocationId) {
    if (toolInvocationId <= 0) {
      throw new IllegalArgumentException("toolInvocationId must be positive");
    }
  }

  private static void validateIdAndVersion(long interactionId, long expectedVersion) {
    validateId(interactionId);
    if (expectedVersion < 0) {
      throw new IllegalArgumentException("expectedVersion must not be negative");
    }
  }
}
