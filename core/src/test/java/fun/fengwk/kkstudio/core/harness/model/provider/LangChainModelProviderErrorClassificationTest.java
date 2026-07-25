package fun.fengwk.kkstudio.core.harness.model.provider;

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

  /** nginx/HTML 404 与 405 是配置/路由错误，绝不能落入 TRANSIENT 自动重试。 */
  @Test
  void classifiesNotFoundAndMethodNotAllowedAsInvalidRequest() {
    ProviderStream active = activeStream();
    assertEquals(
        ProviderErrorKind.INVALID_REQUEST,
        LangChainModelProvider.classify(
            new RuntimeException(
                "<html><head><title>404 Not Found</title></head><body><center><h1>404 Not Found</h1></center><hr><center>nginx</center></body></html>"),
            active));
    assertEquals(
        ProviderErrorKind.INVALID_REQUEST,
        LangChainModelProvider.classify(
            new RuntimeException("HTTP 405 Method Not Allowed"), active));
    assertEquals(
        ProviderErrorKind.TRANSIENT,
        LangChainModelProvider.classify(
            new RuntimeException("HTTP 429 Too Many Requests"), active));
    assertEquals(
        ProviderErrorKind.TRANSIENT,
        LangChainModelProvider.classify(new RuntimeException("HTTP 502 Bad Gateway"), active));
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
