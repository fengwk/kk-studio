package fun.fengwk.kkstudio.core.harness.interaction.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.core.harness.interaction.store.mapper.InteractionMapper;
import fun.fengwk.kkstudio.core.harness.interaction.store.mapper.InteractionOwnerThreadMapper;
import fun.fengwk.kkstudio.core.harness.interaction.store.mapper.InteractionToolOwnerMapper;
import fun.fengwk.kkstudio.core.harness.interaction.store.model.InteractionDO;
import fun.fengwk.kkstudio.core.harness.interaction.store.model.InteractionToolOwnerDO;
import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.kernel.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.interaction.Interaction;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionCreate;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionOwnerAction;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionOwnerDirective;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionResolution;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionResponse;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionStatus;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionTransactions;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionTransition;
import fun.fengwk.kkstudio.harness.runtime.port.HarnessIdGenerator;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * PostgreSQL implementation of generic Interaction transactions.
 *
 * <p>Each terminal mutation locks the fact, validates id/version/OPEN state, then advances exactly
 * once. An already expired response is terminalized as {@code EXPIRED} without applying its handler
 * resolution. The returned target is only a post-commit signal; notifications remain the caller's
 * best-effort responsibility.
 */
@Service
public class PostgresqlInteractionTransactions implements InteractionTransactions {
  /**
   * Canonical durable error payload written when an Interaction rejects a queued Tool invocation.
   */
  static final String INTERACTION_REJECTED_ERROR_JSON =
      "{\"kind\": \"INTERACTION_REJECTED\", \"message\": \"Interaction was rejected.\"}";

  private final InteractionMapper interactionMapper;
  private final InteractionOwnerThreadMapper threadMapper;
  private final InteractionToolOwnerMapper toolMapper;
  private final HarnessIdGenerator idGenerator;

  public PostgresqlInteractionTransactions(
      InteractionMapper interactionMapper,
      InteractionOwnerThreadMapper threadMapper,
      InteractionToolOwnerMapper toolMapper,
      HarnessIdGenerator idGenerator) {
    this.interactionMapper = Objects.requireNonNull(interactionMapper, "interactionMapper");
    this.threadMapper = Objects.requireNonNull(threadMapper, "threadMapper");
    this.toolMapper = Objects.requireNonNull(toolMapper, "toolMapper");
    this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public Interaction create(InteractionCreate create) {
    Objects.requireNonNull(create, "create");
    OwnerLock ownerLock = lockOwner(create.owner(), create.ownerDirective());
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
    OwnerLock ownerLock = lockOwner(current.owner(), resolution.ownerDirective());
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
    return new InteractionTransition(
        resolved(current, response, terminalAt), resolution.nextTarget());
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
    OwnerLock ownerLock = lockOwner(current.owner(), ownerDirective);
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
    return new InteractionTransition(cancelled(current, terminalAt), nextTarget);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public InteractionTransition expire(
      long interactionId, long expectedVersion, Instant resolvedAt) {
    validateIdAndVersion(interactionId, expectedVersion);
    Interaction current = peekInteraction(interactionId);
    Instant terminalAt = Objects.requireNonNull(resolvedAt, "resolvedAt");
    OwnerLock ownerLock = lockOwner(current.owner(), defaultExpirationDirective(current.owner()));
    current = lockOpen(interactionId, expectedVersion);
    if (!isExpired(current, terminalAt)) {
      throw new IllegalStateException("interaction has not expired");
    }
    return expireLocked(
        current,
        ownerLock,
        defaultExpirationDirective(current.owner()),
        defaultExpirationNextTarget(ownerLock),
        terminalAt);
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
    return new InteractionTransition(expired(current, terminalAt), nextTarget);
  }

  private InteractionTransition expireLockedDefault(Interaction current, Instant terminalAt) {
    InteractionOwnerDirective ownerDirective = defaultExpirationDirective(current.owner());
    OwnerLock ownerLock = lockOwner(current.owner(), ownerDirective);
    return expireLocked(
        current, ownerLock, ownerDirective, defaultExpirationNextTarget(ownerLock), terminalAt);
  }

  private static InteractionOwnerDirective defaultExpirationDirective(ExecutionTarget owner) {
    return switch (owner.kind()) {
      case THREAD -> new InteractionOwnerDirective(InteractionOwnerAction.RESUME_THREAD);
      case TOOL_INVOCATION -> new InteractionOwnerDirective(
          InteractionOwnerAction.REJECT_TOOL_TO_FAILED);
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

  private OwnerLock lockOwner(ExecutionTarget owner, InteractionOwnerDirective ownerDirective) {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(ownerDirective, "ownerDirective");
    if (owner.kind() == ExecutionTargetKind.THREAD) {
      requireThreadAction(ownerDirective.action());
      lockThread(owner.id());
      return new OwnerLock(owner, null);
    }
    if (owner.kind() == ExecutionTargetKind.TOOL_INVOCATION) {
      requireToolAction(ownerDirective.action());
      InteractionToolOwnerDO peek = toolMapper.find(owner.id());
      if (peek == null) {
        throw new IllegalStateException("owning tool invocation missing: " + owner.id());
      }
      long threadId = Objects.requireNonNull(peek.getThreadId(), "tool threadId");
      lockThread(threadId);
      InteractionToolOwnerDO tool = toolMapper.findForUpdate(owner.id(), threadId);
      if (tool == null) {
        throw new IllegalStateException("owning tool invocation changed: " + owner.id());
      }
      if (!"QUEUED".equals(tool.getStatus())) {
        throw new IllegalStateException("tool invocation is not interaction-suspendable");
      }
      return new OwnerLock(owner, tool);
    }
    throw new IllegalArgumentException(
        "interaction owner action is unsupported for " + owner.kind());
  }

  private void applySuspension(
      OwnerLock ownerLock, InteractionOwnerDirective ownerDirective, Instant transitionAt) {
    if (ownerDirective.action() == InteractionOwnerAction.SUSPEND_THREAD
        && ownerLock.owner().kind() == ExecutionTargetKind.THREAD) {
      setThreadRunnable(ownerLock.owner().id(), false, transitionAt);
      return;
    }
    if (ownerDirective.action() == InteractionOwnerAction.SUSPEND_TOOL_INVOCATION
        && ownerLock.owner().kind() == ExecutionTargetKind.TOOL_INVOCATION) {
      setThreadRunnable(requireTool(ownerLock).getThreadId(), false, transitionAt);
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
    if (action == InteractionOwnerAction.RESUME_TOOL_TO_QUEUED
        && ownerLock.owner().kind() == ExecutionTargetKind.TOOL_INVOCATION) {
      InteractionToolOwnerDO tool = requireTool(ownerLock);
      requireNextTarget(nextTarget, ExecutionTargetKind.TOOL_INVOCATION, tool.getId());
      if (toolMapper.keepQueued(tool.getId()) != 1) {
        throw new IllegalStateException("tool invocation queue resume lost race: " + tool.getId());
      }
      return;
    }
    if (action == InteractionOwnerAction.REJECT_TOOL_TO_FAILED
        && ownerLock.owner().kind() == ExecutionTargetKind.TOOL_INVOCATION) {
      InteractionToolOwnerDO tool = requireTool(ownerLock);
      requireNextTarget(nextTarget, ExecutionTargetKind.THREAD, tool.getThreadId());
      Instant startedAt = max(transitionAt, tool.getCreatedAt().toInstant());
      if (toolMapper.rejectQueued(
              tool.getId(),
              InteractionRowConverter.toUtcOffsetDateTime(startedAt),
              InteractionRowConverter.toUtcOffsetDateTime(startedAt.plus(Duration.ofMillis(1))),
              InteractionRowConverter.toUtcOffsetDateTime(startedAt),
              INTERACTION_REJECTED_ERROR_JSON)
          != 1) {
        throw new IllegalStateException("tool invocation rejection lost race: " + tool.getId());
      }
      setThreadRunnable(tool.getThreadId(), true, transitionAt);
      return;
    }
    throw new IllegalArgumentException("interaction terminal directive does not match owner");
  }

  private void lockThread(long threadId) {
    if (threadMapper.findIdForUpdate(threadId) == null) {
      throw new IllegalStateException("owning thread missing: " + threadId);
    }
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
    if (action != InteractionOwnerAction.SUSPEND_TOOL_INVOCATION
        && action != InteractionOwnerAction.RESUME_TOOL_TO_QUEUED
        && action != InteractionOwnerAction.REJECT_TOOL_TO_FAILED) {
      throw new IllegalArgumentException(
          "interaction directive is unsupported for TOOL_INVOCATION owner");
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

  private static Instant max(Instant first, Instant second) {
    return first.isAfter(second) ? first : second;
  }

  private record OwnerLock(ExecutionTarget owner, InteractionToolOwnerDO tool) {}

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
