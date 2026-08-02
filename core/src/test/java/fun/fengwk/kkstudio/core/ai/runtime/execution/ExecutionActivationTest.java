package fun.fengwk.kkstudio.core.ai.runtime.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.execution.ExecutionTargetKind;

import java.time.Instant;

class ExecutionActivationTest {

  private static final Instant WAKE_AT = Instant.parse("2026-07-24T00:00:00Z");

  @Test
  void acceptsValidActivationAndMaximumEnvironmentLength() {
    String environmentName = "x".repeat(128);

    ExecutionActivation activation =
        new ExecutionActivation(
            ExecutionTargetKind.TOOL_INVOCATION,
            1L,
            environmentName,
            ActivationState.SCHEDULED,
            WAKE_AT);

    assertEquals(ExecutionTargetKind.TOOL_INVOCATION, activation.targetKind());
    assertEquals(1L, activation.targetId());
    assertEquals(environmentName, activation.environmentName());
    assertEquals(ActivationState.SCHEDULED, activation.activationState());
    assertEquals(WAKE_AT, activation.wakeAt());
  }

  @Test
  void rejectsMissingRequiredValues() {
    assertThrows(
        NullPointerException.class,
        () -> new ExecutionActivation(null, 1L, null, ActivationState.SCHEDULED, WAKE_AT));
    assertThrows(
        NullPointerException.class,
        () -> new ExecutionActivation(ExecutionTargetKind.THREAD, 1L, null, null, WAKE_AT));
    assertThrows(
        NullPointerException.class,
        () ->
            new ExecutionActivation(
                ExecutionTargetKind.THREAD, 1L, null, ActivationState.SCHEDULED, null));
  }

  @Test
  void rejectsInvalidIdentityAndEnvironment() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionActivation(
                ExecutionTargetKind.THREAD, 0L, null, ActivationState.SCHEDULED, WAKE_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionActivation(
                ExecutionTargetKind.THREAD, -1L, null, ActivationState.SCHEDULED, WAKE_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionActivation(
                ExecutionTargetKind.TOOL_INVOCATION, 1L, "", ActivationState.SCHEDULED, WAKE_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionActivation(
                ExecutionTargetKind.TOOL_INVOCATION, 1L, " ", ActivationState.SCHEDULED, WAKE_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionActivation(
                ExecutionTargetKind.TOOL_INVOCATION,
                1L,
                " env-a",
                ActivationState.SCHEDULED,
                WAKE_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionActivation(
                ExecutionTargetKind.TOOL_INVOCATION,
                1L,
                "env-a ",
                ActivationState.SCHEDULED,
                WAKE_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionActivation(
                ExecutionTargetKind.TOOL_INVOCATION,
                1L,
                "x".repeat(129),
                ActivationState.SCHEDULED,
                WAKE_AT));
  }

  @Test
  void restrictsEnvironmentAndParkedStateToToolInvocation() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionActivation(
                ExecutionTargetKind.THREAD, 1L, "env-a", ActivationState.SCHEDULED, WAKE_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionActivation(
                ExecutionTargetKind.MODEL_INVOCATION,
                1L,
                "env-a",
                ActivationState.SCHEDULED,
                WAKE_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionActivation(
                ExecutionTargetKind.THREAD, 1L, null, ActivationState.PARKED, WAKE_AT));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ExecutionActivation(
                ExecutionTargetKind.MODEL_INVOCATION, 1L, null, ActivationState.PARKED, WAKE_AT));
  }
}
