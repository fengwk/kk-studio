package fun.fengwk.kkstudio.core.ai.runtime.execution;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;

import java.time.Instant;
import java.util.Objects;

/** 一条持久化 ExecutionActivation 的不可变领域投影。 */
public record ExecutionActivation(
    ExecutionTargetKind targetKind,
    long targetId,
    String environmentName,
    ActivationState activationState,
    Instant wakeAt) {

  public ExecutionActivation {
    Objects.requireNonNull(targetKind, "targetKind");
    Objects.requireNonNull(activationState, "activationState");
    Objects.requireNonNull(wakeAt, "wakeAt");
    if (targetId <= 0) {
      throw new IllegalArgumentException("targetId must be positive");
    }
    if (environmentName != null && environmentName.isBlank()) {
      throw new IllegalArgumentException("environmentName must not be blank");
    }
    if (environmentName != null && !environmentName.equals(environmentName.strip())) {
      throw new IllegalArgumentException(
          "environmentName must not have leading or trailing whitespace");
    }
    if (environmentName != null && environmentName.length() > 128) {
      throw new IllegalArgumentException("environmentName must be <= 128 characters");
    }
    if (targetKind != ExecutionTargetKind.TOOL_INVOCATION && environmentName != null) {
      throw new IllegalArgumentException(
          "environmentName is only valid for TOOL_INVOCATION activations");
    }
    if (activationState == ActivationState.PARKED
        && targetKind != ExecutionTargetKind.TOOL_INVOCATION) {
      throw new IllegalArgumentException("only TOOL_INVOCATION activations may be PARKED");
    }
  }
}
