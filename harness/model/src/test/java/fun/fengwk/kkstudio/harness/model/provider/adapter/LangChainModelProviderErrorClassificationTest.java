package fun.fengwk.kkstudio.harness.model.provider.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

  /** 用户可见错误必须保留 cause 链详情，而不是硬编码 provider request failed。 */
  @Test
  void userFacingMessageKeepsNestedProviderDetailAndRedactsSecrets() {
    RuntimeException nested =
        new RuntimeException(
            "HTTP 401 Unauthorized body={\"error\":\"invalid api key\"} Authorization: Bearer sk-secret-value");
    IllegalStateException wrapper = new IllegalStateException("stream failed", nested);

    String message = LangChainModelProvider.userFacingMessage(wrapper);
    assertTrue(message.contains("stream failed"));
    assertTrue(message.contains("HTTP 401 Unauthorized"));
    assertTrue(message.contains("invalid api key"));
    assertFalse(message.contains("sk-secret-value"));
    assertFalse(message.contains("Bearer sk-secret-value"));
    assertTrue(message.contains("Bearer ***") || message.contains("sk-***"));
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
