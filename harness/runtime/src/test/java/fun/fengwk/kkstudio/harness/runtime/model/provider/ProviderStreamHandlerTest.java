package fun.fengwk.kkstudio.harness.runtime.model.provider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelCost;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 验证 ProviderStreamHandler 的唯一成功终态入口 onComplete 完整交付 ProviderCompletion，以及 onProtocolEvent 的默认忽略。
 */
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

  /** 意图：成功终态只有一个入口，必须原样交付 ProviderCompletion（含 native replay），不丢信息也不做二次适配。 */
  @Test
  void completionHandlerReceivesFullCompletionIncludingReplayState() {
    ProviderReplayState replayState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT,
            new ProviderReplayAffinity(ProviderType.OPENAI, "openai", UUID.randomUUID(), "gpt-5"),
            "0".repeat(64),
            new ObjectMapper().createObjectNode());
    ProviderCompletion completion = new ProviderCompletion(sampleResponse(), replayState);
    AtomicReference<ProviderCompletion> captured = new AtomicReference<>();
    ProviderStreamHandler handler =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion value, ProviderStream stream) {
            captured.set(value);
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}
        };

    handler.onComplete(completion, DUMMY_STREAM);

    assertSame(completion, captured.get());
    assertSame(replayState, captured.get().replayState());
    assertSame(completion.response(), captured.get().response());
  }

  /** 意图：只需规范化增量的实现者不覆盖 onProtocolEvent 也能编译并安全忽略原生事件（默认实现无副作用）。 */
  @Test
  void defaultProtocolEventIsSafelyIgnored() {
    ProviderStreamHandler handler =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}
        };

    assertDoesNotThrow(
        () ->
            handler.onProtocolEvent(
                new ProviderProtocolEvent("message_delta", "{\"delta\":{}}"), DUMMY_STREAM));
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
