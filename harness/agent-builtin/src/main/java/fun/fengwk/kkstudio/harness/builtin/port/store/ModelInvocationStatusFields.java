package fun.fengwk.kkstudio.harness.builtin.port.store;

import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.provider.ProviderResponse;

import java.util.List;
import java.util.UUID;

/**
 * 每个 {@link ModelInvocationStatus} 允许携带的 durable 字段组合：terminal 事实、结果 Entry 链接与已物化的 attempt 状态。
 *
 * <p>构造 {@link ModelInvocation} 时校验；规则只读入参，不修改状态。
 */
final class ModelInvocationStatusFields {

  private ModelInvocationStatusFields() {}

  static void validate(
      ModelInvocationStatus status,
      int attempt,
      List<ModelAttemptFailure> failedAttempts,
      ProviderResponse result,
      ModelInvocationError error,
      UUID resultEntryId,
      StreamCheckpoint streamCheckpoint) {
    if (!status.isTerminal() && resultEntryId != null) {
      throw new IllegalArgumentException("resultEntryId is only allowed on terminal states");
    }
    switch (status) {
      case READY -> validateReady(attempt, failedAttempts, result, error, streamCheckpoint);
      case RUNNING -> validateRunning(attempt, failedAttempts, result, error);
      case SUCCEEDED -> validateSucceeded(
          attempt, failedAttempts, result, error, resultEntryId, streamCheckpoint);
      case FAILED -> validateFailed(
          attempt, failedAttempts, result, error, resultEntryId, streamCheckpoint);
      case CANCELLED -> validateCancelled(
          attempt, failedAttempts, result, error, resultEntryId, streamCheckpoint);
      case UNKNOWN -> validateUnknown(
          attempt, failedAttempts, result, error, resultEntryId, streamCheckpoint);
    }
  }

  /** READY 尚未消耗 attempt：失败审计与 attempt 数相等，且不携带任何执行期事实。 */
  private static void validateReady(
      int attempt,
      List<ModelAttemptFailure> failedAttempts,
      ProviderResponse result,
      ModelInvocationError error,
      StreamCheckpoint streamCheckpoint) {
    requireFailureCount(failedAttempts, attempt, ModelInvocationStatus.READY);
    if (streamCheckpoint != null) {
      throw new IllegalArgumentException("READY must not carry a stream checkpoint");
    }
    if (result != null || error != null) {
      throw new IllegalArgumentException("READY must not carry terminal result facts");
    }
  }

  /** RUNNING 必有正 attempt；本 attempt 的失败尚未审计。 */
  private static void validateRunning(
      int attempt,
      List<ModelAttemptFailure> failedAttempts,
      ProviderResponse result,
      ModelInvocationError error) {
    if (attempt <= 0) {
      throw new IllegalArgumentException("RUNNING requires a positive attempt");
    }
    requireFailureCount(failedAttempts, precedingAttempts(attempt), ModelInvocationStatus.RUNNING);
    if (result != null || error != null) {
      throw new IllegalArgumentException("RUNNING must not carry terminal result facts");
    }
  }

  private static void validateSucceeded(
      int attempt,
      List<ModelAttemptFailure> failedAttempts,
      ProviderResponse result,
      ModelInvocationError error,
      UUID resultEntryId,
      StreamCheckpoint streamCheckpoint) {
    if (attempt <= 0) {
      throw new IllegalArgumentException("SUCCEEDED requires a positive attempt");
    }
    requireTerminalMaterialization(
        ModelInvocationStatus.SUCCEEDED,
        failedAttempts,
        streamCheckpoint,
        resultEntryId,
        precedingAttempts(attempt),
        false);
    if (result == null) {
      throw new IllegalArgumentException("SUCCEEDED requires a result");
    }
    if (error != null) {
      throw new IllegalArgumentException("SUCCEEDED must not carry an error");
    }
  }

  /** FAILED 由 STARTED 前拒绝或失败 attempt 收敛，失败审计可等于 attempt 或落后一条。 */
  private static void validateFailed(
      int attempt,
      List<ModelAttemptFailure> failedAttempts,
      ProviderResponse result,
      ModelInvocationError error,
      UUID resultEntryId,
      StreamCheckpoint streamCheckpoint) {
    requireTerminalMaterialization(
        ModelInvocationStatus.FAILED,
        failedAttempts,
        streamCheckpoint,
        resultEntryId,
        attempt,
        true);
    if (error == null) {
      throw new IllegalArgumentException("FAILED requires an error");
    }
    if (result != null) {
      throw new IllegalArgumentException("FAILED must not carry a result");
    }
  }

  private static void validateCancelled(
      int attempt,
      List<ModelAttemptFailure> failedAttempts,
      ProviderResponse result,
      ModelInvocationError error,
      UUID resultEntryId,
      StreamCheckpoint streamCheckpoint) {
    requireTerminalMaterialization(
        ModelInvocationStatus.CANCELLED,
        failedAttempts,
        streamCheckpoint,
        resultEntryId,
        attempt,
        true);
    if (error == null) {
      throw new IllegalArgumentException(ModelInvocationStatus.CANCELLED + " requires an error");
    }
    if (result != null) {
      throw new IllegalArgumentException(
          ModelInvocationStatus.CANCELLED + " must not carry a result");
    }
  }

  private static void validateUnknown(
      int attempt,
      List<ModelAttemptFailure> failedAttempts,
      ProviderResponse result,
      ModelInvocationError error,
      UUID resultEntryId,
      StreamCheckpoint streamCheckpoint) {
    if (attempt <= 0) {
      throw new IllegalArgumentException("UNKNOWN requires a positive attempt");
    }
    requireTerminalMaterialization(
        ModelInvocationStatus.UNKNOWN,
        failedAttempts,
        streamCheckpoint,
        resultEntryId,
        precedingAttempts(attempt),
        false);
    if (error == null) {
      throw new IllegalArgumentException("UNKNOWN requires an error");
    }
    if (result != null) {
      throw new IllegalArgumentException("UNKNOWN must not carry a result");
    }
  }

  private static int precedingAttempts(int attempt) {
    return Math.subtractExact(attempt, 1);
  }

  private static void requireFailureCount(
      List<ModelAttemptFailure> failedAttempts, int expected, ModelInvocationStatus status) {
    if (failedAttempts.size() != expected) {
      throw new IllegalArgumentException(status + " requires failedAttempts.size == " + expected);
    }
  }

  /** terminal 行要么已链接结果 Entry（此时不得保留任何已物化的 attempt 状态），要么保留 pending failedAttempts 前缀。 */
  private static void requireTerminalMaterialization(
      ModelInvocationStatus status,
      List<ModelAttemptFailure> failedAttempts,
      StreamCheckpoint streamCheckpoint,
      UUID resultEntryId,
      int expectedFailures,
      boolean allowOneFewerFailure) {
    if (resultEntryId != null) {
      if (!failedAttempts.isEmpty() || streamCheckpoint != null) {
        throw new IllegalArgumentException(
            status + " with resultEntryId must not retain materialized attempt state");
      }
      return;
    }
    boolean valid = failedAttempts.size() == expectedFailures;
    if (allowOneFewerFailure && expectedFailures > 0) {
      valid |= failedAttempts.size() == expectedFailures - 1;
    }
    if (!valid) {
      throw new IllegalArgumentException(
          status + " requires the pending failedAttempts prefix for attempt " + expectedFailures);
    }
  }
}
