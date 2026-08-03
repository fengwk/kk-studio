package fun.fengwk.kkstudio.harness.runtime.invocation.model;

import fun.fengwk.kkstudio.harness.runtime.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;

import java.time.Instant;
import java.util.Objects;

/**
 * Durable current state of one Model invocation.
 *
 * <p>{@code attempt} counts actual Provider call starts; BUSY/local rejections before the call do
 * not increase it. {@code resultEntryId} links the execution result to the Session history and is
 * only present (possibly) on terminal states. {@code streamCheckpoint} is the safe partial of the
 * current attempt and is never a second result.
 */
public record ModelInvocation(
    long id,
    long threadId,
    long turnStartEntryId,
    long basisHeadEntryId,
    ModelInvocationRequest request,
    ModelInvocationStatus status,
    int attempt,
    StreamCheckpoint streamCheckpoint,
    ProviderResponse result,
    ModelInvocationError error,
    Long resultEntryId,
    Instant createdAt,
    Instant updatedAt) {

  public ModelInvocation {
    if (id <= 0) {
      throw new IllegalArgumentException("invocation id must be positive");
    }
    if (threadId <= 0) {
      throw new IllegalArgumentException("threadId must be positive");
    }
    if (turnStartEntryId <= 0) {
      throw new IllegalArgumentException("turnStartEntryId must be positive");
    }
    if (basisHeadEntryId <= 0) {
      throw new IllegalArgumentException("basisHeadEntryId must be positive");
    }
    request = Objects.requireNonNull(request, "request");
    status = Objects.requireNonNull(status, "status");
    if (attempt < 0) {
      throw new IllegalArgumentException("attempt must not be negative");
    }
    if (streamCheckpoint != null && streamCheckpoint.attempt() != attempt) {
      throw new IllegalArgumentException("streamCheckpoint attempt must match invocation attempt");
    }
    validateStatusFields(status, attempt, result, error, resultEntryId, streamCheckpoint);
    createdAt = Objects.requireNonNull(createdAt, "createdAt");
    updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not precede createdAt");
    }
  }

  private static void validateStatusFields(
      ModelInvocationStatus status,
      int attempt,
      ProviderResponse result,
      ModelInvocationError error,
      Long resultEntryId,
      StreamCheckpoint streamCheckpoint) {
    boolean terminal = status.isTerminal();
    if (!terminal && resultEntryId != null) {
      throw new IllegalArgumentException("resultEntryId is only allowed on terminal states");
    }
    if (terminal && resultEntryId != null && resultEntryId <= 0) {
      throw new IllegalArgumentException("terminal resultEntryId must be positive");
    }
    if (status == ModelInvocationStatus.READY) {
      if (streamCheckpoint != null) {
        throw new IllegalArgumentException("READY must not carry a stream checkpoint");
      }
      if (result != null || error != null) {
        throw new IllegalArgumentException("READY must not carry terminal result facts");
      }
    } else if (status == ModelInvocationStatus.RUNNING) {
      if (attempt <= 0) {
        throw new IllegalArgumentException("RUNNING requires a positive attempt");
      }
      if (result != null || error != null) {
        throw new IllegalArgumentException("RUNNING must not carry terminal result facts");
      }
    } else if (status == ModelInvocationStatus.SUCCEEDED) {
      if (attempt <= 0) {
        throw new IllegalArgumentException("SUCCEEDED requires a positive attempt");
      }
      if (result == null) {
        throw new IllegalArgumentException("SUCCEEDED requires a result");
      }
      if (error != null) {
        throw new IllegalArgumentException("SUCCEEDED must not carry an error");
      }
    } else if (status == ModelInvocationStatus.FAILED || status == ModelInvocationStatus.UNKNOWN) {
      if (attempt <= 0) {
        throw new IllegalArgumentException(status + " requires a positive attempt");
      }
      if (error == null) {
        throw new IllegalArgumentException(status + " requires an error");
      }
      if (result != null) {
        throw new IllegalArgumentException(status + " must not carry a result");
      }
    } else {
      if (error == null) {
        throw new IllegalArgumentException(status + " requires an error");
      }
      if (result != null) {
        throw new IllegalArgumentException(status + " must not carry a result");
      }
    }
  }
}
