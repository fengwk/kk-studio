package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 验证官方 text citations 的无损链路：{@code citations_delta} 依序累积为 text block 的 {@code citations} 数组，终态
 * replay 与下一轮 wire 逐字节保留 citation 对象（包含未来新增字段）。
 */
class AnthropicCitationsReplayTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final String SYSTEM_INSTRUCTION = "Test system instruction.";

  private final AnthropicRequestEncoder encoder = new AnthropicRequestEncoder();
  private final ProviderDescriptor descriptor =
      new ProviderDescriptor(
          "test-anthropic",
          ProviderType.ANTHROPIC,
          "https://api.anthropic.com/v1",
          new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)),
          new UUID(1L, 2L));

  /** 测试意图：citations_delta 依序累积进 text block 的 citations，并原样进入 replay 与下一轮 wire。 */
  @Test
  void accumulatesCitationsInOrderAndReplaysExactly() throws IOException {
    ProviderRequest firstRequest = request(List.of(userMsg()));
    String frozenHash = encoder.encode(firstRequest, descriptor).sourcePrefixHash();

    AnthropicStreamAccumulator accumulator = accumulator(firstRequest, frozenHash);
    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_cite\",\"usage\":{\"input_tokens\":1}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Weather \"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"citations_delta\",\"citation\":{\"type\":\"web_search_result_location\",\"url\":\"https://example.com/a\",\"title\":\"A\",\"cited_text\":\"Weather \",\"future_field\":{\"x\":1}}}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"is sunny\"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"citations_delta\",\"citation\":{\"type\":\"document_location\",\"document_index\":0,\"cited_text\":\"is sunny\"}}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":4}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertEquals(GenerationStopReason.COMPLETE, completion.response().stopReason());
    assertEquals("Weather is sunny", completion.response().text());

    ObjectNode expectedTextBlock = NODES.objectNode();
    expectedTextBlock.put("type", "text");
    expectedTextBlock.put("text", "Weather is sunny");
    expectedTextBlock
        .putArray("citations")
        .add(
            MAPPER.readTree(
                "{\"type\":\"web_search_result_location\",\"url\":\"https://example.com/a\",\"title\":\"A\",\"cited_text\":\"Weather \",\"future_field\":{\"x\":1}}"))
        .add(
            MAPPER.readTree(
                "{\"type\":\"document_location\",\"document_index\":0,\"cited_text\":\"is sunny\"}"));

    ProviderReplayState replayState = completion.replayState();
    assertNotNull(replayState);
    JsonNode replayContent = replayState.payload().path("content");
    assertEquals(1, replayContent.size());
    assertEquals(expectedTextBlock, replayContent.get(0));

    // 下一轮：citations 逐字段无损进入 wire
    ProviderRequest nextRequest =
        request(
            List.of(
                userMsg(),
                assistantMsg(List.of(new ProviderTextBlock("Weather is sunny")), replayState)));
    JsonNode assistantContent =
        wire(encoder.encode(nextRequest, descriptor)).path("messages").get(1).path("content");
    assertEquals(1, assistantContent.size());
    assertEquals(expectedTextBlock, assistantContent.get(0));
  }

  /** 测试意图：start 已声明 citations 时，delta 依序追加在其后；无 delta 时 start 的 citations 原样保留。 */
  @Test
  void preservesStartDeclaredCitationsAndAppendsDeltasAfterThem() {
    ProviderRequest firstRequest = request(List.of(userMsg()));
    String frozenHash = encoder.encode(firstRequest, descriptor).sourcePrefixHash();

    AnthropicStreamAccumulator accumulator = accumulator(firstRequest, frozenHash);
    accumulator.handleEvent(
        "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\",\"citations\":[{\"type\":\"document_location\",\"cited_text\":\"start\"}]}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"body\"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"citations_delta\",\"citation\":{\"type\":\"document_location\",\"cited_text\":\"delta\"}}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"plain\",\"citations\":[{\"type\":\"document_location\",\"cited_text\":\"only\"}]}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}");
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":3}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    JsonNode content = accumulator.finish().replayState().payload().path("content");
    assertEquals(2, content.size());
    assertEquals("body", content.get(0).path("text").asText());
    assertEquals(2, content.get(0).path("citations").size());
    assertEquals("start", content.get(0).path("citations").get(0).path("cited_text").asText());
    assertEquals("delta", content.get(0).path("citations").get(1).path("cited_text").asText());
    assertEquals("plain", content.get(1).path("text").asText());
    assertEquals(1, content.get(1).path("citations").size());
    assertEquals("only", content.get(1).path("citations").get(0).path("cited_text").asText());
  }

  /** 测试意图：citations_delta 的 citation 必须是对象；否则显式失败而不是累积损坏的 citation。 */
  @Test
  void rejectsCitationsDeltaWithoutObjectCitation() {
    ProviderRequest firstRequest = request(List.of(userMsg()));
    String frozenHash = encoder.encode(firstRequest, descriptor).sourcePrefixHash();
    AnthropicStreamAccumulator accumulator = accumulator(firstRequest, frozenHash);
    accumulator.handleEvent(
        "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");

    ProviderException missing =
        assertThrows(
            ProviderException.class,
            () ->
                accumulator.handleEvent(
                    "content_block_delta",
                    "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"citations_delta\"}}"));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, missing.kind());
    assertEquals("citations_delta citation must be an object", missing.getMessage());

    ProviderException scalar =
        assertThrows(
            ProviderException.class,
            () ->
                accumulator.handleEvent(
                    "content_block_delta",
                    "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"citations_delta\",\"citation\":\"nope\"}}"));
    assertEquals("citations_delta citation must be an object", scalar.getMessage());
  }

  /** 测试意图：text block 只接受 text_delta 与 citations_delta，其它官方 delta 仍显式失败。 */
  @Test
  void rejectsNonTextDeltaForTextBlock() {
    ProviderRequest firstRequest = request(List.of(userMsg()));
    String frozenHash = encoder.encode(firstRequest, descriptor).sourcePrefixHash();
    AnthropicStreamAccumulator accumulator = accumulator(firstRequest, frozenHash);
    accumulator.handleEvent(
        "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");

    ProviderException error =
        assertThrows(
            ProviderException.class,
            () ->
                accumulator.handleEvent(
                    "content_block_delta",
                    "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"nope\"}}"));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, error.kind());
    assertEquals("mismatched delta type for text block", error.getMessage());
  }

  private AnthropicStreamAccumulator accumulator(
      ProviderRequest request, String frozenSourcePrefixHash) {
    return new AnthropicStreamAccumulator(
        request, descriptor, frozenSourcePrefixHash, new AnthropicStreamBridge(new NoopHandler()));
  }

  private static JsonNode wire(AnthropicEncodedRequest encoded) throws IOException {
    return MAPPER.readTree(encoded.bodyUtf8Bytes());
  }

  private ProviderRequest request(List<ProviderMessage> messages) {
    return new ProviderRequest(
        model(),
        new ModelVariant("default"),
        1024,
        SYSTEM_INSTRUCTION,
        messages,
        List.of(),
        ProviderCacheControl.none());
  }

  private static ProviderMessage userMsg() {
    return new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("hi")));
  }

  private static ProviderMessage assistantMsg(
      List<ProviderContentBlock> blocks, ProviderReplayState replayState) {
    return new ProviderMessage(ProviderMessageRole.ASSISTANT, blocks, replayState);
  }

  private static ModelDescriptor model() {
    return new ModelDescriptor(
        "test-anthropic",
        "claude-3-5-sonnet",
        "claude-3-5-sonnet",
        Set.of(ModelInputModality.TEXT),
        true,
        false,
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
            BigDecimal.ZERO));
  }

  private static final class NoopHandler implements ProviderStreamHandler {

    @Override
    public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

    @Override
    public void onError(ProviderException error, ProviderStream stream) {}
  }
}
