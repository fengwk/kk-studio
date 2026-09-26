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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 验证 native-only block 的 opaque replay 与官方字段保真：
 *
 * <ul>
 *   <li>未知合法 block（如 server tool use）的 raw 形态与 {@code input_json_delta} 累积结果原样进入下一轮 wire；
 *   <li>已知 block 的官方附加字段（citations 等）不被字段白名单剥离；
 *   <li>native-only block 无法用 durable 语义表达，affinity/hash 失配时 fail closed 而不是静默丢弃。
 * </ul>
 */
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

  /**
   * 测试意图：server tool use 这类 native block 携带 {@code input_json_delta} 时，partial JSON 依序累积并在终态解析为
   * {@code input} 对象；block 其余字段（含未知官方字段）原样保留。旧的最小通用 merge 会把 {@code partial_json} 当成普通字段递归并入 {@code
   * input}，本测试即对该行为的反例。
   */
  @Test
  void assemblesNativeToolInputFromJsonDeltaAndReplaysExactly() throws IOException {
    ProviderRequest firstRequest = request(List.of(userMsg()));
    String frozenHash = encoder.encode(firstRequest, descriptor).sourcePrefixHash();

    AnthropicStreamAccumulator accumulator = accumulator(firstRequest, frozenHash);
    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"srv_1\",\"name\":\"web_search\",\"input\":{},\"future_field\":{\"x\":1}}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"query\\\":\\\"kk\\\"\"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"}\"}}");
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

    JsonNode content = replayState.payload().path("content");
    assertEquals(2, content.size());
    ObjectNode expectedNativeBlock = NODES.objectNode();
    expectedNativeBlock.put("type", "server_tool_use");
    expectedNativeBlock.put("id", "srv_1");
    expectedNativeBlock.put("name", "web_search");
    expectedNativeBlock.putObject("input").put("query", "kk");
    expectedNativeBlock.putObject("future_field").put("x", 1);
    assertEquals(expectedNativeBlock, content.get(0));
    assertEquals("text", content.get(1).path("type").asText());
    assertEquals("answer", content.get(1).path("text").asText());

    // 下一轮：native block（含解析后的 input）与已知 text 全部无损进入 wire
    ProviderRequest nextRequest =
        request(
            List.of(
                userMsg(), assistantMsg(List.of(new ProviderTextBlock("answer")), replayState)));
    JsonNode assistantContent =
        wire(encoder.encode(nextRequest, descriptor)).path("messages").get(1).path("content");
    assertEquals(2, assistantContent.size());
    assertEquals(expectedNativeBlock, assistantContent.get(0));
    assertEquals("answer", assistantContent.get(1).path("text").asText());
  }

  /** 测试意图：未携带 delta 的 native block 原样保留，绝不被补造或裁剪字段。 */
  @Test
  void keepsCompleteNativeBlockWithoutDeltaExactly() {
    ProviderRequest firstRequest = request(List.of(userMsg()));
    String frozenHash = encoder.encode(firstRequest, descriptor).sourcePrefixHash();

    AnthropicStreamAccumulator accumulator = accumulator(firstRequest, frozenHash);
    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":1}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"srv_1\",\"content\":[{\"type\":\"web_search_result\",\"url\":\"https://example.com\"}],\"flag\":false}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    JsonNode content = accumulator.finish().replayState().payload().path("content");
    assertEquals(1, content.size());
    assertEquals("web_search_tool_result", content.get(0).path("type").asText());
    assertEquals("srv_1", content.get(0).path("tool_use_id").asText());
    assertEquals("https://example.com", content.get(0).path("content").get(0).path("url").asText());
    assertEquals(false, content.get(0).path("flag").asBoolean());
    assertEquals(4, content.get(0).size());
  }

  /**
   * 测试意图：已知 block 的官方附加字段（citations 等）与 tool_use 的额外成员都无法用 durable 语义等价重建，因此 affinity 或 prefix hash
   * 失配时必须 fail closed；恰好 {@code type+text} 的 text block 与恰好 {@code type+id+name+input} 的 tool_use
   * 则仍回退语义编码。
   */
  @Test
  void failsClosedForKnownBlocksWithNativeOnlyMembersOnMismatch() throws IOException {
    // 1. 带 citations 与额外官方字段的 text block：hash 失配时 fail closed，绝不剥离这些字段
    ObjectNode citedPayload = NODES.objectNode();
    citedPayload.put("role", "assistant");
    ObjectNode citedBlock = citedPayload.putArray("content").addObject();
    citedBlock.put("type", "text");
    citedBlock.put("text", "cited answer");
    citedBlock.putArray("citations").addObject().put("type", "web_search_result_location");
    ProviderException citedHashMismatch =
        assertReplayRejected(
            replayState(citedPayload, FORGED_PREFIX_HASH),
            List.of(new ProviderTextBlock("cited answer")));
    assertEquals(
        "native replay blocks require matching affinity and source prefix hash",
        citedHashMismatch.getMessage());

    // 2. 同一 payload 的 affinity 失配同样 fail closed
    ProviderException citedAffinityMismatch =
        assertReplayRejected(
            new ProviderReplayState(
                ProviderReplayFormat.ANTHROPIC_MESSAGES,
                descriptor.affinity("another-model"),
                FORGED_PREFIX_HASH,
                citedPayload),
            List.of(new ProviderTextBlock("cited answer")));
    assertEquals(
        "native replay blocks require matching affinity and source prefix hash",
        citedAffinityMismatch.getMessage());

    // 3. 带额外成员的 tool_use：prefix hash 失配时 fail closed，绝不丢弃额外成员
    ObjectNode extraMemberToolPayload = NODES.objectNode();
    extraMemberToolPayload.put("role", "assistant");
    ObjectNode extraMemberTool = extraMemberToolPayload.putArray("content").addObject();
    extraMemberTool.put("type", "tool_use").put("id", "c1").put("name", "calc");
    extraMemberTool.putObject("input").put("x", 1);
    extraMemberTool.put("caller", "server");
    List<ProviderContentBlock> durableToolCall =
        List.of(new ProviderToolCallBlock(new ProviderToolCall("c1", "calc", "{\"x\":1}")));
    ProviderException toolHashMismatch =
        assertReplayRejected(
            replayState(extraMemberToolPayload, FORGED_PREFIX_HASH), durableToolCall);
    assertEquals(
        "native replay blocks require matching affinity and source prefix hash",
        toolHashMismatch.getMessage());

    // 4. 恰好 type+id+name+input 的 tool_use 失配时仍回退语义编码（由 durable 重建 wire）
    ObjectNode minimalToolPayload = NODES.objectNode();
    minimalToolPayload.put("role", "assistant");
    ObjectNode minimalTool = minimalToolPayload.putArray("content").addObject();
    minimalTool.put("type", "tool_use").put("id", "c1").put("name", "calc");
    minimalTool.putObject("input").put("x", 1);
    ProviderRequest fallbackRequest =
        request(
            List.of(
                userMsg(),
                assistantMsg(
                    durableToolCall, replayState(minimalToolPayload, FORGED_PREFIX_HASH))));
    JsonNode fallbackContent =
        wire(encoder.encode(fallbackRequest, descriptor)).path("messages").get(1).path("content");
    assertEquals(1, fallbackContent.size());
    assertEquals("tool_use", fallbackContent.get(0).path("type").asText());
    assertEquals("c1", fallbackContent.get(0).path("id").asText());
    assertEquals("calc", fallbackContent.get(0).path("name").asText());
    assertEquals(1, fallbackContent.get(0).path("input").path("x").asInt());
  }

  /**
   * 测试意图：native-only block 无法用 durable 语义表达，affinity/hash 失配时必须 fail closed；而纯已知 text payload 仍保留既有
   * semantic fallback 行为。
   */
  @Test
  void failsClosedForNativeOnlyReplayOnAffinityOrHashMismatch() throws IOException {
    // 1. 含 native-only block 的 payload + 伪造 hash -> 显式失败，绝不静默丢弃该 block
    ObjectNode nativePayload = NODES.objectNode();
    nativePayload.put("role", "assistant");
    ArrayNode nativeContent = nativePayload.putArray("content");
    nativeContent
        .addObject()
        .put("type", "server_tool_use")
        .put("id", "srv_1")
        .put("name", "web_search");
    nativeContent.addObject().put("type", "text").put("text", "answer");
    ProviderException hashMismatch =
        assertReplayRejected(
            replayState(nativePayload, FORGED_PREFIX_HASH),
            List.of(new ProviderTextBlock("answer")));
    assertEquals(
        "native replay blocks require matching affinity and source prefix hash",
        hashMismatch.getMessage());

    // 2. 含 native-only block 的 payload + affinity 失配 -> 同样显式失败
    ProviderException affinityMismatch =
        assertReplayRejected(
            new ProviderReplayState(
                ProviderReplayFormat.ANTHROPIC_MESSAGES,
                descriptor.affinity("another-model"),
                FORGED_PREFIX_HASH,
                nativePayload),
            List.of(new ProviderTextBlock("answer")));
    assertEquals(
        "native replay blocks require matching affinity and source prefix hash",
        affinityMismatch.getMessage());

    // 3. redacted_thinking 同样无法用 durable 语义表达 -> 失配时 fail closed
    ObjectNode redactedPayload = NODES.objectNode();
    redactedPayload.put("role", "assistant");
    ArrayNode redactedContent = redactedPayload.putArray("content");
    redactedContent.addObject().put("type", "redacted_thinking").put("data", "abc==");
    redactedContent.addObject().put("type", "text").put("text", "answer");
    ProviderException redactedMismatch =
        assertReplayRejected(
            replayState(redactedPayload, FORGED_PREFIX_HASH),
            List.of(new ProviderTextBlock("answer")));
    assertEquals(
        "native replay blocks require matching affinity and source prefix hash",
        redactedMismatch.getMessage());

    // 4. 纯已知 text payload 失配时仍静默回退语义编码
    ObjectNode knownPayload = NODES.objectNode();
    knownPayload.put("role", "assistant");
    knownPayload.putArray("content").addObject().put("type", "text").put("text", "answer");
    ProviderRequest nextRequest =
        request(
            List.of(
                userMsg(),
                assistantMsg(
                    List.of(new ProviderTextBlock("answer")),
                    replayState(knownPayload, FORGED_PREFIX_HASH))));
    JsonNode assistantContent =
        wire(encoder.encode(nextRequest, descriptor)).path("messages").get(1).path("content");
    assertEquals(1, assistantContent.size());
    assertEquals("text", assistantContent.get(0).path("type").asText());
    assertEquals("answer", assistantContent.get(0).path("text").asText());
  }

  /**
   * 测试意图：已知 block 的官方附加字段不被剥离。带 citations 与额外官方字段的 text block 仍通过 shape 与 durable
   * 校验（旧行为按精确字段数拒绝），并在下一轮 wire 中原样回放。
   */
  @Test
  void preservesExtraOfficialFieldsOnKnownReplayBlocks() throws IOException {
    ProviderRequest firstRequest = request(List.of(userMsg()));
    String frozenHash = encoder.encode(firstRequest, descriptor).sourcePrefixHash();

    ObjectNode payload = NODES.objectNode();
    payload.put("role", "assistant");
    ObjectNode textBlock = payload.putArray("content").addObject();
    textBlock.put("type", "text");
    textBlock.put("text", "cited answer");
    ArrayNode citations = textBlock.putArray("citations");
    citations
        .addObject()
        .put("type", "web_search_result_location")
        .put("url", "https://example.com/a")
        .put("cited_text", "cited answer");
    textBlock.putObject("future_official_field").put("k", "v");

    ProviderRequest nextRequest =
        request(
            List.of(
                userMsg(),
                assistantMsg(
                    List.of(new ProviderTextBlock("cited answer")),
                    replayState(payload, frozenHash))));
    JsonNode assistantContent =
        wire(encoder.encode(nextRequest, descriptor)).path("messages").get(1).path("content");
    assertEquals(1, assistantContent.size());
    assertEquals(textBlock, assistantContent.get(0));
  }

  /** 测试意图：citations 只做形状校验，不做字段白名单；形状非法时必须显式失败而不是回放损坏结构。 */
  @Test
  void rejectsInvalidCitationShapesInReplayPayload() {
    ProviderRequest firstRequest = request(List.of(userMsg()));
    String frozenHash = encoder.encode(firstRequest, descriptor).sourcePrefixHash();

    ObjectNode scalarCitations = NODES.objectNode();
    scalarCitations.put("role", "assistant");
    ObjectNode textBlock = scalarCitations.putArray("content").addObject();
    textBlock.put("type", "text");
    textBlock.put("text", "answer");
    textBlock.put("citations", "not-an-array");
    ProviderException notArray =
        assertReplayRejected(
            replayState(scalarCitations, frozenHash), List.of(new ProviderTextBlock("answer")));
    assertEquals("invalid text block in replay payload", notArray.getMessage());

    ObjectNode nonObjectCitation = NODES.objectNode();
    nonObjectCitation.put("role", "assistant");
    ObjectNode textBlock2 = nonObjectCitation.putArray("content").addObject();
    textBlock2.put("type", "text");
    textBlock2.put("text", "answer");
    textBlock2.putArray("citations").add("not-an-object");
    ProviderException notObject =
        assertReplayRejected(
            replayState(nonObjectCitation, frozenHash), List.of(new ProviderTextBlock("answer")));
    assertEquals("invalid text block in replay payload", notObject.getMessage());
  }

  /** 测试意图：native block 的 input_json_delta 累积结果不是合法 JSON 对象时，绝不伪造 replay，也不静默保留半成品。 */
  @Test
  void rejectsUnparsableNativeInputJsonInsteadOfFabricatingReplay() {
    ProviderRequest firstRequest = request(List.of(userMsg()));
    String frozenHash = encoder.encode(firstRequest, descriptor).sourcePrefixHash();

    AnthropicStreamAccumulator accumulator = accumulator(firstRequest, frozenHash);
    accumulator.handleEvent(
        "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"srv_1\",\"name\":\"web_search\"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"query\\\":\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":1}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderException error = assertThrows(ProviderException.class, accumulator::finish);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, error.kind());
    assertEquals(
        "invalid native block input JSON accumulated from input_json_delta", error.getMessage());
  }

  /** 测试意图：native block 若 start 已声明非空 input，后续 input_json_delta 无法无损合并，必须显式失败。 */
  @Test
  void rejectsNativeInputJsonDeltaWhenStartAlreadyDeclaresInput() {
    ProviderRequest firstRequest = request(List.of(userMsg()));
    String frozenHash = encoder.encode(firstRequest, descriptor).sourcePrefixHash();

    AnthropicStreamAccumulator accumulator = accumulator(firstRequest, frozenHash);
    accumulator.handleEvent(
        "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"srv_1\",\"name\":\"web_search\",\"input\":{\"query\":\"pre\"}}}");

    ProviderException error =
        assertThrows(
            ProviderException.class,
            () ->
                accumulator.handleEvent(
                    "content_block_delta",
                    "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"}\"}}"));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, error.kind());
    assertEquals(
        "native block input_json_delta conflicts with non-empty input already declared",
        error.getMessage());
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
            replayState(mismatchedText, frozenHash), List.of(new ProviderTextBlock("answer")));
    assertEquals("replay payload text does not match durable content", textError.getMessage());

    // 2. 未知 block 透传不放宽已知 type 的 shape 校验（text block 缺少 text 字段）
    ObjectNode missingTextField = NODES.objectNode();
    missingTextField.put("role", "assistant");
    ArrayNode content2 = missingTextField.putArray("content");
    content2.addObject().put("type", "future_block").put("label", "opaque");
    content2.addObject().put("type", "text");
    ProviderException shapeError =
        assertReplayRejected(
            replayState(missingTextField, frozenHash), List.of(new ProviderTextBlock("")));
    assertEquals("invalid text block in replay payload", shapeError.getMessage());

    // 3. 空 type 不是合法未知 block
    ObjectNode blankType = NODES.objectNode();
    blankType.put("role", "assistant");
    ArrayNode content3 = blankType.putArray("content");
    content3.addObject().put("type", "");
    content3.addObject().put("type", "text").put("text", "answer");
    ProviderException blankTypeError =
        assertReplayRejected(
            replayState(blankType, frozenHash), List.of(new ProviderTextBlock("answer")));
    assertEquals("invalid Anthropic replay payload block", blankTypeError.getMessage());

    // 4. 已知 thinking block 的 signature 仍必须存在且非空白
    ObjectNode blankSignature = NODES.objectNode();
    blankSignature.put("role", "assistant");
    ObjectNode thinkingBlock = blankSignature.putArray("content").addObject();
    thinkingBlock.put("type", "thinking");
    thinkingBlock.put("thinking", "t");
    thinkingBlock.put("signature", " ");
    ProviderException signatureError =
        assertReplayRejected(
            replayState(blankSignature, frozenHash), List.of(new ProviderThinkingBlock("t")));
    assertEquals("invalid thinking block in replay payload", signatureError.getMessage());
  }

  private ProviderReplayState replayState(ObjectNode payload, String sourcePrefixHash) {
    return new ProviderReplayState(
        ProviderReplayFormat.ANTHROPIC_MESSAGES,
        descriptor.affinity("claude-3-5-sonnet"),
        sourcePrefixHash,
        payload);
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
