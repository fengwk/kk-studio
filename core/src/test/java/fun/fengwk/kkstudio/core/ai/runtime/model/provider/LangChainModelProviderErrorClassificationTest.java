package fun.fengwk.kkstudio.core.ai.runtime.model.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.langchain4j.exception.HttpException;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

/** Provider error 分类必须穿透包装异常，避免 401 被误判为瞬态故障。 */
class LangChainModelProviderErrorClassificationTest {

  @Test
  void classifiesNestedAuthenticationAndBillingFailuresWithoutRetry() {
    ProviderStream active = activeStream();

    assertEquals(
        ProviderErrorKind.AUTHENTICATION,
        LangChainModelProvider.classify(
            ProviderType.OPENAI,
            new IllegalStateException(
                "provider request failed", new RuntimeException("HTTP 401 Unauthorized")),
            active));
    assertEquals(
        ProviderErrorKind.BILLING,
        LangChainModelProvider.classify(
            ProviderType.OPENAI,
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
            ProviderType.OPENAI,
            new RuntimeException(
                "<html><head><title>404 Not Found</title></head><body><center><h1>404 Not Found</h1></center><hr><center>nginx</center></body></html>"),
            active));
    assertEquals(
        ProviderErrorKind.INVALID_REQUEST,
        LangChainModelProvider.classify(
            ProviderType.OPENAI, new RuntimeException("HTTP 405 Method Not Allowed"), active));
    assertEquals(
        ProviderErrorKind.TRANSIENT,
        LangChainModelProvider.classify(
            ProviderType.OPENAI, new RuntimeException("HTTP 429 Too Many Requests"), active));
    assertEquals(
        ProviderErrorKind.TRANSIENT,
        LangChainModelProvider.classify(
            ProviderType.OPENAI, new RuntimeException("HTTP 502 Bad Gateway"), active));
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
        LangChainModelProvider.classify(
            ProviderType.OPENAI, new RuntimeException("HTTP 401 Unauthorized"), cancelled));
  }

  /** CANCELLED 优先于一切 context 判定，即使错误本身是 413。 */
  @Test
  void cancelledStreamWinsOverContextPressure() {
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
        LangChainModelProvider.classify(
            ProviderType.OPENAI, new HttpException(413, "prompt is too long"), cancelled));
  }

  /** 端到端：HttpException 413、显式 context code/type 与 Provider 特有 pattern 都必须分类为 OVERFLOW。 */
  @Test
  void classifiesExplicitContextPressureAsOverflow() {
    ProviderStream active = activeStream();
    assertEquals(
        ProviderErrorKind.OVERFLOW,
        LangChainModelProvider.classify(
            ProviderType.OPENAI, new HttpException(413, "Request Entity Too Large"), active));
    assertEquals(
        ProviderErrorKind.OVERFLOW,
        LangChainModelProvider.classify(
            ProviderType.OPENAI,
            new IllegalStateException(
                "provider request failed",
                new HttpException(
                    400, "HTTP 400 body={\"error\":{\"code\":\"context_length_exceeded\"}}")),
            active));
    assertEquals(
        ProviderErrorKind.OVERFLOW,
        LangChainModelProvider.classify(
            ProviderType.ANTHROPIC, new RuntimeException("Your prompt is too long."), active));
    assertEquals(
        ProviderErrorKind.OVERFLOW,
        LangChainModelProvider.classify(
            ProviderType.OPENAI,
            new RuntimeException("This model's maximum context length"),
            active));
  }

  /** 端到端：HTTP 429 即使消息含 context 字样、普通 400 invalid 都不得误判为 OVERFLOW。 */
  @Test
  void rateLimitingAndPlain400NeverOverflow() {
    ProviderStream active = activeStream();
    assertEquals(
        ProviderErrorKind.TRANSIENT,
        LangChainModelProvider.classify(
            ProviderType.OPENAI,
            new HttpException(429, "Too Many Requests: maximum context length"),
            active));
    assertEquals(
        ProviderErrorKind.TRANSIENT,
        LangChainModelProvider.classify(
            ProviderType.OPENAI,
            new RuntimeException(
                "HTTP 429 Too Many Requests: your input exceeds the context window"),
            active));
    assertEquals(
        ProviderErrorKind.INVALID_REQUEST,
        LangChainModelProvider.classify(
            ProviderType.OPENAI, new HttpException(400, "HTTP 400 Bad Request"), active));
  }

  /** 端到端：typed status 401/402/403 或明确 auth/billing message 即使同时提到 context 字样也优先分类，绝不 OVERFLOW。 */
  @Test
  void authAndBillingWinOverContextText() {
    ProviderStream active = activeStream();
    assertEquals(
        ProviderErrorKind.AUTHENTICATION,
        LangChainModelProvider.classify(
            ProviderType.OPENAI,
            new RuntimeException(
                "HTTP 401 Unauthorized: this model's maximum context length is 1000 tokens"),
            active));
    assertEquals(
        ProviderErrorKind.AUTHENTICATION,
        LangChainModelProvider.classify(
            ProviderType.OPENAI, new HttpException(403, "Forbidden"), active));
    assertEquals(
        ProviderErrorKind.AUTHENTICATION,
        LangChainModelProvider.classify(
            ProviderType.OPENAI,
            new RuntimeException("auth failed: maximum prompt length is 60000"),
            active));
    assertEquals(
        ProviderErrorKind.BILLING,
        LangChainModelProvider.classify(
            ProviderType.OPENAI,
            new RuntimeException("HTTP 402 Payment Required: maximum context length exceeded"),
            active));
    assertEquals(
        ProviderErrorKind.BILLING,
        LangChainModelProvider.classify(
            ProviderType.OPENAI,
            new RuntimeException("quota exhausted while trimming to the context window"),
            active));
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
