package fun.fengwk.kkstudio.harness.runtime.model.provider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** 验证 ProviderStreamHandler 两个 onComplete default 方法的单向适配、安全默认与递归切断。 */
class ProviderStreamHandlerTest {

  private static final ProviderStream DUMMY_STREAM =
      new ProviderStream() {
        @Override
        public void cancel() {}

        @Override
        public boolean isCancelled() {
          return false;
        }
      };

  /** 意图：验证未覆盖任何 onComplete 的 handler 在接收 completion 或 response 时绝不发生递归或 StackOverflowError。 */
  @Test
  void unoverriddenHandlerDoesNotThrowStackOverflowOnEitherOnComplete() {
    ProviderStreamHandler noopHandler =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}
        };

    ProviderResponse response = sampleResponse();
    ProviderCompletion completion = new ProviderCompletion(response, null);

    assertDoesNotThrow(() -> noopHandler.onComplete(completion, DUMMY_STREAM));
    assertDoesNotThrow(() -> noopHandler.onComplete(response, DUMMY_STREAM));
  }

  /** 意图：验证旧 handler 仅重写 onComplete(ProviderResponse) 时，新主路径 onComplete(completion) 能正确单向适配。 */
  @Test
  void legacyHandlerOverridingResponseReceivesAdaptedCompletion() {
    AtomicReference<ProviderResponse> captured = new AtomicReference<>();
    ProviderStreamHandler legacyHandler =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderResponse response, ProviderStream stream) {
            captured.set(response);
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}
        };

    ProviderResponse response = sampleResponse();
    ProviderCompletion completion = new ProviderCompletion(response, null);

    legacyHandler.onComplete(completion, DUMMY_STREAM);
    assertEquals(response, captured.get());
  }

  /** 意图：验证新 handler 重写 onComplete(ProviderCompletion) 时能直接接收完整 completion。 */
  @Test
  void modernHandlerOverridingCompletionReceivesFullCompletion() {
    AtomicReference<ProviderCompletion> captured = new AtomicReference<>();
    ProviderStreamHandler modernHandler =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {
            captured.set(completion);
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}
        };

    ProviderResponse response = sampleResponse();
    ProviderCompletion completion = new ProviderCompletion(response, null);

    modernHandler.onComplete(completion, DUMMY_STREAM);
    assertEquals(completion, captured.get());
  }

  /** 意图：验证 onComplete(null, stream) 抛出显式 NPE，防止静默空指针穿透。 */
  @Test
  void requiresNonNullCompletion() {
    ProviderStreamHandler handler =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}
        };

    assertThrows(
        NullPointerException.class,
        () -> handler.onComplete((ProviderCompletion) null, DUMMY_STREAM));
  }

  private static ProviderResponse sampleResponse() {
    return new ProviderResponse(
        "ok",
        "",
        List.of(),
        GenerationStopReason.COMPLETE,
        new ModelUsage(1, 1, 0, 0, 0, 0, 2),
        new ModelCost(
            "USD",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO),
        "req-1",
        null,
        "{}");
  }
}
