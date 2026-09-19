package fun.fengwk.kkstudio.harness.provider.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 基于真实 Wire SSE Fixture 的完整解析与累积回归测试。 */
class GeminiWireTest {

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
            true,
            pricing),
        variant,
        1024,
        "Test system instruction.",
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  private String loadFixture(String name) throws Exception {
    try (InputStream in = getClass().getResourceAsStream("fixtures/" + name)) {
      assertNotNull(in, "fixture not found: " + name);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private void feedSse(GeminiStreamAccumulator accumulator, String sseContent) {
    String[] lines = sseContent.split("\n");
    for (String line : lines) {
      String trimmed = line.trim();
      if (trimmed.startsWith("data:")) {
        String json = trimmed.substring(5).trim();
        accumulator.handleEvent("message", json);
      }
    }
  }

  /** 验证纯增量 SSE fixture 解析与拼装结果。 */
  @Test
  void parsesIncrementalSseFixture() throws Exception {
    String sse = loadFixture("stream-incremental.sse");
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
    feedSse(accumulator, sse);
    ProviderCompletion completion = accumulator.finish();
    ProviderResponse response = completion.response();

    assertEquals(GenerationStopReason.COMPLETE, response.stopReason());
    assertEquals("Gemini streaming response.", response.text());
    assertEquals(15L, response.usage().providerTotalTokens());
  }

  /** 验证快照重复 SSE fixture 解析去重，不双计。 */
  @Test
  void parsesSnapshotSseFixture() throws Exception {
    String sse = loadFixture("stream-snapshot.sse");
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
    feedSse(accumulator, sse);
    ProviderCompletion completion = accumulator.finish();
    ProviderResponse response = completion.response();

    assertEquals(GenerationStopReason.COMPLETE, response.stopReason());
    assertEquals("Hello world from Gemini!", response.text());
  }

  /** 验证思考过程 SSE fixture 提取 thinking 与 thoughtSignature。 */
  @Test
  void parsesThinkingSseFixture() throws Exception {
    String sse = loadFixture("stream-thinking.sse");
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
    feedSse(accumulator, sse);
    ProviderCompletion completion = accumulator.finish();
    ProviderResponse response = completion.response();

    assertEquals(GenerationStopReason.COMPLETE, response.stopReason());
    assertEquals("Analyzing query...", response.thinking());
    assertEquals("Result is 42.", response.text());
    assertEquals(4L, response.usage().reasoningTokens());
  }

  /** 验证工具调用 SSE fixture 提取 functionCall。 */
  @Test
  void parsesToolCallSseFixture() throws Exception {
    String sse = loadFixture("stream-tool-call.sse");
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
    feedSse(accumulator, sse);
    ProviderCompletion completion = accumulator.finish();
    ProviderResponse response = completion.response();

    assertEquals(GenerationStopReason.COMPLETE, response.stopReason());
    List<ProviderToolCall> calls = response.toolCalls();
    assertEquals(1, calls.size());

    ProviderToolCall call = calls.get(0);
    assertEquals("getLocation", call.name());
    assertEquals("call_loc", call.id());
    assertTrue(call.argumentsJson().contains("127.0.0.1"));
  }
}
