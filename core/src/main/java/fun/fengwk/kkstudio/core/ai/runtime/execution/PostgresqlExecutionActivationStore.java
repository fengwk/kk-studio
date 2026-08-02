package fun.fengwk.kkstudio.core.ai.runtime.execution;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** PostgreSQL ExecutionActivation 持久化适配器。 */
@Repository
public class PostgresqlExecutionActivationStore implements ExecutionActivationStore {

  private final ExecutionActivationMapper mapper;

  public PostgresqlExecutionActivationStore(ExecutionActivationMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public int schedule(ExecutionTargetKind kind, long id, String environmentName, Instant wakeAt) {
    validate(kind, id, environmentName, ActivationState.SCHEDULED, wakeAt);
    return mapper.schedule(kind.name(), id, environmentName, offset(wakeAt));
  }

  @Override
  public int park(ExecutionTargetKind kind, long id, String environmentName, Instant wakeAt) {
    validate(kind, id, environmentName, ActivationState.PARKED, wakeAt);
    return mapper.park(kind.name(), id, environmentName, offset(wakeAt));
  }

  @Override
  public Optional<ExecutionActivation> lock(ExecutionTargetKind kind, long id) {
    requireActiveTransaction();
    requireIdentity(kind, id);
    ExecutionActivationDO row = mapper.lockForUpdate(kind.name(), id);
    return row == null ? Optional.empty() : Optional.of(toActivation(row));
  }

  @Override
  public Optional<ExecutionActivation> lockDue(ExecutionTargetKind kind, long id, Instant now) {
    requireActiveTransaction();
    requireIdentity(kind, id);
    Objects.requireNonNull(now, "now");
    ExecutionActivationDO row = mapper.lockDueForUpdate(kind.name(), id, offset(now));
    return row == null ? Optional.empty() : Optional.of(toActivation(row));
  }

  @Override
  public int rescheduleLocked(ExecutionTargetKind kind, long id, Instant wakeAt) {
    requireActiveTransaction();
    requireIdentity(kind, id);
    Objects.requireNonNull(wakeAt, "wakeAt");
    return mapper.rescheduleLocked(kind.name(), id, offset(wakeAt));
  }

  @Override
  public int parkLocked(ExecutionTargetKind kind, long id, Instant wakeAt) {
    requireActiveTransaction();
    validate(kind, id, null, ActivationState.PARKED, wakeAt);
    requireLockedRow(kind, id, "parkLocked");
    return mapper.parkLocked(kind.name(), id, offset(wakeAt));
  }

  @Override
  public int activateLocked(ExecutionTargetKind kind, long id, Instant wakeAt) {
    requireActiveTransaction();
    requireIdentity(kind, id);
    Objects.requireNonNull(wakeAt, "wakeAt");
    requireLockedRow(kind, id, "activateLocked");
    return mapper.activateLocked(kind.name(), id, offset(wakeAt));
  }

  @Override
  public int deleteLocked(ExecutionTargetKind kind, long id) {
    requireActiveTransaction();
    requireIdentity(kind, id);
    return mapper.deleteByPk(kind.name(), id);
  }

  @Override
  public int deleteIfExists(ExecutionTargetKind kind, long id) {
    requireIdentity(kind, id);
    return mapper.deleteByPk(kind.name(), id);
  }

  @Override
  public List<ExecutionActivation> findEligibleDue(
      ExecutionActivationEnvironmentEligibility environmentEligibility, Instant now, int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    Objects.requireNonNull(now, "now");
    List<ExecutionActivationDO> rows =
        mapper.findEligibleDue(offset(now), environmentSnapshot(environmentEligibility), limit);
    return rows.stream().map(PostgresqlExecutionActivationStore::toActivation).toList();
  }

  @Override
  public Optional<Instant> findNearestEligibleWakeAt(
      ExecutionActivationEnvironmentEligibility environmentEligibility) {
    OffsetDateTime nearest =
        mapper.findNearestEligibleWakeAt(environmentSnapshot(environmentEligibility));
    return nearest == null ? Optional.empty() : Optional.of(nearest.toInstant());
  }

  @Override
  public List<ExecutionActivation> findAll() {
    return mapper.findAll().stream().map(PostgresqlExecutionActivationStore::toActivation).toList();
  }

  private static String[] environmentSnapshot(
      ExecutionActivationEnvironmentEligibility eligibility) {
    if (eligibility == null) {
      return new String[0];
    }
    Set<String> names = eligibility.readyEnvironmentNames();
    if (names == null || names.isEmpty()) {
      return new String[0];
    }
    return names.toArray(new String[0]);
  }

  private static ExecutionActivation toActivation(ExecutionActivationDO row) {
    Objects.requireNonNull(row, "row");
    return new ExecutionActivation(
        ExecutionTargetKind.valueOf(Objects.requireNonNull(row.getTargetKind(), "targetKind")),
        Objects.requireNonNull(row.getTargetId(), "targetId"),
        row.getEnvironmentName(),
        ActivationState.valueOf(
            Objects.requireNonNull(row.getActivationState(), "activationState")),
        Objects.requireNonNull(row.getWakeAt(), "wakeAt").toInstant());
  }

  private static void validate(
      ExecutionTargetKind kind,
      long id,
      String environmentName,
      ActivationState activationState,
      Instant wakeAt) {
    new ExecutionActivation(kind, id, environmentName, activationState, wakeAt);
  }

  private static void requireIdentity(ExecutionTargetKind kind, long id) {
    Objects.requireNonNull(kind, "kind");
    if (id <= 0) {
      throw new IllegalArgumentException("id must be positive");
    }
  }

  private void requireLockedRow(ExecutionTargetKind kind, long id, String operation) {
    if (mapper.lockForUpdate(kind.name(), id) == null) {
      throw new IllegalStateException(
          operation + " activation row missing or lock lost: " + kind + " " + id);
    }
  }

  private static OffsetDateTime offset(Instant value) {
    return OffsetDateTime.ofInstant(
        Objects.requireNonNull(value, "value").truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
  }

  private static void requireActiveTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "execution activation row lock requires an active transaction");
    }
  }
}
