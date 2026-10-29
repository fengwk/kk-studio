package fun.fengwk.kkstudio.harness.runtime.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelInvocationError;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelGroupExecution;
import fun.fengwk.kkstudio.harness.runtime.invocation.model.ModelGroupMemberExecution;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Fill First 失败分类边界：触发码优先、普通耗尽后降级、取消/溢出绝不降级。 */
class ModelGroupFailureDeciderTest {

  private static final ModelGroupExecution GROUP =
      new ModelGroupExecution(
          "group",
          1L,
          List.of(
              new ModelGroupMemberExecution(
                  ProviderType.OPENAI,
                  new UUID(0L, 1L),
                  new ModelDescriptor(
                      "provider", "model", "model", Set.of(ModelInputModality.TEXT), true, true),
                  new ModelVariant("v1"))),
          Set.of(429),
          30);

  @Test
  void fallbackStatusSkipsOrdinaryRetryEvenWhenBudgetRemains() {
    ModelInvocationError error =
        new ModelInvocationError(ProviderErrorKind.TRANSIENT, "rate limited", 429);
    assertEquals(
        ModelGroupFailureDecider.Action.ADVANCE,
        ModelGroupFailureDecider.decide(error, GROUP, true, true));
  }

  @Test
  void nonFallbackRetryableUsesExistingBudgetFirst() {
    ModelInvocationError error =
        new ModelInvocationError(ProviderErrorKind.TRANSIENT, "network", null);
    assertEquals(
        ModelGroupFailureDecider.Action.RETRY_CURRENT,
        ModelGroupFailureDecider.decide(error, GROUP, true, true));
    assertEquals(
        ModelGroupFailureDecider.Action.ADVANCE,
        ModelGroupFailureDecider.decide(error, GROUP, true, false));
  }

  @Test
  void deterministicProviderFailureWithNoRetryAdvances() {
    ModelInvocationError error =
        new ModelInvocationError(ProviderErrorKind.AUTHENTICATION, "denied", null);
    assertEquals(
        ModelGroupFailureDecider.Action.ADVANCE,
        ModelGroupFailureDecider.decide(error, GROUP, false, false));
  }

  @Test
  void cancellationAndOverflowNeverFallback() {
    assertEquals(
        ModelGroupFailureDecider.Action.DEDICATED_TERMINAL,
        ModelGroupFailureDecider.decide(
            new ModelInvocationError(ProviderErrorKind.CANCELLED, "cancelled"),
            GROUP,
            false,
            false));
    assertEquals(
        ModelGroupFailureDecider.Action.DEDICATED_TERMINAL,
        ModelGroupFailureDecider.decide(
            new ModelInvocationError(ProviderErrorKind.OVERFLOW, "too large"),
            GROUP,
            false,
            false));
  }
}
