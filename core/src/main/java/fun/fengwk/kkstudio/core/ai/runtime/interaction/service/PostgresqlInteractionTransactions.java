package fun.fengwk.kkstudio.core.ai.runtime.interaction.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.ai.runtime.execution.ExecutionTargetRow;
import fun.fengwk.kkstudio.core.ai.runtime.execution.ExecutionTargetStore;
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

/** PostgreSQL transaction adapter for the sole durable Tool permission Interaction product. */
@Service
public class PostgresqlInteractionTransactions implements InteractionTransactions {
  static final String TOOL_PERMISSION_DENIED_ERROR_JSON =
      new ToolInvocationErrorJsonCodec()
          .encode(new ToolInvocationError("PERMISSION_DENIED", "Tool permission was denied."));

  private final InteractionMapper interactionMapper;
  private final InteractionThreadMapper threadMapper;
  private final InteractionToolOwnerMapper toolMapper;
  private final ExecutionTargetStore executionTargetStore;

  public PostgresqlInteractionTransactions(
      InteractionMapper interactionMapper,
      InteractionThreadMapper threadMapper,
      InteractionToolOwnerMapper toolMapper,
      ExecutionTargetStore executionTargetStore) {
    this.interactionMapper = Objects.requireNonNull(interactionMapper, "interactionMapper");
    this.threadMapper = Objects.requireNonNull(threadMapper, "threadMapper");
    this.toolMapper = Objects.requireNonNull(toolMapper, "toolMapper");
    this.executionTargetStore =
        Objects.requireNonNull(executionTargetStore, "executionTargetStore");
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

    // Read only the immutable owner id before acquiring the documented global lock order.
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
   * Locks Thread → ToolInvocation → ExecutionTarget using only durable database facts. Request JSON
   * is audit data and never selects a mutation target.
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
    ExecutionTargetRow target =
        executionTargetStore
            .lock(ExecutionTargetKind.TOOL_INVOCATION, toolInvocationId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "tool permission owner is missing its target: " + toolInvocationId));
    requireParkedPermissionTarget(tool, target);
    return new PermissionOwnerLock(tool, target);
  }

  private void approveToolPermission(PermissionOwnerLock ownerLock, Instant transitionAt) {
    InteractionToolOwnerDO tool = ownerLock.tool();
    if (toolMapper.approveAsked(tool.getId()) != 1) {
      throw new IllegalStateException("tool permission approval lost race: " + tool.getId());
    }
    Instant availableAt = persisted(transitionAt);
    ExecutionTargetRow target = ownerLock.target();
    requireTargetAffected(
        executionTargetStore.rescheduleLocked(
            ExecutionTargetKind.TOOL_INVOCATION, tool.getId(), target.routeKey(), availableAt),
        "reschedule approved tool permission target");
    if (target.routeKey() == null) {
      requireTargetAffected(
          executionTargetStore.activateLocked(
              ExecutionTargetKind.TOOL_INVOCATION, tool.getId(), null, availableAt),
          "activate approved platform tool target");
      return;
    }
    executionTargetStore.activateOldestEnvironment(target.routeKey(), availableAt);
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
    requireTargetAffected(
        executionTargetStore.deleteLocked(ExecutionTargetKind.TOOL_INVOCATION, tool.getId()),
        "delete denied tool permission target");
    executionTargetStore.schedule(ExecutionTargetKind.THREAD, tool.getThreadId(), null, terminalAt);
    String routeKey = ownerLock.target().routeKey();
    if (routeKey != null) {
      executionTargetStore.activateOldestEnvironment(routeKey, terminalAt);
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

  private static void requireParkedPermissionTarget(
      InteractionToolOwnerDO tool, ExecutionTargetRow target) {
    if (target.dispatchEnabled()) {
      throw new IllegalStateException(
          "tool permission target must be parked while interaction is open: " + tool.getId());
    }
    String expectedRoute = tool.getEnvironmentName();
    if (!Objects.equals(expectedRoute, target.routeKey())) {
      throw new IllegalStateException(
          "tool permission target route does not match invocation: " + tool.getId());
    }
  }

  private static void requireTargetAffected(int affected, String operation) {
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

  private record PermissionOwnerLock(InteractionToolOwnerDO tool, ExecutionTargetRow target) {}

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
