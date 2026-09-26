package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderContentBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
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

/** 验证未知合法 content block 的 opaque replay：raw block 与 delta 的保留、下一轮无损进入 wire，以及已知 block 严格校验不受影响。 */
class AnthropicOpaqueReplayTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
  private static final String SYSTEM_INSTRUCTION = "Test system instruction.";
  private static final String FORGED_PREFIX_HASH =
      "0000000000000000000000000000000000000000000000000000000000000000";

  private final AnthropicRequestEncoder encoder = new AnthropicRequestEncoder();
  private final ProviderDescriptor descriptor =
      new ProviderDescriptor(
          "test-anthropic",
          ProviderType.ANTHROPIC,
          "https://api.anthropic.com/v1",
          new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)),
          new UUID(1L, 2L));

  /** 测试意图：未知 block 的 raw 与 delta 合并结果在 replay 中被保留，并在下一轮无损进入 wire。 */
  @Test
  void preservesOpaqueBlockAndDeltaAcrossReplayRoundTrip() throws IOException {
    ProviderRequest firstRequest = request(List.of(userMsg()));
    String frozenHash = encoder.encode(firstRequest, descriptor).sourcePrefixHash();

    AnthropicStreamAccumulator accumulator = accumulator(firstRequest, frozenHash);
    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"future_block\",\"label\":\"a\",\"tags\":[\"t1\"],\"meta\":{\"x\":1},\"flag\":false,\"count\":1}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"opaque_delta\",\"label\":\"b\",\"tags\":[\"t2\"],\"meta\":{\"y\":2},\"flag\":true,\"count\":2}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"answer\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}");
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertEquals("answer", completion.response().text());
    ProviderReplayState replayState = completion.replayState();
    assertNotNull(replayState);
    assertEquals(ProviderReplayFormat.ANTHROPIC_MESSAGES, replayState.format());
    assertEquals(frozenHash, replayState.sourcePrefixHash());

    // 未知 block 的 raw 形态（含全部 delta 的最小通用 merge 结果）原样保留；已知 text block 由 normalized 状态合成
    JsonNode content = replayState.payload().path("content");
    assertEquals(2, content.size());
    assertEquals(expectedOpaqueBlock(), content.get(0));
    assertEquals("text", content.get(1).path("type").asText());
    assertEquals("answer", content.get(1).path("text").asText());

    // 下一轮：未知 block 无损进入 wire，已知 durable text 仍保持一致
    ProviderRequest nextRequest =
        request(
            List.of(
                userMsg(), assistantMsg(List.of(new ProviderTextBlock("answer")), replayState)));
    JsonNode messages = wire(encoder.encode(nextRequest, descriptor)).path("messages");
    JsonNode assistantContent = messages.get(1).path("content");
    assertEquals(2, assistantContent.size());
    assertEquals(expectedOpaqueBlock(), assistantContent.get(0));
    assertEquals("text", assistantContent.get(1).path("type").asText());
    assertEquals("answer", assistantContent.get(1).path("text").asText());
  }

  /** 测试意图：affinity/hash 失配时安全回退到 durable 语义，未知 block 被丢弃而非报错。 */
  @Test
  void dropsOpaqueBlockOnAffinityOrHashMismatchInsteadOfFailing() throws IOException {
    ObjectNode payload = NODES.objectNode();
    payload.put("role", "assistant");
    ArrayNode content = payload.putArray("content");
    content.addObject().put("type", "future_block").put("label", "opaque");
    content.addObject().put("type", "text").put("text", "answer");
    ProviderReplayState mismatchedHash =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor.affinity("claude-3-5-sonnet"),
            FORGED_PREFIX_HASH,
            payload);

    ProviderRequest nextRequest =
        request(
            List.of(
                userMsg(), assistantMsg(List.of(new ProviderTextBlock("answer")), mismatchedHash)));
    JsonNode assistantContent =
        wire(encoder.encode(nextRequest, descriptor)).path("messages").get(1).path("content");

    // 失配时安全 fallback：只发送 durable 语义，未知 block 不再进入 wire
    assertEquals(1, assistantContent.size());
    assertEquals("text", assistantContent.get(0).path("type").asText());
    assertEquals("answer", assistantContent.get(0).path("text").asText());
  }

  /** 测试意图：opaque 透传不放宽已知 block 的 shape 与 durable 一致性校验，空 type 也不算合法未知 block。 */
  @Test
  void keepsStrictDurableAndShapeValidationForKnownBlocks() {
    ProviderRequest firstRequest = request(List.of(userMsg()));
    String frozenHash = encoder.encode(firstRequest, descriptor).sourcePrefixHash();

    // 1. opaque block 存在也不能掩盖已知 durable text 不一致
    ObjectNode mismatchedText = NODES.objectNode();
    mismatchedText.put("role", "assistant");
    ArrayNode content1 = mismatchedText.putArray("content");
    content1.addObject().put("type", "future_block").put("label", "opaque");
    content1.addObject().put("type", "text").put("text", "different");
    ProviderException textError =
        assertReplayRejected(
            new ProviderReplayState(
                ProviderReplayFormat.ANTHROPIC_MESSAGES,
                descriptor.affinity("claude-3-5-sonnet"),
                frozenHash,
                mismatchedText),
            List.of(new ProviderTextBlock("answer")));
    assertEquals("replay payload text does not match durable content", textError.getMessage());

    // 2. 未知 block 透传不放宽已知 type 的 shape 校验（text block 缺少 text 字段）
    ObjectNode missingTextField = NODES.objectNode();
    missingTextField.put("role", "assistant");
    ArrayNode content2 = missingTextField.putArray("content");
    content2.addObject().put("type", "future_block").put("label", "opaque");
    content2.addObject().put("type", "text");
    ProviderException shapeError =
        assertReplayRejected(
            new ProviderReplayState(
                ProviderReplayFormat.ANTHROPIC_MESSAGES,
                descriptor.affinity("claude-3-5-sonnet"),
                frozenHash,
                missingTextField),
            List.of(new ProviderTextBlock("")));
    assertEquals("invalid text block in replay payload", shapeError.getMessage());

    // 3. 空 type 不是合法未知 block
    ObjectNode blankType = NODES.objectNode();
    blankType.put("role", "assistant");
    ArrayNode content3 = blankType.putArray("content");
    content3.addObject().put("type", "");
    content3.addObject().put("type", "text").put("text", "answer");
    ProviderException blankTypeError =
        assertReplayRejected(
            new ProviderReplayState(
                ProviderReplayFormat.ANTHROPIC_MESSAGES,
                descriptor.affinity("claude-3-5-sonnet"),
                frozenHash,
                blankType),
            List.of(new ProviderTextBlock("answer")));
    assertEquals("invalid Anthropic replay payload block", blankTypeError.getMessage());
  }

  private ProviderException assertReplayRejected(
      ProviderReplayState replayState, List<ProviderContentBlock> durableContents) {
    ProviderRequest request =
        request(List.of(userMsg(), assistantMsg(durableContents, replayState)));
    ProviderException error =
        assertThrows(ProviderException.class, () -> encoder.encode(request, descriptor));
    assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());
    return error;
  }

  /** delta 的最小通用 merge 期望结果：字符串追加、数组追加、对象递归合并、其余覆盖，且 delta 的 type 绝不覆盖 block 的 type。 */
  private static ObjectNode expectedOpaqueBlock() {
    ObjectNode expected = NODES.objectNode();
    expected.put("type", "future_block");
    expected.put("label", "ab");
    ArrayNode tags = expected.putArray("tags");
    tags.add("t1").add("t2");
    ObjectNode meta = expected.putObject("meta");
    meta.put("x", 1);
    meta.put("y", 2);
    expected.put("flag", true);
    expected.put("count", 2);
    return expected;
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
