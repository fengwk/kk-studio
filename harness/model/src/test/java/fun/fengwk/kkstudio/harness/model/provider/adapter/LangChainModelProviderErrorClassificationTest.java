package fun.fengwk.kkstudio.harness.model.provider.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStream;

/** Provider error 分类必须穿透包装异常，避免 401 被误判为瞬态故障。 */
class LangChainModelProviderErrorClassificationTest {

  @Test
  void classifiesNestedAuthenticationAndBillingFailuresWithoutRetry() {
    ProviderStream active = activeStream();

    assertEquals(
        ProviderErrorKind.AUTHENTICATION,
        LangChainModelProvider.classify(
            new IllegalStateException(
                "provider request failed", new RuntimeException("HTTP 401 Unauthorized")),
            active));
    assertEquals(
        ProviderErrorKind.BILLING,
        LangChainModelProvider.classify(
            new IllegalStateException(
                "provider request failed", new RuntimeException("quota exceeded")),
            active));
  }

  @Test
  void classifiesCancelledStreamsBeforeInspectingErrors() {
    ProviderStream cancelled =
        new ProviderStream() {
          @Override
          public void cancel() {}

          @Override
          public boolean isCancelled() {
            return true;
          }
        };

    assertEquals(
        ProviderErrorKind.CANCELLED,
        LangChainModelProvider.classify(new RuntimeException("HTTP 401 Unauthorized"), cancelled));
  }

  private static ProviderStream activeStream() {
    return new ProviderStream() {
      @Override
      public void cancel() {}

      @Override
      public boolean isCancelled() {
        return false;
      }
    };
  }
}
