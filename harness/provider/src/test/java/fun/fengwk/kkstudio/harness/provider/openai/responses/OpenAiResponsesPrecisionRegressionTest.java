package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static fun.fengwk.kkstudio.harness.provider.JsonNumberAssertions.assertExactNumber;
import static fun.fengwk.kkstudio.harness.provider.JsonNumberAssertions.assertPreservedScale;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 测试意图：OpenAI Responses 自由 JSON 数值语义的边界行为——工具 schema 与原生 opaque item 的数值以 {@link
 * java.math.BigDecimal} 穿过 encoder/stream/replay 边界（{@code 1e309}/{@code 1e-324} 不得退化为 {@code
 * Infinity}/{@code 0}，尾零原样保留）；JSON null 的 id/service_tier 回落协议默认（缺省），不产生 {@code "null"} 文本。
 */
class OpenAiResponsesPrecisionRegressionTest {

  private static final ObjectMapper MAPPER =
      JsonMapper.builder().enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

  private static final String FREE_FORM_SCHEMA =
      "{\"type\":\"object\",\"properties\":{"
          + "\"big\":{\"type\":\"number\",\"const\":1e309},"
          + "\"tiny\":{\"type\":\"number\",\"const\":1e-324},"
          + "\"exact\":{\"type\":\"number\",\"const\":0.1234567890123456789012345678},"
          + "\"ratio\":{\"type\":\"number\",\"const\":1.10}},"
          + "\"required\":[\"big\",\"tiny\",\"exact\",\"ratio\"],\"additionalProperties\":false}";

  private final ProviderDescriptor descriptor =
      new ProviderDescriptor(
          "openai_test",
          ProviderType.OPENAI_RESPONSES,
          "https://api.openai.com/v1",
          new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(60)),
          new UUID(1L, 2L));
  private final ModelDescriptor model =
      new ModelDescriptor(
          "openai_test",
          "gpt-5.4-mini",
          "gpt-5.4-mini",
          Set.of(ModelInputModality.TEXT),
          true,
          true);

  /** strict 归一化后的工具 schema 仍是自由 JSON：数值原样进入 wire，尾零不被改写。 */
  @Test
  void encoderPreservesFreeFormToolSchemaNumberPrecision() throws Exception {
    ProviderRequest request =
        request(
            List.of(), List.of(new ProviderToolDefinition("calc", "compute", FREE_FORM_SCHEMA)));

    JsonNode consts = wire(request).path("tools").get(0).path("parameters").path("properties");

    assertExactNumber(consts.path("big").path("const"), "1e309");
    assertExactNumber(consts.path("tiny").path("const"), "1e-324");
    assertExactNumber(consts.path("exact").path("const"), "0.1234567890123456789012345678");
    assertExactNumber(consts.path("ratio").path("const"), "1.10");
    assertPreservedScale(consts.path("ratio").path("const"), "1.10");
  }

  /** 原生 message item 的未知字段穿过 stream -> replay -> encoder 后数值类型与值仍精确。 */
  @Test
  void streamAndReplayPreserveOpaqueItemNumberPrecision() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            request(List.of(), List.of()), descriptor, event -> {});
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{"
                + "\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"hi\"}],"
                + "\"vendor_opaque\":{\"big\":1e309,\"tiny\":1e-324,"
                + "\"exact\":0.1234567890123456789012345678,\"ratio\":1.10}}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\","
                + "\"status\":\"completed\","
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}}"));

    ProviderCompletion completion = accumulator.finish();
    JsonNode opaque =
        firstWithField(completion.replayState().payload().path("output"), "vendor_opaque")
            .path("vendor_opaque");
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
        firstWithField(wire(request(List.of(assistant), List.of())).path("input"), "vendor_opaque")
            .path("vendor_opaque");
    assertExactNumber(replayed.path("big"), "1e309");
    assertExactNumber(replayed.path("exact"), "0.1234567890123456789012345678");
    assertPreservedScale(replayed.path("ratio"), "1.10");
  }

  /** JSON null 的 id/service_tier 回落协议默认（缺省），不被写成 {@code "null"} 文本。 */
  @Test
  void nullIdAndServiceTierFallBackToProtocolDefaults() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            request(List.of(), List.of()), descriptor, event -> {});
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.created\",\"response\":{\"id\":null,\"service_tier\":null}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.completed\",\"response\":{\"status\":\"completed\","
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}}"));

    ProviderCompletion completion = accumulator.finish();
    assertNull(completion.response().requestId());
    assertNull(completion.response().serviceTier());
    assertEquals(GenerationStopReason.COMPLETE, completion.response().stopReason());
  }

  private static JsonNode firstWithField(JsonNode array, String field) {
    for (JsonNode element : array) {
      if (element.has(field)) {
        return element;
      }
    }
    throw new AssertionError("no element carrying field " + field + " in " + array);
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
        new OpenAiResponsesRequestEncoder().encode(request, descriptor).bodyUtf8Bytes());
  }
}
