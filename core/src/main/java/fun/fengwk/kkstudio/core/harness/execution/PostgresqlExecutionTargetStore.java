package fun.fengwk.kkstudio.core.harness.execution;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
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

/**
 * Final-schema PostgreSQL adapter for {@link ExecutionTargetStore}.
 *
 * <p>{@link #lockDue(ExecutionTargetKind, long, Instant)} and the matching {@link
 * #rescheduleLocked} / {@link #deleteLocked} pair run inside the caller's transaction. The
 * dispatcher's reads ({@link #findEligibleDue}, {@link #findNearestEligibleAvailableAt}) are
 * lock-free and ignore disabled targets; domain transactions own the row lock from {@code lockDue}
 * onward. {@link #schedule}, {@link #park}, and {@link #activateOldestEnvironment} are short
 * operations safe for use by any thread (and join an existing transaction when one is active).
 */
@Repository
public class PostgresqlExecutionTargetStore implements ExecutionTargetStore {

  private final ExecutionTargetMapper mapper;

  public PostgresqlExecutionTargetStore(ExecutionTargetMapper mapper) {
    this.mapper = Objects.requireNonNull(mapper, "mapper");
  }

  @Override
  public int schedule(ExecutionTargetKind kind, long id, String routeKey, Instant availableAt) {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(availableAt, "availableAt");
    if (id <= 0) {
      throw new IllegalArgumentException("id must be positive");
    }
    if (routeKey != null && routeKey.isBlank()) {
      throw new IllegalArgumentException("routeKey must not be blank");
    }
    return mapper.schedule(kind.name(), id, routeKey, offset(availableAt));
  }

  @Override
  public int park(ExecutionTargetKind kind, long id, String routeKey, Instant availableAt) {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(availableAt, "availableAt");
    if (kind != ExecutionTargetKind.TOOL_INVOCATION) {
      throw new IllegalArgumentException("park is only valid for TOOL_INVOCATION targets");
    }
    if (id <= 0) {
      throw new IllegalArgumentException("id must be positive");
    }
    if (routeKey == null || routeKey.isBlank()) {
      throw new IllegalArgumentException("park requires a non-blank routeKey");
    }
    return mapper.park(kind.name(), id, routeKey, offset(availableAt));
  }

  @Override
  public Optional<ExecutionTargetRow> lock(ExecutionTargetKind kind, long id) {
    requireActiveTransaction();
    Objects.requireNonNull(kind, "kind");
    if (id <= 0) {
      throw new IllegalArgumentException("id must be positive");
    }
    ExecutionTargetDO row = mapper.lockForUpdate(kind.name(), id);
    return row == null ? Optional.empty() : Optional.of(toRow(row));
  }

  @Override
  public Optional<ExecutionTargetRow> lockDue(ExecutionTargetKind kind, long id, Instant now) {
    requireActiveTransaction();
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(now, "now");
    if (id <= 0) {
      throw new IllegalArgumentException("id must be positive");
    }
    ExecutionTargetDO row = mapper.lockDueForUpdate(kind.name(), id, offset(now));
    return row == null ? Optional.empty() : Optional.of(toRow(row));
  }

  @Override
  public int rescheduleLocked(
      ExecutionTargetKind kind, long id, String routeKey, Instant availableAt) {
    requireActiveTransaction();
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(availableAt, "availableAt");
    if (id <= 0) {
      throw new IllegalArgumentException("id must be positive");
    }
    if (routeKey != null && routeKey.isBlank()) {
      throw new IllegalArgumentException("routeKey must not be blank");
    }
    return mapper.rescheduleLocked(kind.name(), id, routeKey, offset(availableAt));
  }

  @Override
  public int deleteLocked(ExecutionTargetKind kind, long id) {
    requireActiveTransaction();
    Objects.requireNonNull(kind, "kind");
    if (id <= 0) {
      throw new IllegalArgumentException("id must be positive");
    }
    return mapper.deleteByPk(kind.name(), id);
  }

  @Override
  public int deleteIfExists(ExecutionTargetKind kind, long id) {
    Objects.requireNonNull(kind, "kind");
    if (id <= 0) {
      throw new IllegalArgumentException("id must be positive");
    }
    return mapper.deleteByPk(kind.name(), id);
  }

  @Override
  @Transactional(isolation = Isolation.READ_COMMITTED)
  public boolean activateOldestEnvironment(String routeKey, Instant availableAt) {
    Objects.requireNonNull(routeKey, "routeKey");
    Objects.requireNonNull(availableAt, "availableAt");
    if (routeKey.isBlank()) {
      throw new IllegalArgumentException("routeKey must not be blank");
    }
    return mapper.activateOldestEnvironment(routeKey, offset(availableAt)) > 0;
  }

  @Override
  public List<ExecutionTargetRow> findEligibleDue(
      ExecutionTargetRouteEligibility routeEligibility, Instant now, int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    Objects.requireNonNull(now, "now");
    List<ExecutionTargetDO> rows =
        mapper.findEligibleDue(offset(now), routeSnapshot(routeEligibility), limit);
    return rows.stream().map(PostgresqlExecutionTargetStore::toRow).toList();
  }

  @Override
  public Optional<Instant> findNearestEligibleAvailableAt(
      ExecutionTargetRouteEligibility routeEligibility) {
    OffsetDateTime nearest = mapper.findNearestEligibleAvailableAt(routeSnapshot(routeEligibility));
    return nearest == null ? Optional.empty() : Optional.of(nearest.toInstant());
  }

  @Override
  public List<ExecutionTargetRow> findAll() {
    return mapper.findAll().stream().map(PostgresqlExecutionTargetStore::toRow).toList();
  }

  private static String[] routeSnapshot(ExecutionTargetRouteEligibility eligibility) {
    if (eligibility == null) {
      return new String[0];
    }
    Set<String> keys = eligibility.readyRouteKeys();
    if (keys == null || keys.isEmpty()) {
      return new String[0];
    }
    return keys.toArray(new String[0]);
  }

  private static ExecutionTargetRow toRow(ExecutionTargetDO d) {
    Objects.requireNonNull(d, "row");
    ExecutionTargetKind kind = ExecutionTargetKind.valueOf(d.getTargetKind());
    return new ExecutionTargetRow(
        kind,
        d.getTargetId(),
        d.getRouteKey(),
        d.getAvailableAt().toInstant(),
        d.isDispatchEnabled());
  }

  private static OffsetDateTime offset(Instant value) {
    return OffsetDateTime.ofInstant(
        Objects.requireNonNull(value, "value").truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
  }

  private static void requireActiveTransaction() {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("execution target row lock requires an active transaction");
    }
  }
}
