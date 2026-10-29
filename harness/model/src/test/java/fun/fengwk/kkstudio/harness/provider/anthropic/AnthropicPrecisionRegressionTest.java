package fun.fengwk.kkstudio.harness.provider.anthropic;

import static fun.fengwk.kkstudio.harness.provider.JsonNumberAssertions.assertExactNumber;
import static fun.fengwk.kkstudio.harness.provider.JsonNumberAssertions.assertPreservedScale;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.model.ModelVariant;
import fun.fengwk.kkstudio.harness.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.provider.ProviderStreamBridge;
import fun.fengwk.kkstudio.harness.provider.ProviderTestFixtures;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 测试意图：Anthropic 自由 JSON 数值语义的边界行为——工具 schema 与原生 opaque block 的数值以 {@link java.math.BigDecimal} 穿过
 * encoder/stream/replay 边界（{@code 1e309}/{@code 1e-324} 不得退化为 {@code Infinity}/{@code
 * 0}，尾零原样保留）；message id 缺省回落为无 requestId，而显式 JSON null 一律 fail closed。
 */
class AnthropicPrecisionRegressionTest {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  private static final String FREE_FORM_SCHEMA =
      ProviderTestFixtures.read(
          AnthropicPrecisionRegressionTest.class,
          "/fun/fengwk/kkstudio/harness/provider/fixtures/precision/free-form-schema.json");

  private final ProviderDescriptor descriptor =
      new ProviderDescriptor(
          "test-anthropic",
          ProviderType.ANTHROPIC,
          "https://api.anthropic.com/v1",
          new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)),
          new UUID(1L, 2L));
  private final ModelDescriptor model =
      new ModelDescriptor(
          "test-anthropic",
          "claude-3-5-sonnet",
          "claude-3-5-sonnet",
          Set.of(ModelInputModality.TEXT),
          true,
          false);
  private final ProviderStreamBridge bridge =
      new ProviderStreamBridge(
          new ProviderStreamHandler() {
            @Override
            public void onEvent(ProviderStreamEvent event) {}

            @Override
            public void onComplete(ProviderCompletion completion) {}

            @Override
            public void onError(ProviderException error) {}
          });

  /** 工具 input_schema 是自由 JSON：数值原样进入 wire，尾零不被改写。 */
  @Test
  void encoderPreservesFreeFormToolSchemaNumberPrecision() throws Exception {
    ProviderRequest request =
        request(
            List.of(user("Hi")),
            List.of(new ProviderToolDefinition("calc", "compute", FREE_FORM_SCHEMA)));

    JsonNode consts = wire(request).path("tools").get(0).path("input_schema").path("properties");

    assertExactNumber(consts.path("big").path("const"), "1e309");
    assertExactNumber(consts.path("tiny").path("const"), "1e-324");
    assertExactNumber(consts.path("exact").path("const"), "0.1234567890123456789012345678");
    assertExactNumber(consts.path("ratio").path("const"), "1.10");
    assertPreservedScale(consts.path("ratio").path("const"), "1.10");
  }

  /** 原生 block 的未知字段穿过 stream -> replay -> encoder 后数值类型与值仍精确。 */
  @Test
  void streamAndReplayPreserveOpaqueBlockNumberPrecision() throws Exception {
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request(List.of(user("Hi")), List.of()), descriptor, bridge);
    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\","
            + "\"usage\":{\"input_tokens\":1}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\","
            + "\"text\":\"\",\"vendor_opaque\":{\"big\":1e309,\"tiny\":1e-324,"
            + "\"exact\":0.1234567890123456789012345678,\"ratio\":1.10}}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,"
            + "\"delta\":{\"type\":\"text_delta\",\"text\":\"hi\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
            + "\"usage\":{\"output_tokens\":1}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    JsonNode opaque =
        completion.replayState().payload().path("content").get(0).path("vendor_opaque");
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
            .path("messages")
            .get(1)
            .path("content")
            .get(0)
            .path("vendor_opaque");
    assertExactNumber(replayed.path("big"), "1e309");
    assertExactNumber(replayed.path("exact"), "0.1234567890123456789012345678");
    assertPreservedScale(replayed.path("ratio"), "1.10");
  }

  /** message id 缺省时回落为无 requestId；显式 JSON null 一律 fail closed，不被写成 {@code "null"}。 */
  @Test
  void missingMessageIdFallsBackToDefaultAndNullIdFailsClosed() {
    AnthropicStreamAccumulator withoutId =
        new AnthropicStreamAccumulator(request(List.of(user("Hi")), List.of()), descriptor, bridge);
    withoutId.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1}}}");
    withoutId.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,"
            + "\"content_block\":{\"type\":\"text\",\"text\":\"hi\"}}");
    withoutId.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
    withoutId.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},"
            + "\"usage\":{\"output_tokens\":1}}");
    withoutId.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = withoutId.finish();
    assertNull(completion.response().requestId());
    assertEquals("hi", completion.response().text());
    assertEquals(GenerationStopReason.COMPLETE, completion.response().stopReason());

    AnthropicStreamAccumulator withNullId =
        new AnthropicStreamAccumulator(request(List.of(user("Hi")), List.of()), descriptor, bridge);
    ProviderException error =
        assertThrows(
            ProviderException.class,
            () ->
                withNullId.handleEvent(
                    "message_start",
                    "{\"type\":\"message_start\",\"message\":{\"id\":null,"
                        + "\"usage\":{\"input_tokens\":1}}}"));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, error.kind());
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
    return MAPPER.readTree(
        new AnthropicRequestEncoder().encode(request, descriptor).bodyUtf8Bytes());
  }

  private static ProviderMessage user(String text) {
    return new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock(text)));
  }
}
