package fun.fengwk.kkstudio.harness.runtime.model.provider;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 验证 ProviderStreamHandler 的唯一成功终态入口 onComplete 完整交付 ProviderCompletion，以及 onProtocolEvent 的默认忽略。
 */
class ProviderStreamHandlerTest {

  /** 意图：成功终态只有一个入口，必须原样交付 ProviderCompletion（含 native replay），不丢信息也不做二次适配。 */
  @Test
  void completionHandlerReceivesFullCompletionIncludingReplayState() {
    ProviderReplayState replayState =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_CHAT,
            new ProviderReplayAffinity(ProviderType.OPENAI, "openai", UUID.randomUUID(), "gpt-5"),
            new ObjectMapper().createObjectNode());
    ProviderCompletion completion = new ProviderCompletion(sampleResponse(), replayState);
    AtomicReference<ProviderCompletion> captured = new AtomicReference<>();
    ProviderStreamHandler handler =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event) {}

          @Override
          public void onComplete(ProviderCompletion value) {
            captured.set(value);
          }

          @Override
          public void onError(ProviderException error) {}
        };

    handler.onComplete(completion);

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
          public void onEvent(ProviderStreamEvent event) {}

          @Override
          public void onComplete(ProviderCompletion completion) {}

          @Override
          public void onError(ProviderException error) {}
        };

    assertDoesNotThrow(
        () ->
            handler.onProtocolEvent(new ProviderProtocolEvent("message_delta", "{\"delta\":{}}")));
  }

  private static ProviderResponse sampleResponse() {
    return new ProviderResponse(
        "ok",
        "",
        List.of(),
        GenerationStopReason.COMPLETE,
        new ModelUsage(1, 1, 0, 0, 0, 0, 2),
        "req-1",
        null,
        "{}");
  }
}
