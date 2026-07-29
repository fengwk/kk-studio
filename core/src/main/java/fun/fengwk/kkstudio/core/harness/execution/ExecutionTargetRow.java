package fun.fengwk.kkstudio.core.harness.execution;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable projection of one row in {@code harness_execution_target}.
 *
 * <p>The runtime {@code ExecutionTarget(kind, id)} pair is the durable identity; {@code routeKey}
 * and {@code availableAt} are queue metadata (environment affinity and dispatch due time).
 */
public final class ExecutionTargetRow {

  private final ExecutionTargetKind targetKind;
  private final long targetId;
  private final String routeKey;
  private final Instant availableAt;

  public ExecutionTargetRow(
      ExecutionTargetKind targetKind, long targetId, String routeKey, Instant availableAt) {
    this.targetKind = Objects.requireNonNull(targetKind, "targetKind");
    this.availableAt = Objects.requireNonNull(availableAt, "availableAt");
    if (targetId <= 0) {
      throw new IllegalArgumentException("targetId must be positive");
    }
    if (routeKey != null && routeKey.isBlank()) {
      throw new IllegalArgumentException("routeKey must not be blank");
    }
    this.targetId = targetId;
    this.routeKey = routeKey;
  }

  public ExecutionTargetKind targetKind() {
    return targetKind;
  }

  public long targetId() {
    return targetId;
  }

  public String routeKey() {
    return routeKey;
  }

  public Instant availableAt() {
    return availableAt;
  }

  @Override
  public String toString() {
    return "ExecutionTargetRow{"
        + "targetKind="
        + targetKind
        + ", targetId="
        + targetId
        + ", routeKey='"
        + routeKey
        + '\''
        + ", availableAt="
        + availableAt
        + '}';
  }
}
