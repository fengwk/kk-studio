package fun.fengwk.kkstudio.core.ai.runtime.interaction.service;

import fun.fengwk.kkstudio.core.ai.runtime.interaction.store.model.InteractionDO;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTarget;
import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;
import fun.fengwk.kkstudio.harness.runtime.interaction.Interaction;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionRequest;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionResponse;
import fun.fengwk.kkstudio.harness.runtime.interaction.InteractionStatus;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** Strict conversion between PostgreSQL interaction rows and the Runtime aggregate. */
final class InteractionRowConverter {
  private InteractionRowConverter() {}

  static Interaction toAggregate(InteractionDO row) {
    Objects.requireNonNull(row, "row");
    return new Interaction(
        require(row.getId(), "id"),
        new ExecutionTarget(
            enumValue(ExecutionTargetKind.class, row.getOwnerKind(), "ownerKind"),
            require(row.getOwnerId(), "ownerId")),
        row.getHandlerType(),
        new InteractionRequest(row.getRequestJson()),
        enumValue(InteractionStatus.class, row.getStatus(), "status"),
        row.getResponseJson() == null ? null : new InteractionResponse(row.getResponseJson()),
        toInstant(row.getExpiresAt()),
        require(row.getVersion(), "version"),
        Objects.requireNonNull(toInstant(row.getCreatedAt()), "createdAt"),
        toInstant(row.getResolvedAt()));
  }

  static OffsetDateTime toUtcOffsetDateTime(Instant instant) {
    return OffsetDateTime.ofInstant(
        Objects.requireNonNull(instant, "instant").truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
  }

  private static Instant toInstant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  private static long require(Long value, String name) {
    return Objects.requireNonNull(value, name);
  }

  private static <T extends Enum<T>> T enumValue(Class<T> type, String value, String name) {
    try {
      return Enum.valueOf(type, Objects.requireNonNull(value, name));
    } catch (IllegalArgumentException error) {
      throw new IllegalStateException(
          "invalid persisted interaction " + name + ": " + value, error);
    }
  }
}
