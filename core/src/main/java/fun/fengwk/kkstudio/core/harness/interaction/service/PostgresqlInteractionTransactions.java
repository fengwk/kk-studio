package fun.fengwk.kkstudio.core.harness.interaction.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.harness.execution.ExecutionTargetRow;
import fun.fengwk.kkstudio.core.harness.execution.ExecutionTargetStore;
import fun.fengwk.kkstudio.core.harness.interaction.store.mapper.InteractionMapper;
import fun.fengwk.kkstudio.core.harness.interaction.store.mapper.InteractionOwnerThreadMapper;
import fun.fengwk.kkstudio.core.harness.interaction.store.mapper.InteractionToolOwnerMapper;
import fun.fengwk.kkstudio.core.harness.interaction.store.model.InteractionDO;
import fun.fengwk.kkstudio.core.harness.interaction.store.model.InteractionToolOwnerDO;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.interaction.Interaction;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionCreate;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionOwnerAction;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionOwnerDirective;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionResolution;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionResponse;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionStatus;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionTransactions;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionTransition;
import fun.fengwk.kkstudio.harness.runtime.permission.ToolPermissionInteraction;
import fun.fengwk.kkstudio.harness.runtime.port.HarnessIdGenerator;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolExecutionLocation;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationError;
import fun.fengwk.kkstudio.harness.runtime.tool.ToolInvocationErrorJsonCodec;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL implementation of durable Interaction transactions.
 *
 * <p>Each terminal mutation locks the fact, validates id/version/OPEN state, then advances exactly
 * once. Tool permission interactions lock Thread → ToolInvocation → ExecutionTarget, so approval
 * atomically re-enables only the permitted Tool target through the FIFO route gate and denial /
 * expiry atomically terminalizes the Tool, deletes its target, schedules the owning Thread and
 * activates the next environment head. An already expired response is terminalized as {@code
 * EXPIRED} without applying its handler resolution.
 */
@Service
public class PostgresqlInteractionTransactions implements InteractionTransactions {
  /**
   * Canonical durable error payload written when a Tool permission Interaction is denied or
   * expires.
   */
  static final String TOOL_PERMISSION_DENIED_ERROR_JSON =
      new ToolInvocationErrorJsonCodec()
          .encode(new ToolInvocationError("PERMISSION_DENIED", "Tool permission was denied."));

  private final InteractionMapper interactionMapper;
  private final InteractionOwnerThreadMapper threadMapper;
  private final InteractionToolOwnerMapper toolMapper;
  private final ExecutionTargetStore executionTargetStore;
  private final HarnessIdGenerator idGenerator;

  public PostgresqlInteractionTransactions(
      InteractionMapper interactionMapper,
      InteractionOwnerThreadMapper threadMapper,
      InteractionToolOwnerMapper toolMapper,
      ExecutionTargetStore executionTargetStore,
      HarnessIdGenerator idGenerator) {
    this.interactionMapper = Objects.requireNonNull(interactionMapper, "interactionMapper");
    this.threadMapper = Objects.requireNonNull(threadMapper, "threadMapper");
    this.toolMapper = Objects.requireNonNull(toolMapper, "toolMapper");
    this.executionTargetStore =
        Objects.requireNonNull(executionTargetStore, "executionTargetStore");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Interaction create(InteractionCreate create) {
    Objects.requireNonNull(create, "create");
    OwnerLock ownerLock = lockThreadOwner(create.owner(), create.ownerDirective());
    applySuspension(ownerLock, create.ownerDirective(), create.createdAt());
    long id = idGenerator.nextInteractionId();
    Interaction interaction =
        new Interaction(
            id,
            create.owner(),
            create.handlerType(),
            create.request(),
            InteractionStatus.OPEN,
            null,
            create.expiresAt(),
            0,
            create.createdAt(),
            null);
    InteractionDO row = new InteractionDO();
    row.setId(id);
    row.setOwnerKind(interaction.owner().kind().name());
    row.setOwnerId(interaction.owner().id());
    row.setHandlerType(interaction.handlerType());
    row.setRequestJson(interaction.request().json());
    row.setStatus(interaction.status().name());
    row.setExpiresAt(
        interaction.expiresAt() == null
            ? null
            : InteractionRowConverter.toUtcOffsetDateTime(interaction.expiresAt()));
    row.setCreatedAt(InteractionRowConverter.toUtcOffsetDateTime(interaction.createdAt()));
    if (interactionMapper.insertOpen(row) != 1) {
      throw new IllegalStateException("failed to create interaction " + id);
    }
    return interaction;
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
  public Optional<Interaction> findOpenByOwner(ExecutionTarget owner) {
    Objects.requireNonNull(owner, "owner");
    InteractionDO row = interactionMapper.findOpenByOwner(owner.kind().name(), owner.id());
    return row == null ? Optional.empty() : Optional.of(InteractionRowConverter.toAggregate(row));
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public InteractionTransition resolve(
      long interactionId,
      long expectedVersion,
      InteractionResponse response,
      InteractionResolution resolution,
      Instant resolvedAt) {
    validateIdAndVersion(interactionId, expectedVersion);
    Objects.requireNonNull(response, "response");
    Objects.requireNonNull(resolution, "resolution");
    Interaction current = peekInteraction(interactionId);
    Instant terminalAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
    OwnerLock ownerLock = lockOwner(current, resolution.ownerDirective());
    current = lockOpen(interactionId, expectedVersion);
    if (isExpired(current, terminalAt)) {
      return expireLockedDefault(current, terminalAt);
    }
    applyTerminalOwner(ownerLock, resolution.ownerDirective(), resolution.nextTarget(), terminalAt);
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

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public InteractionTransition cancel(
      long interactionId,
      long expectedVersion,
      InteractionOwnerDirective ownerDirective,
      ExecutionTarget nextTarget,
      Instant resolvedAt) {
    validateIdAndVersion(interactionId, expectedVersion);
    Objects.requireNonNull(ownerDirective, "ownerDirective");
    Objects.requireNonNull(nextTarget, "nextTarget");
    Interaction current = peekInteraction(interactionId);
    Instant terminalAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
    OwnerLock ownerLock = lockOwner(current, ownerDirective);
    current = lockOpen(interactionId, expectedVersion);
    applyTerminalOwner(ownerLock, ownerDirective, nextTarget, terminalAt);
    if (interactionMapper.terminalizeWithoutResponse(
            interactionId,
            expectedVersion,
            InteractionStatus.CANCELLED.name(),
            InteractionRowConverter.toUtcOffsetDateTime(terminalAt))
        != 1) {
      throw new IllegalStateException("interaction cancellation lost race: " + interactionId);
    }
    return new InteractionTransition(cancelled(current, terminalAt));
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public InteractionTransition expire(
      long interactionId, long expectedVersion, Instant resolvedAt) {
    validateIdAndVersion(interactionId, expectedVersion);
    Interaction current = peekInteraction(interactionId);
    Instant terminalAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
    InteractionOwnerDirective ownerDirective = defaultExpirationDirective(current);
    OwnerLock ownerLock = lockOwner(current, ownerDirective);
    current = lockOpen(interactionId, expectedVersion);
    if (!isExpired(current, terminalAt)) {
      throw new IllegalStateException("interaction has not expired");
    }
    return expireLocked(
        current, ownerLock, ownerDirective, defaultExpirationNextTarget(ownerLock), terminalAt);
  }

  private InteractionTransition expireLocked(
      Interaction current,
      OwnerLock ownerLock,
      InteractionOwnerDirective ownerDirective,
      ExecutionTarget nextTarget,
      Instant terminalAt) {
    applyTerminalOwner(ownerLock, ownerDirective, nextTarget, terminalAt);
    if (interactionMapper.terminalizeWithoutResponse(
            current.id(),
            current.version(),
            InteractionStatus.EXPIRED.name(),
            InteractionRowConverter.toUtcOffsetDateTime(terminalAt))
        != 1) {
      throw new IllegalStateException("interaction expiration lost race: " + current.id());
    }
    return new InteractionTransition(expired(current, terminalAt));
  }

  private InteractionTransition expireLockedDefault(Interaction current, Instant terminalAt) {
    InteractionOwnerDirective ownerDirective = defaultExpirationDirective(current);
    OwnerLock ownerLock = lockOwner(current, ownerDirective);
    return expireLocked(
        current, ownerLock, ownerDirective, defaultExpirationNextTarget(ownerLock), terminalAt);
  }

  private static InteractionOwnerDirective defaultExpirationDirective(Interaction interaction) {
    return switch (interaction.owner().kind()) {
      case THREAD -> new InteractionOwnerDirective(InteractionOwnerAction.RESUME_THREAD);
      case TOOL_INVOCATION -> {
        requireToolPermissionHandler(interaction);
        yield new InteractionOwnerDirective(InteractionOwnerAction.DENY_TOOL_PERMISSION);
      }
      case MODEL_INVOCATION -> throw new IllegalArgumentException(
          "interaction owner action is unsupported for MODEL_INVOCATION");
    };
  }

  private static ExecutionTarget defaultExpirationNextTarget(OwnerLock ownerLock) {
    if (ownerLock.owner().kind() == ExecutionTargetKind.THREAD) {
      return ownerLock.owner();
    }
    return new ExecutionTarget(ExecutionTargetKind.THREAD, requireTool(ownerLock).getThreadId());
  }

  private Interaction peekInteraction(long interactionId) {
    InteractionDO row = interactionMapper.find(interactionId);
    if (row == null) {
      throw new IllegalArgumentException("unknown interaction: " + interactionId);
    }
    return InteractionRowConverter.toAggregate(row);
  }

  private OwnerLock lockThreadOwner(
      ExecutionTarget owner, InteractionOwnerDirective ownerDirective) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(ownerDirective, "ownerDirective");
    if (owner.kind() != ExecutionTargetKind.THREAD) {
      throw new IllegalArgumentException(
          "interaction creation only supports THREAD owners, but was " + owner.kind());
    }
    requireThreadAction(ownerDirective.action());
    lockThread(owner.id());
    return new OwnerLock(owner, null, null);
  }

  /**
   * Locks the owner facts in the global order Thread → ToolInvocation → ExecutionTarget. Tool
   * permission interactions are created only by the Tool worker after it has parked a
   * WAITING_INTERACTION/ASKED target, so any deviation is a durable invariant breach.
   */
  private OwnerLock lockOwner(Interaction interaction, InteractionOwnerDirective ownerDirective) {
    Objects.requireNonNull(interaction, "interaction");
    Objects.requireNonNull(ownerDirective, "ownerDirective");
    ExecutionTarget owner = interaction.owner();
    if (owner.kind() == ExecutionTargetKind.THREAD) {
      requireThreadAction(ownerDirective.action());
      lockThread(owner.id());
      return new OwnerLock(owner, null, null);
    }
    if (owner.kind() != ExecutionTargetKind.TOOL_INVOCATION) {
      throw new IllegalArgumentException(
          "interaction owner action is unsupported for " + owner.kind());
    }
    requireToolPermissionHandler(interaction);
    requireToolAction(ownerDirective.action());
    InteractionToolOwnerDO peek = toolMapper.find(owner.id());
    if (peek == null) {
      throw new IllegalStateException("owning tool invocation missing: " + owner.id());
    }
    long threadId = Objects.requireNonNull(peek.getThreadId(), "tool threadId");
    long threadEpoch = lockThread(threadId);
    InteractionToolOwnerDO tool = toolMapper.findForUpdate(owner.id(), threadId);
    if (tool == null) {
      throw new IllegalStateException("owning tool invocation changed: " + owner.id());
    }
    if (!Objects.equals(tool.getExecutionEpoch(), threadEpoch)) {
      throw new IllegalStateException(
          "tool permission owner execution epoch no longer matches its thread: " + owner.id());
    }
    if (!"WAITING_INTERACTION".equals(tool.getStatus())
        || !"ASKED".equals(tool.getPermissionState())) {
      throw new IllegalStateException(
          "tool permission owner is not WAITING_INTERACTION/ASKED: " + owner.id());
    }
    ExecutionTargetRow target =
        executionTargetStore
            .lock(ExecutionTargetKind.TOOL_INVOCATION, owner.id())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "tool permission owner is missing its target: " + owner.id()));
    requireParkedPermissionTarget(tool, target);
    return new OwnerLock(owner, tool, target);
  }

  private void applySuspension(
      OwnerLock ownerLock, InteractionOwnerDirective ownerDirective, Instant transitionAt) {
    if (ownerDirective.action() == InteractionOwnerAction.SUSPEND_THREAD
        && ownerLock.owner().kind() == ExecutionTargetKind.THREAD) {
      setThreadRunnable(ownerLock.owner().id(), false, transitionAt);
      return;
    }
    throw new IllegalArgumentException("interaction suspension directive does not match owner");
  }

  private void applyTerminalOwner(
      OwnerLock ownerLock,
      InteractionOwnerDirective ownerDirective,
      ExecutionTarget nextTarget,
      Instant transitionAt) {
    InteractionOwnerAction action = ownerDirective.action();
    if (action == InteractionOwnerAction.RESUME_THREAD
        && ownerLock.owner().kind() == ExecutionTargetKind.THREAD) {
      requireNextTarget(nextTarget, ExecutionTargetKind.THREAD, ownerLock.owner().id());
      setThreadRunnable(ownerLock.owner().id(), true, transitionAt);
      return;
    }
    if (action == InteractionOwnerAction.APPROVE_TOOL_PERMISSION
        && ownerLock.owner().kind() == ExecutionTargetKind.TOOL_INVOCATION) {
      approveToolPermission(ownerLock, nextTarget, transitionAt);
      return;
    }
    if (action == InteractionOwnerAction.DENY_TOOL_PERMISSION
        && ownerLock.owner().kind() == ExecutionTargetKind.TOOL_INVOCATION) {
      denyToolPermission(ownerLock, nextTarget, transitionAt);
      return;
    }
    throw new IllegalArgumentException("interaction terminal directive does not match owner");
  }

  private void approveToolPermission(
      OwnerLock ownerLock, ExecutionTarget nextTarget, Instant transitionAt) {
    InteractionToolOwnerDO tool = requireTool(ownerLock);
    requireNextTarget(nextTarget, ExecutionTargetKind.TOOL_INVOCATION, tool.getId());
    if (toolMapper.approveAsked(tool.getId()) != 1) {
      throw new IllegalStateException("tool permission approval lost race: " + tool.getId());
    }
    Instant availableAt = persisted(transitionAt);
    ExecutionTargetRow target = requireTarget(ownerLock);
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
    // A waiting permission target is a FIFO route member. Re-run the shared FIFO activation query
    // after its row becomes QUEUED; it enables this head only when no older nonterminal member
    // exists, never allowing the approval path to bypass another route member.
    executionTargetStore.activateOldestEnvironment(target.routeKey(), availableAt);
  }

  private void denyToolPermission(
      OwnerLock ownerLock, ExecutionTarget nextTarget, Instant transitionAt) {
    InteractionToolOwnerDO tool = requireTool(ownerLock);
    requireNextTarget(nextTarget, ExecutionTargetKind.THREAD, tool.getThreadId());
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
    String routeKey = requireTarget(ownerLock).routeKey();
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

  private static void requireThreadAction(InteractionOwnerAction action) {
    if (action != InteractionOwnerAction.SUSPEND_THREAD
        && action != InteractionOwnerAction.RESUME_THREAD) {
      throw new IllegalArgumentException("interaction directive is unsupported for THREAD owner");
    }
  }

  private static void requireToolAction(InteractionOwnerAction action) {
    if (action != InteractionOwnerAction.APPROVE_TOOL_PERMISSION
        && action != InteractionOwnerAction.DENY_TOOL_PERMISSION) {
      throw new IllegalArgumentException(
          "interaction directive is unsupported for TOOL_INVOCATION owner");
    }
  }

  private static void requireToolPermissionHandler(Interaction interaction) {
    if (!ToolPermissionInteraction.HANDLER_TYPE.equals(interaction.handlerType())) {
      throw new IllegalArgumentException(
          "unsupported TOOL_INVOCATION interaction handler: " + interaction.handlerType());
    }
  }

  private static void requireParkedPermissionTarget(
      InteractionToolOwnerDO tool, ExecutionTargetRow target) {
    if (target.dispatchEnabled()) {
      throw new IllegalStateException(
          "tool permission target must be parked while interaction is open: " + tool.getId());
    }
    String expectedRoute =
        ToolExecutionLocation.ENVIRONMENT.name().equals(tool.getLocation())
            ? tool.getEnvironmentName()
            : null;
    if (!Objects.equals(expectedRoute, target.routeKey())) {
      throw new IllegalStateException(
          "tool permission target route does not match invocation: " + tool.getId());
    }
  }

  private static void requireNextTarget(
      ExecutionTarget nextTarget, ExecutionTargetKind expectedKind, long expectedId) {
    if (nextTarget.kind() != expectedKind || nextTarget.id() != expectedId) {
      throw new IllegalArgumentException(
          "interaction directive returned an incompatible nextTarget");
    }
  }

  private static InteractionToolOwnerDO requireTool(OwnerLock ownerLock) {
    return Objects.requireNonNull(ownerLock.tool(), "tool owner");
  }

  private static ExecutionTargetRow requireTarget(OwnerLock ownerLock) {
    return Objects.requireNonNull(ownerLock.target(), "tool target");
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

  private record OwnerLock(
      ExecutionTarget owner, InteractionToolOwnerDO tool, ExecutionTargetRow target) {}

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

  private static boolean isExpired(Interaction interaction, Instant terminalAt) {
    return interaction.expiresAt() != null && !terminalAt.isBefore(interaction.expiresAt());
  }

  private static Interaction resolved(
      Interaction current, InteractionResponse response, Instant resolvedAt) {
    return new Interaction(
        current.id(),
        current.owner(),
        current.handlerType(),
        current.request(),
        InteractionStatus.RESOLVED,
        response,
        current.expiresAt(),
        current.version() + 1,
        current.createdAt(),
        resolvedAt);
  }

  private static Interaction cancelled(Interaction current, Instant resolvedAt) {
    return new Interaction(
        current.id(),
        current.owner(),
        current.handlerType(),
        current.request(),
        InteractionStatus.CANCELLED,
        null,
        current.expiresAt(),
        current.version() + 1,
        current.createdAt(),
        resolvedAt);
  }

  private static Interaction expired(Interaction current, Instant resolvedAt) {
    return new Interaction(
        current.id(),
        current.owner(),
        current.handlerType(),
        current.request(),
        InteractionStatus.EXPIRED,
        null,
        current.expiresAt(),
        current.version() + 1,
        current.createdAt(),
        resolvedAt);
  }

  private static void validateId(long interactionId) {
    if (interactionId <= 0) {
      throw new IllegalArgumentException("interactionId must be positive");
    }
  }

  private static void validateIdAndVersion(long interactionId, long expectedVersion) {
    validateId(interactionId);
    if (expectedVersion < 0) {
      throw new IllegalArgumentException("expectedVersion must not be negative");
    }
  }
}
