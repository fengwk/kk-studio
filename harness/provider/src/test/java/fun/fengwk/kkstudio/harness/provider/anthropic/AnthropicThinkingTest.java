package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayAffinity;
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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * 原生 Anthropic 自适应思考（adaptive reasoning）、签名（signature）、redacted_thinking 与多轮工具重放测试套件。
 *
 * <p>遵循 kk-studio 极简与自适应架构契约：
 *
 * <ul>
 *   <li>全面覆盖上游 AnthropicChatModelThinkingIT 与 AnthropicStreamingChatModelThinkingIT 的 7 个模型维度，验证
 *       model affinity 与 pass-through 正确性。
 *   <li>验证真实自适应参数 thinking.type=adaptive 与 output_config.effort，拒绝构造 SDK 私有虚假字段（如
 *       thinkingType=enabled、budget_tokens、sendThinking、returnThinking 等字段在 kk-studio 中故意不存在）。
 *   <li>验证流式 ThinkingDelta、SignatureDelta、TextDelta 与 ToolCallDelta 真实事件序列。
 *   <li>验证 terminal ProviderCompletion、durable 内容装配以及基于 canonical prefix hash 的原生 wire 重放。
 * </ul>
 */
class AnthropicThinkingTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

  private static final UUID CONNECTION_GEN_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");

  private ProviderDescriptor descriptor;
  private AnthropicRequestEncoder encoder;

  @BeforeEach
  void setUp() {
    descriptor =
        new ProviderDescriptor(
            "anthropic-thinking",
            ProviderType.ANTHROPIC,
            "https://api.anthropic.com/v1",
            new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)),
            CONNECTION_GEN_ID);
    encoder = new AnthropicRequestEncoder();
  }

  /**
   * 上游 AnthropicChatModelThinkingIT 排除 CLAUDE_OPUS_4_7 后的 7 个真实模型维度。
   *
   * <p>按模型名称在参数化测试中注入，验证各模型在请求编码、流累积与亲和性匹配下的一致行为。
   */
  static Stream<String> upstreamModels() {
    return Stream.of(
        "claude-opus-4-8",
        "claude-opus-4-6",
        "claude-sonnet-4-6",
        "claude-opus-4-5-20251101",
        "claude-sonnet-4-5-20250929",
        "claude-haiku-4-5-20251001",
        "claude-opus-4-1-20250805");
  }

  /**
   * 对应上游 should_return_and_send_thinking：覆盖 7 个模型维度。
   *
   * <p>测试意图：验证自适应思考全生命周期：请求编码阶段输出 thinking.type=adaptive 与 exact effort； 流式阶段按序派发 ThinkingDelta 与
   * TextDelta；终态累积完整的 thinking 与 text 并生成携带 signature 的 ReplayState； 在同亲和性、同前缀的多轮后续请求中，wire
   * 协议精准重放原生 thinking 块与 signature。
   */
  @ParameterizedTest
  @MethodSource("upstreamModels")
  void should_return_and_send_thinking(String modelName) throws IOException {
    // 1. 首轮请求构建与编码断言
    ModelDescriptor modelDesc = createReasoningModel(modelName);
    ModelVariant variant =
        new ModelVariant("default", 2048, null, null, null, null, null, List.of(), "high");
    ProviderMessage userMsg1 =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(new ProviderTextBlock("What is the capital of Germany?")));

    ProviderRequest turn1Request =
        new ProviderRequest(
            modelDesc, variant, List.of(userMsg1), List.of(), ProviderCacheControl.none());

    AnthropicEncodedRequest encodedTurn1 = encoder.encode(turn1Request, descriptor);
    JsonNode turn1WireRoot = MAPPER.readTree(encodedTurn1.bodyUtf8Bytes());

    assertEquals(modelName, turn1WireRoot.path("model").asText());
    assertEquals("adaptive", turn1WireRoot.path("thinking").path("type").asText());
    assertEquals("high", turn1WireRoot.path("output_config").path("effort").asText());
    assertFalse(
        turn1WireRoot.path("thinking").has("budget_tokens"),
        "kk-studio uses adaptive thinking without budget_tokens");
    assertFalse(turn1WireRoot.has("sendThinking"));
    assertFalse(turn1WireRoot.has("returnThinking"));

    String frozenPrefixHash = encodedTurn1.sourcePrefixHash();
    assertNotNull(frozenPrefixHash);

    // 2. 流式累积与事件顺序断言
    RecordingStreamHandler handler = new RecordingStreamHandler();
    AnthropicStreamBridge bridge = new AnthropicStreamBridge(handler);
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(turn1Request, descriptor, frozenPrefixHash, bridge);

    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_think_de\",\"usage\":{\"input_tokens\":14,\"output_tokens\":0}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"Analyzing geography: \"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Berlin is the capital of Germany.\"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig_berlin_valid_token\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"The capital of Germany is Berlin.\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}");
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":28}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    bridge.emitComplete(completion);

    // 断言流事件顺序与内容
    assertEquals(3, handler.events.size());
    assertInstanceOf(ProviderStreamEvent.ThinkingDelta.class, handler.events.get(0));
    assertEquals(
        "Analyzing geography: ",
        ((ProviderStreamEvent.ThinkingDelta) handler.events.get(0)).text());
    assertInstanceOf(ProviderStreamEvent.ThinkingDelta.class, handler.events.get(1));
    assertEquals(
        "Berlin is the capital of Germany.",
        ((ProviderStreamEvent.ThinkingDelta) handler.events.get(1)).text());
    assertInstanceOf(ProviderStreamEvent.TextDelta.class, handler.events.get(2));
    assertEquals(
        "The capital of Germany is Berlin.",
        ((ProviderStreamEvent.TextDelta) handler.events.get(2)).text());

    // 断言终态 completion
    assertEquals(
        "Analyzing geography: Berlin is the capital of Germany.", completion.response().thinking());
    assertEquals("The capital of Germany is Berlin.", completion.response().text());
    assertEquals(GenerationStopReason.COMPLETE, completion.response().stopReason());

    ProviderReplayState replayState = completion.replayState();
    assertNotNull(replayState, "replayState must be formed for successful turn with signature");
    assertEquals(ProviderReplayFormat.ANTHROPIC_MESSAGES, replayState.format());
    assertEquals(descriptor.affinity(modelName), replayState.affinity());
    assertEquals(frozenPrefixHash, replayState.sourcePrefixHash());

    // 3. 次轮后续请求重放 wire 断言
    ProviderMessage asstMsg1 =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("Analyzing geography: Berlin is the capital of Germany."),
                new ProviderTextBlock("The capital of Germany is Berlin.")),
            replayState);
    ProviderMessage userMsg2 =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(new ProviderTextBlock("What is the capital of France?")));

    ProviderRequest turn2Request =
        new ProviderRequest(
            modelDesc,
            variant,
            List.of(userMsg1, asstMsg1, userMsg2),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encodedTurn2 = encoder.encode(turn2Request, descriptor);
    JsonNode turn2WireRoot = MAPPER.readTree(encodedTurn2.bodyUtf8Bytes());
    ArrayNode wireMessages = (ArrayNode) turn2WireRoot.path("messages");

    assertEquals(3, wireMessages.size());
    JsonNode replayedAssistant = wireMessages.get(1);
    assertEquals("assistant", replayedAssistant.path("role").asText());
    ArrayNode asstContent = (ArrayNode) replayedAssistant.path("content");
    assertEquals(2, asstContent.size());

    // 严格验证原生 thinking 块及其 signature 均在 wire 上精准重放
    assertEquals("thinking", asstContent.get(0).path("type").asText());
    assertEquals(
        "Analyzing geography: Berlin is the capital of Germany.",
        asstContent.get(0).path("thinking").asText());
    assertEquals("sig_berlin_valid_token", asstContent.get(0).path("signature").asText());

    assertEquals("text", asstContent.get(1).path("type").asText());
    assertEquals("The capital of Germany is Berlin.", asstContent.get(1).path("text").asText());
  }

  /**
   * 对应上游 should_return_and_send_thinking_with_tools：覆盖 7 个模型维度。
   *
   * <p>测试意图：验证包含工具调用的自适应思考流式处理与重放：模型先进行内部思考输出 signature， 紧随 tool_use 块；验证 ThinkingDelta 与
   * ToolCallDelta 正确派发；验证 stop_reason=tool_use 时生成持久化工具调用与 包含 thinking+tool_use 的 ReplayState；后续带
   * ToolResult 请求时 wire 协议严格保序重现 thinking 块与 tool_use 块。
   */
  @ParameterizedTest
  @MethodSource("upstreamModels")
  void should_return_and_send_thinking_with_tools(String modelName) throws IOException {
    ModelDescriptor modelDesc = createReasoningModel(modelName);
    ModelVariant variant =
        new ModelVariant("default", 2048, null, null, null, null, null, List.of(), "medium");
    ProviderToolDefinition tool =
        new ProviderToolDefinition(
            "getWeather",
            "Retrieve current weather",
            "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}");

    ProviderMessage userMsg1 =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(new ProviderTextBlock("What is the weather in Munich?")));

    ProviderRequest turn1Request =
        new ProviderRequest(
            modelDesc, variant, List.of(userMsg1), List.of(tool), ProviderCacheControl.none());

    AnthropicEncodedRequest encodedTurn1 = encoder.encode(turn1Request, descriptor);
    String frozenPrefixHash = encodedTurn1.sourcePrefixHash();

    RecordingStreamHandler handler = new RecordingStreamHandler();
    AnthropicStreamBridge bridge = new AnthropicStreamBridge(handler);
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(turn1Request, descriptor, frozenPrefixHash, bridge);

    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_tool_1\",\"usage\":{\"input_tokens\":20,\"output_tokens\":0}}}");
    // Block 0: thinking
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"User wants weather in Munich. \"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Calling getWeather tool.\"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig_tool_call_munich_999\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
    // Block 1: tool_use
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"call_munich_101\",\"name\":\"getWeather\",\"input\":{}}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\\\"Munich\\\"}\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}");
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":35}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    bridge.emitComplete(completion);

    // 验证事件
    assertEquals(4, handler.events.size());
    assertInstanceOf(ProviderStreamEvent.ThinkingDelta.class, handler.events.get(0));
    assertInstanceOf(ProviderStreamEvent.ThinkingDelta.class, handler.events.get(1));

    // tool_use 启动事件：声明 toolOrdinal、id 与 name
    assertInstanceOf(ProviderStreamEvent.ToolCallDelta.class, handler.events.get(2));
    ProviderStreamEvent.ToolCallDelta startDelta =
        (ProviderStreamEvent.ToolCallDelta) handler.events.get(2);
    assertEquals(0, startDelta.index());
    assertEquals("call_munich_101", startDelta.id());
    assertEquals("getWeather", startDelta.name());
    assertNull(startDelta.argumentsJson());

    // input_json_delta 增量事件：增量提供参数 JSON 片段
    assertInstanceOf(ProviderStreamEvent.ToolCallDelta.class, handler.events.get(3));
    ProviderStreamEvent.ToolCallDelta jsonDelta =
        (ProviderStreamEvent.ToolCallDelta) handler.events.get(3);
    assertEquals(0, jsonDelta.index());
    assertNull(jsonDelta.id());
    assertNull(jsonDelta.name());
    assertEquals("{\"city\":\"Munich\"}", jsonDelta.argumentsJson());

    // 验证 completion
    assertEquals(
        "User wants weather in Munich. Calling getWeather tool.", completion.response().thinking());
    assertEquals(1, completion.response().toolCalls().size());
    ProviderToolCall toolCall = completion.response().toolCalls().get(0);
    assertEquals("call_munich_101", toolCall.id());
    assertEquals("getWeather", toolCall.name());
    assertEquals("{\"city\":\"Munich\"}", toolCall.argumentsJson());
    assertEquals(GenerationStopReason.COMPLETE, completion.response().stopReason());

    ProviderReplayState replayState = completion.replayState();
    assertNotNull(replayState);
    assertEquals(ProviderReplayFormat.ANTHROPIC_MESSAGES, replayState.format());

    // 次轮带 ToolResult 的请求编码验证
    ProviderMessage asstMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("User wants weather in Munich. Calling getWeather tool."),
                new ProviderToolCallBlock(toolCall)),
            replayState);
    ProviderMessage toolResultMsg =
        new ProviderMessage(
            ProviderMessageRole.TOOL,
            List.of(
                new ProviderToolResultBlock(
                    "call_munich_101",
                    "getWeather",
                    List.of(new ProviderTextBlock("Sunny, 21C")),
                    false,
                    "{}")));

    ProviderRequest turn2Request =
        new ProviderRequest(
            modelDesc,
            variant,
            List.of(userMsg1, asstMsg, toolResultMsg),
            List.of(tool),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encodedTurn2 = encoder.encode(turn2Request, descriptor);
    JsonNode turn2Wire = MAPPER.readTree(encodedTurn2.bodyUtf8Bytes());
    ArrayNode messages = (ArrayNode) turn2Wire.path("messages");

    assertEquals(3, messages.size());
    JsonNode asstWire = messages.get(1);
    assertEquals("assistant", asstWire.path("role").asText());
    ArrayNode asstWireContent = (ArrayNode) asstWire.path("content");
    assertEquals(2, asstWireContent.size());

    // wire 保序断言：thinking (带签名) 必须在 tool_use 前
    assertEquals("thinking", asstWireContent.get(0).path("type").asText());
    assertEquals(
        "User wants weather in Munich. Calling getWeather tool.",
        asstWireContent.get(0).path("thinking").asText());
    assertEquals("sig_tool_call_munich_999", asstWireContent.get(0).path("signature").asText());

    assertEquals("tool_use", asstWireContent.get(1).path("type").asText());
    assertEquals("call_munich_101", asstWireContent.get(1).path("id").asText());
    assertEquals("getWeather", asstWireContent.get(1).path("name").asText());
    assertEquals("Munich", asstWireContent.get(1).path("input").path("city").asText());

    // 工具结果 wire 断言
    JsonNode toolWire = messages.get(2);
    assertEquals("user", toolWire.path("role").asText());
    assertEquals("tool_result", toolWire.path("content").get(0).path("type").asText());
    assertEquals("call_munich_101", toolWire.path("content").get(0).path("tool_use_id").asText());
  }

  /**
   * 验证 Anthropic redacted_thinking 块在流式与回放中的处理。
   *
   * <p>测试意图：当 Anthropic 返回加密脱敏思考块（redacted_thinking）时，提取 base64 data 并纳入 ReplayState； 在后续多轮请求中，能够以
   * native 格式在 assistant 消息中精确重放 redacted_thinking 块。
   */
  @ParameterizedTest
  @MethodSource("upstreamModels")
  void should_support_and_replay_redacted_thinking(String modelName) throws IOException {
    ModelDescriptor modelDesc = createReasoningModel(modelName);
    ModelVariant variant =
        new ModelVariant("default", 2048, null, null, null, null, null, List.of(), "high");
    ProviderMessage userMsg =
        new ProviderMessage(
            ProviderMessageRole.USER, List.of(new ProviderTextBlock("Explain quantum mechanics")));

    ProviderRequest req =
        new ProviderRequest(
            modelDesc, variant, List.of(userMsg), List.of(), ProviderCacheControl.none());
    String hash = encoder.encode(req, descriptor).sourcePrefixHash();

    RecordingStreamHandler handler = new RecordingStreamHandler();
    AnthropicStreamBridge bridge = new AnthropicStreamBridge(handler);
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(req, descriptor, hash, bridge);

    String encryptedThinkingData = "bWlzdGVyaW91cy1lbmNyeXB0ZWQtdGhpbmtpbmctYmxvYg==";

    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_redacted_1\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"redacted_thinking\",\"data\":\""
            + encryptedThinkingData
            + "\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"Quantum mechanics answer.\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}");
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":20}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertNotNull(completion.replayState());
    assertEquals("", completion.response().thinking());
    assertEquals("Quantum mechanics answer.", completion.response().text());

    // 次轮重放验证
    ProviderMessage asstMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderTextBlock("Quantum mechanics answer.")),
            completion.replayState());
    ProviderMessage nextUser =
        new ProviderMessage(
            ProviderMessageRole.USER, List.of(new ProviderTextBlock("Follow up question")));

    ProviderRequest followUpReq =
        new ProviderRequest(
            modelDesc,
            variant,
            List.of(userMsg, asstMsg, nextUser),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(followUpReq, descriptor);
    JsonNode root = MAPPER.readTree(encoded.bodyUtf8Bytes());
    JsonNode asstWireContent = root.path("messages").get(1).path("content");

    assertEquals(2, asstWireContent.size());
    assertEquals("redacted_thinking", asstWireContent.get(0).path("type").asText());
    assertEquals(encryptedThinkingData, asstWireContent.get(0).path("data").asText());
    assertEquals("text", asstWireContent.get(1).path("type").asText());
  }

  /**
   * 验证思考块缺失或包含空白 signature 时，系统主动禁用 ReplayState 生成，防止向上游回放无签名的非法 thinking 块。
   *
   * <p>测试意图：证明无 signature 的 thinking 无法形成有效 ReplayState（返回 null）， 且在其降级编码时自动将 thinking 转换为普通 text
   * 块以防上游协议校验报错。
   */
  @Test
  void should_disable_replay_when_signature_is_missing() throws IOException {
    ModelDescriptor modelDesc = createReasoningModel("claude-sonnet-4-6");
    ModelVariant variant =
        new ModelVariant("default", 2048, null, null, null, null, null, List.of(), "high");
    ProviderMessage userMsg =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));
    ProviderRequest req =
        new ProviderRequest(
            modelDesc, variant, List.of(userMsg), List.of(), ProviderCacheControl.none());
    String hash = encoder.encode(req, descriptor).sourcePrefixHash();

    RecordingStreamHandler handler = new RecordingStreamHandler();
    AnthropicStreamBridge bridge = new AnthropicStreamBridge(handler);
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(req, descriptor, hash, bridge);

    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_no_sig\",\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}}");
    // thinking block without signature delta
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"Thinking without signature.\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"Response\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}");
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":10}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertNull(
        completion.replayState(),
        "ReplayState must be disabled (null) when thinking signature is missing");

    // 验证降级回放：无 replayState 时，thinking 块安全降级为普通 text
    ProviderMessage asstMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("Thinking without signature."),
                new ProviderTextBlock("Response")),
            null);
    ProviderRequest nextReq =
        new ProviderRequest(
            modelDesc,
            variant,
            List.of(userMsg, asstMsg, userMsg),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(nextReq, descriptor);
    JsonNode asstWire = MAPPER.readTree(encoded.bodyUtf8Bytes()).path("messages").get(1);
    ArrayNode contents = (ArrayNode) asstWire.path("content");

    // 均降级为 text，绝不包含无签名的 thinking 块
    assertEquals("text", contents.get(0).path("type").asText());
    assertEquals("Thinking without signature.", contents.get(0).path("text").asText());
    assertEquals("text", contents.get(1).path("type").asText());
  }

  /**
   * 验证当 ReplayState 的模型亲和性（affinity）与当前请求模型不匹配时，自动禁用 native 重放并回退为语义降级。
   *
   * <p>测试意图：防止跨模型复用思考块或签名导致的协议不兼容或数据污染。
   */
  @Test
  void should_disable_replay_when_affinity_mismatches() throws IOException {
    ModelDescriptor modelDesc = createReasoningModel("claude-opus-4-8");
    ModelVariant variant =
        new ModelVariant("default", 2048, null, null, null, null, null, List.of(), "high");
    ProviderMessage userMsg =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));
    ProviderRequest req =
        new ProviderRequest(
            modelDesc, variant, List.of(userMsg), List.of(), ProviderCacheControl.none());
    String currentHash = encoder.encode(req, descriptor).sourcePrefixHash();

    // 构造模型不一致的 affinity (例如来自 claude-haiku-4-5-20251001)
    ProviderReplayAffinity mismatchedAffinity =
        new ProviderReplayAffinity(
            ProviderType.ANTHROPIC,
            "anthropic-thinking",
            CONNECTION_GEN_ID,
            "claude-haiku-4-5-20251001");

    ObjectNode payload = NODES.objectNode();
    payload.put("role", "assistant");
    ArrayNode content = payload.putArray("content");
    content
        .addObject()
        .put("type", "thinking")
        .put("thinking", "Thought")
        .put("signature", "sig_valid");
    content.addObject().put("type", "text").put("text", "Text");

    ProviderReplayState replayState =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES, mismatchedAffinity, currentHash, payload);

    ProviderMessage asstMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderThinkingBlock("Thought"), new ProviderTextBlock("Text")),
            replayState);

    ProviderRequest followUp =
        new ProviderRequest(
            modelDesc,
            variant,
            List.of(userMsg, asstMsg, userMsg),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(followUp, descriptor);
    JsonNode asstWire = MAPPER.readTree(encoded.bodyUtf8Bytes()).path("messages").get(1);
    ArrayNode contents = (ArrayNode) asstWire.path("content");

    // 亲和性不匹配，触发语义降级：thinking 块降级为 text，不输出 signature
    assertEquals("text", contents.get(0).path("type").asText());
    assertEquals("Thought", contents.get(0).path("text").asText());
    assertFalse(contents.get(0).has("signature"));
  }

  /**
   * 验证当前缀哈希（sourcePrefixHash）不匹配时（例如历史对话或提示词改动），自动禁用 native 重放并回退为语义降级。
   *
   * <p>测试意图：验证对话历史前缀校验契约，杜绝由于前缀错位造成 Anthropic 拒绝请求。
   */
  @Test
  void should_disable_replay_when_source_prefix_hash_mismatches() throws IOException {
    ModelDescriptor modelDesc = createReasoningModel("claude-sonnet-4-6");
    ModelVariant variant =
        new ModelVariant("default", 2048, null, null, null, null, null, List.of(), "high");
    ProviderMessage userMsg =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));

    // 构造伪造或过期的 prefix hash
    String forgedHash = "0000000000000000000000000000000000000000000000000000000000000000";

    ObjectNode payload = NODES.objectNode();
    payload.put("role", "assistant");
    ArrayNode content = payload.putArray("content");
    content
        .addObject()
        .put("type", "thinking")
        .put("thinking", "Thought")
        .put("signature", "sig_valid");
    content.addObject().put("type", "text").put("text", "Text");

    ProviderReplayState replayState =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor.affinity("claude-sonnet-4-6"),
            forgedHash,
            payload);

    ProviderMessage asstMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderThinkingBlock("Thought"), new ProviderTextBlock("Text")),
            replayState);

    ProviderRequest followUp =
        new ProviderRequest(
            modelDesc,
            variant,
            List.of(userMsg, asstMsg, userMsg),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(followUp, descriptor);
    JsonNode asstWire = MAPPER.readTree(encoded.bodyUtf8Bytes()).path("messages").get(1);
    ArrayNode contents = (ArrayNode) asstWire.path("content");

    // 前缀哈希不一致，自动降级为语义文本
    assertEquals("text", contents.get(0).path("type").asText());
    assertEquals("Thought", contents.get(0).path("text").asText());
  }

  /**
   * 验证当 ReplayState 内保存的 payload 与应用层 durable 内容不一致时，防篡改校验生效并自动降级为语义文本。
   *
   * <p>测试意图：防止 payload 内容被恶意篡改导致发往模型的请求偏离实际历史记录。
   */
  @Test
  void should_disable_replay_when_payload_durable_contents_mismatch() throws IOException {
    ModelDescriptor modelDesc = createReasoningModel("claude-sonnet-4-6");
    ModelVariant variant =
        new ModelVariant("default", 2048, null, null, null, null, null, List.of(), "high");
    ProviderMessage userMsg =
        new ProviderMessage(ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hi")));
    ProviderRequest req =
        new ProviderRequest(
            modelDesc, variant, List.of(userMsg), List.of(), ProviderCacheControl.none());
    String currentHash = encoder.encode(req, descriptor).sourcePrefixHash();

    ObjectNode payload = NODES.objectNode();
    payload.put("role", "assistant");
    ArrayNode content = payload.putArray("content");
    content
        .addObject()
        .put("type", "thinking")
        .put("thinking", "Original Thought in payload")
        .put("signature", "sig_valid");
    content.addObject().put("type", "text").put("text", "Text");

    ProviderReplayState replayState =
        new ProviderReplayState(
            ProviderReplayFormat.ANTHROPIC_MESSAGES,
            descriptor.affinity("claude-sonnet-4-6"),
            currentHash,
            payload);

    // Durable 内容中的 thinking 与 payload 不匹配
    ProviderMessage tamperedMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("Tampered Thought in durable content"),
                new ProviderTextBlock("Text")),
            replayState);

    ProviderRequest followUp =
        new ProviderRequest(
            modelDesc,
            variant,
            List.of(userMsg, tamperedMsg, userMsg),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encoded = encoder.encode(followUp, descriptor);
    JsonNode asstWire = MAPPER.readTree(encoded.bodyUtf8Bytes()).path("messages").get(1);
    ArrayNode contents = (ArrayNode) asstWire.path("content");

    // 不匹配拒绝 native 重放，降级为 text
    assertEquals("text", contents.get(0).path("type").asText());
    assertEquals("Tampered Thought in durable content", contents.get(0).path("text").asText());
  }

  /**
   * 验证未显式开启 reasoning（reasoning=false 或 reasoningEffort=null）时，请求体中绝不输出 thinking 字段与 output_config
   * 字段，且不引入非法的 upstream SDK 字段（如 budget_tokens、thinkingType 等）。
   *
   * <p>测试意图：验证 kk-studio 严格遵循极简原则，对非 reasoning 请求不添加任何多余推理控制属性。
   */
  @Test
  void should_not_emit_thinking_parameters_when_reasoning_not_requested() throws IOException {
    // 情况 1: model.reasoning = false
    ModelDescriptor nonReasoningModel =
        new ModelDescriptor(
            "anthropic-thinking",
            "claude-3-5-sonnet",
            Set.of(ModelInputModality.TEXT),
            true,
            false, // disabled
            pricing());
    ModelVariant variantWithEffort =
        new ModelVariant("default", 2048, null, null, null, null, null, List.of(), "high");

    ProviderRequest req1 =
        new ProviderRequest(
            nonReasoningModel,
            variantWithEffort,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hello")))),
            List.of(),
            ProviderCacheControl.none());

    JsonNode wire1 = MAPPER.readTree(encoder.encode(req1, descriptor).bodyUtf8Bytes());
    assertFalse(wire1.has("thinking"), "thinking must not be emitted when reasoning=false");
    assertFalse(
        wire1.has("output_config"), "output_config must not be emitted when reasoning=false");

    // 情况 2: model.reasoning = true, 但 variant.reasoningEffort = null
    ModelDescriptor reasoningModel = createReasoningModel("claude-sonnet-4-6");
    ModelVariant variantWithoutEffort =
        new ModelVariant("default", 2048, null, null, null, null, null, List.of(), null);

    ProviderRequest req2 =
        new ProviderRequest(
            reasoningModel,
            variantWithoutEffort,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hello")))),
            List.of(),
            ProviderCacheControl.none());

    JsonNode wire2 = MAPPER.readTree(encoder.encode(req2, descriptor).bodyUtf8Bytes());
    assertFalse(wire2.has("thinking"), "thinking must not be emitted when reasoningEffort is null");
    assertFalse(
        wire2.has("output_config"),
        "output_config must not be emitted when reasoningEffort is null");
  }

  /**
   * 验证通过结构化测试 fixture 文件 thinking-stream.json 回放流式思考事件序列。
   *
   * <p>测试意图：证明存放在资源目录中的标准 thinking 流数据能够被完整解析与累积，产生合规的终态响应与 ReplayState。
   */
  @Test
  void should_process_thinking_stream_from_fixture_resource() throws IOException {
    JsonNode fixtureArray;
    try (InputStream in = getClass().getResourceAsStream("fixtures/thinking-stream.json")) {
      assertNotNull(in, "fixtures/thinking-stream.json must exist in classpath");
      fixtureArray = MAPPER.readTree(in);
    }
    assertTrue(fixtureArray.isArray());

    ModelDescriptor modelDesc = createReasoningModel("claude-sonnet-4-5-20250929");
    ModelVariant variant =
        new ModelVariant("default", 2048, null, null, null, null, null, List.of(), "high");
    ProviderRequest req =
        new ProviderRequest(
            modelDesc,
            variant,
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("Ping")))),
            List.of(),
            ProviderCacheControl.none());

    String hash = encoder.encode(req, descriptor).sourcePrefixHash();

    RecordingStreamHandler handler = new RecordingStreamHandler();
    AnthropicStreamBridge bridge = new AnthropicStreamBridge(handler);
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(req, descriptor, hash, bridge);

    for (JsonNode item : fixtureArray) {
      String event = item.path("event").asText();
      String data = item.path("data").asText();
      accumulator.handleEvent(event, data);
    }

    ProviderCompletion completion = accumulator.finish();
    assertEquals("Thinking deeply about the answer.", completion.response().thinking());
    assertEquals("Here is the response.", completion.response().text());
    assertNotNull(completion.replayState());
    assertEquals(
        "sig_test_fixture_signature",
        completion.replayState().payload().path("content").get(0).path("signature").asText());
  }

  /**
   * 验证 BUDGET 模式下的完整生命周期： 1. 编码阶段：thinking.type=enabled、budget_tokens=8192，且完全省略 output_config； 2.
   * 流式阶段：正确派发 ThinkingDelta、TextDelta 并由 Accumulator 完成终态组装； 3. 重放阶段：在后续轮次中正确以原生 thinking 块重放。
   */
  @Test
  void should_support_budget_thinking_mode_wire_and_stream() throws IOException {
    AnthropicRequestEncoder budgetEncoder =
        new AnthropicRequestEncoder(new AnthropicConfiguration(AnthropicThinkingMode.BUDGET));

    ModelDescriptor modelDesc = createReasoningModel("MiniMax-M3");
    ModelVariant variant =
        new ModelVariant("default", 16384, null, null, null, null, null, List.of(), "medium");
    ProviderMessage userMsg =
        new ProviderMessage(
            ProviderMessageRole.USER,
            List.of(new ProviderTextBlock("Explain quantum superposition.")));
    ProviderRequest turn1Request =
        new ProviderRequest(
            modelDesc, variant, List.of(userMsg), List.of(), ProviderCacheControl.none());

    AnthropicEncodedRequest encodedTurn1 = budgetEncoder.encode(turn1Request, descriptor);
    JsonNode turn1WireRoot = MAPPER.readTree(encodedTurn1.bodyUtf8Bytes());

    assertEquals("MiniMax-M3", turn1WireRoot.path("model").asText());
    assertEquals("enabled", turn1WireRoot.path("thinking").path("type").asText());
    assertEquals(8192, turn1WireRoot.path("thinking").path("budget_tokens").asInt());
    assertFalse(turn1WireRoot.has("output_config"), "BUDGET mode must omit output_config");

    String frozenPrefixHash = encodedTurn1.sourcePrefixHash();
    assertNotNull(frozenPrefixHash);

    RecordingStreamHandler handler = new RecordingStreamHandler();
    AnthropicStreamBridge bridge = new AnthropicStreamBridge(handler);
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(turn1Request, descriptor, frozenPrefixHash, bridge);

    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_minimax_1\",\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"Thinking: \"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"Quantum states...\"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig_minimax_budget\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"text_delta\",\"text\":\"Superposition means...\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}");
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":20}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    bridge.emitComplete(completion);

    assertEquals(3, handler.events.size());
    assertInstanceOf(ProviderStreamEvent.ThinkingDelta.class, handler.events.get(0));
    assertInstanceOf(ProviderStreamEvent.ThinkingDelta.class, handler.events.get(1));
    assertInstanceOf(ProviderStreamEvent.TextDelta.class, handler.events.get(2));

    assertEquals("Thinking: Quantum states...", completion.response().thinking());
    assertEquals("Superposition means...", completion.response().text());
    assertNotNull(completion.replayState());

    // 轮次 2：重放轮次 1 的 assistant 生成并验证 wire 内容
    ProviderMessage assistantMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("Thinking: Quantum states..."),
                new ProviderTextBlock("Superposition means...")),
            completion.replayState());
    ProviderMessage userMsg2 =
        new ProviderMessage(
            ProviderMessageRole.USER, List.of(new ProviderTextBlock("Tell me more.")));
    ProviderRequest turn2Request =
        new ProviderRequest(
            modelDesc,
            variant,
            List.of(userMsg, assistantMsg, userMsg2),
            List.of(),
            ProviderCacheControl.none());

    AnthropicEncodedRequest encodedTurn2 = budgetEncoder.encode(turn2Request, descriptor);
    JsonNode turn2WireRoot = MAPPER.readTree(encodedTurn2.bodyUtf8Bytes());
    JsonNode wireAsstMsg = turn2WireRoot.path("messages").get(1);
    assertEquals("assistant", wireAsstMsg.path("role").asText());
    assertEquals("thinking", wireAsstMsg.path("content").get(0).path("type").asText());
    assertEquals(
        "sig_minimax_budget", wireAsstMsg.path("content").get(0).path("signature").asText());
    assertEquals("text", wireAsstMsg.path("content").get(1).path("type").asText());
  }

  private static ModelDescriptor createReasoningModel(String modelName) {
    return new ModelDescriptor(
        "anthropic-thinking",
        modelName,
        Set.of(ModelInputModality.TEXT),
        true,
        true, // reasoning enabled
        pricing());
  }

  private static ModelPricing pricing() {
    return new ModelPricing(
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
  }

  private static class RecordingStreamHandler implements ProviderStreamHandler {
    final List<ProviderStreamEvent> events = new CopyOnWriteArrayList<>();
    final AtomicReference<ProviderCompletion> completionRef = new AtomicReference<>();
    final AtomicReference<ProviderException> errorRef = new AtomicReference<>();
    final CountDownLatch latch = new CountDownLatch(1);

    @Override
    public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
      events.add(event);
    }

    @Override
    public void onComplete(ProviderCompletion completion, ProviderStream stream) {
      completionRef.set(completion);
      latch.countDown();
    }

    @Override
    public void onError(ProviderException error, ProviderStream stream) {
      errorRef.set(error);
      latch.countDown();
    }
  }
}
