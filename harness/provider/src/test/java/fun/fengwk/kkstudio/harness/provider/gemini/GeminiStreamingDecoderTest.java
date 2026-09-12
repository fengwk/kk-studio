package fun.fengwk.kkstudio.harness.provider.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Gemini SSE 流式分包与边缘格式解析测试。 */
class GeminiStreamingDecoderTest {

  private static ProviderDescriptor descriptor() {
    return new ProviderDescriptor(
        "google-test",
        ProviderType.GOOGLE,
        "https://generativelanguage.googleapis.com",
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)),
        new UUID(1L, 2L));
  }

  private static ProviderRequest request() {
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "tier-1",
            "default",
            BigDecimal.ONE,
            "v1",
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO);
    ModelVariant variant = new ModelVariant("default");
    return new ProviderRequest(
        new ModelDescriptor(
            "google-test",
            "gemini-2.5-flash",
            "gemini-2.5-flash",
            Set.of(ModelInputModality.TEXT),
            true,
            false,
            pricing),
        variant,
        1024,
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  /** 验证多字节 UTF-8 中文字符在分包切分时的正确重组。 */
  @Test
  void handlesUtf8CharactersAcrossStreamChunks() throws Exception {
    List<ProviderStreamEvent> events = new ArrayList<>();
    ProviderStreamHandler handler =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
            events.add(event);
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}
        };

    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(
            request(),
            descriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            new GeminiStreamBridge(handler));

    String c1 =
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{ "text": "你好，" }] }
          }]
        }
        """;
    String c2 =
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{ "text": "世界！" }] },
            "finishReason": "STOP"
          }]
        }
        """;

    accumulator.handleEvent("message", c1);
    accumulator.handleEvent("message", c2);
    ProviderCompletion completion = accumulator.finish();
    ProviderResponse response = completion.response();

    assertEquals(GenerationStopReason.COMPLETE, response.stopReason());
    assertEquals("你好，世界！", response.text());
  }

  /** 验证带有空格的 json 换行及转义文本正常解析。 */
  @Test
  void handlesEscapedAndMultilineContent() throws Exception {
    List<ProviderStreamEvent> events = new ArrayList<>();
    ProviderStreamHandler handler =
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
            events.add(event);
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {}
        };

    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(
            request(),
            descriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            new GeminiStreamBridge(handler));

    String chunk =
        """
        {
          "candidates": [{
            "content": { "role": "model", "parts": [{ "text": "line1\\nline2\\tindent" }] },
            "finishReason": "STOP"
          }]
        }
        """;

    accumulator.handleEvent("message", chunk);
    ProviderCompletion completion = accumulator.finish();
    ProviderResponse response = completion.response();

    assertEquals("line1\nline2\tindent", response.text());
  }
}
