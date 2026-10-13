package fun.fengwk.kkstudio.harness.provider.gemini;

import static fun.fengwk.kkstudio.harness.provider.JsonNumberAssertions.assertExactNumber;
import static fun.fengwk.kkstudio.harness.provider.JsonNumberAssertions.assertPreservedScale;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.provider.ProviderStreamBridge;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 测试意图：Gemini 自由 JSON 数值语义的边界行为——工具 schema 与原生 opaque part 的数值以 {@link java.math.BigDecimal} 穿过
 * encoder/stream/replay 边界（{@code 1e309}/{@code 1e-324} 不得退化为 {@code Infinity}/{@code 0}，尾零原样保留）；
 * JSON null 的 finishReason 视为无语义尾帧，不产生停止原因。
 */
class GeminiPrecisionRegressionTest {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  private static final String FREE_FORM_SCHEMA =
      "{\"type\":\"object\",\"properties\":{"
          + "\"big\":{\"type\":\"number\",\"const\":1e309},"
          + "\"tiny\":{\"type\":\"number\",\"const\":1e-324},"
          + "\"exact\":{\"type\":\"number\",\"const\":0.1234567890123456789012345678},"
          + "\"ratio\":{\"type\":\"number\",\"const\":1.10}}}";

  private final ProviderDescriptor descriptor =
      new ProviderDescriptor(
          "google-test",
          ProviderType.GOOGLE,
          "https://generativelanguage.googleapis.com",
          new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)),
          new UUID(1L, 2L));
  private final ModelDescriptor model =
      new ModelDescriptor(
          "google-test",
          "gemini-2.5-flash",
          "gemini-2.5-flash",
          Set.of(ModelInputModality.TEXT),
          true,
          true);
  private final ProviderStreamBridge bridge =
      new ProviderStreamBridge(
          new ProviderStreamHandler() {
            @Override
            public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

            @Override
            public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

            @Override
            public void onError(ProviderException error, ProviderStream stream) {}
          });

  /** 工具 schema 是自由 JSON：数值原样进入 wire，尾零不被改写。 */
  @Test
  void encoderPreservesFreeFormToolSchemaNumberPrecision() throws Exception {
    ProviderRequest request =
        request(
            List.of(user("Hi")),
            List.of(new ProviderToolDefinition("calc", "compute", FREE_FORM_SCHEMA)));

    JsonNode consts =
        wire(request)
            .path("tools")
            .get(0)
            .path("functionDeclarations")
            .get(0)
            .path("parameters")
            .path("properties");

    assertExactNumber(consts.path("big").path("const"), "1e309");
    assertExactNumber(consts.path("tiny").path("const"), "1e-324");
    assertExactNumber(consts.path("exact").path("const"), "0.1234567890123456789012345678");
    assertExactNumber(consts.path("ratio").path("const"), "1.10");
    assertPreservedScale(consts.path("ratio").path("const"), "1.10");
  }

  /** 原生 part 的未知字段穿过 stream -> replay -> encoder 后数值类型与值仍精确。 */
  @Test
  void streamAndReplayPreserveOpaquePartNumberPrecision() throws Exception {
    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(request(List.of(user("Hi")), List.of()), descriptor, bridge);
    accumulator.handleEvent(
        "message",
        "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\","
            + "\"vendor_opaque\":{\"big\":1e309,\"tiny\":1e-324,"
            + "\"exact\":0.1234567890123456789012345678,\"ratio\":1.10}}]},"
            + "\"finishReason\":\"STOP\"}]}");

    ProviderCompletion completion = accumulator.finish();
    JsonNode opaque = completion.replayState().payload().path("parts").get(0).path("vendor_opaque");
    assertExactNumber(opaque.path("big"), "1e309");
    assertExactNumber(opaque.path("tiny"), "1e-324");
    assertExactNumber(opaque.path("exact"), "0.1234567890123456789012345678");
    assertExactNumber(opaque.path("ratio"), "1.10");
    assertPreservedScale(opaque.path("ratio"), "1.10");

    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("hi")),
            completion.replayState());
    JsonNode replayed =
        wire(request(List.of(user("Hi"), assistant), List.of()))
            .path("contents")
            .get(1)
            .path("parts")
            .get(0)
            .path("vendor_opaque");
    assertExactNumber(replayed.path("big"), "1e309");
    assertExactNumber(replayed.path("exact"), "0.1234567890123456789012345678");
    assertPreservedScale(replayed.path("ratio"), "1.10");
  }

  /** JSON null 的 finishReason 视为无语义尾帧：不设定停止原因，终止由后续有效原因决定。 */
  @Test
  void nullFinishReasonIsTreatedAsNoStopReason() {
    GeminiStreamAccumulator accumulator =
        new GeminiStreamAccumulator(request(List.of(user("Hi")), List.of()), descriptor, bridge);
    accumulator.handleEvent(
        "message",
        "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]},\"finishReason\":null}]}");
    accumulator.handleEvent("message", "{\"candidates\":[{\"finishReason\":\"STOP\"}]}");

    ProviderCompletion completion = accumulator.finish();
    assertEquals("hi", completion.response().text());
    assertEquals(GenerationStopReason.COMPLETE, completion.response().stopReason());
  }

  private ProviderRequest request(
      List<ProviderMessage> messages, List<ProviderToolDefinition> tools) {
    return new ProviderRequest(
        model,
        new ModelVariant("default"),
        1024,
        "Test system instruction.",
        messages,
        tools,
        ProviderCacheControl.none());
  }

  private JsonNode wire(ProviderRequest request) throws Exception {
    return MAPPER.readTree(new GeminiRequestEncoder().encode(request, descriptor).bodyUtf8Bytes());
  }

  private static ProviderMessage user(String text) {
    return new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock(text)));
  }
}
