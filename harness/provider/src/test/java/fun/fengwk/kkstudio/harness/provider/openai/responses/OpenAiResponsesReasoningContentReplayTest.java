package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import fun.fengwk.kkstudio.harness.runtime.history.EntryType;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryEntryPayloadJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.history.HistoryPayloadMapper;
import fun.fengwk.kkstudio.harness.runtime.history.MessagePayload;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderReplayStateJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.thread.ProviderMessageProjector;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/** 合成 reasoning.content 历史的生产、持久化、投影与严格回放回归。 */
class OpenAiResponsesReasoningContentReplayTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String THINKING = "0123456789".repeat(5);
  private static final String TEXT = "Synthetic answer.";
  private static final UUID GENERATION = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final ProviderDescriptor SOURCE = descriptor("deepseek_test", GENERATION);
  private static final String MODEL = "synthetic-source-model";

  /** known reasoning.content 的已知坏形状；consumer（INVALID_REQUEST）与 producer（INVALID_RESPONSE）两侧共用。 */
  private static final List<String> MALFORMED_CONTENT_SHAPES =
      List.of(
          "null",
          "{}",
          "\"text\"",
          "1",
          "[null]",
          "[1]",
          "[\"text\"]",
          "[{}]",
          "[{\"type\":null,\"text\":\"x\"}]",
          "[{\"type\":1,\"text\":\"x\"}]",
          "[{\"type\":\"unknown\",\"text\":\"x\"}]",
          "[{\"type\":\"reasoning_text\"}]",
          "[{\"type\":\"reasoning_text\",\"text\":null}]",
          "[{\"type\":\"reasoning_text\",\"text\":1}]");

  private static Stream<Arguments> malformedContentShapes() {
    return MALFORMED_CONTENT_SHAPES.stream().map(Arguments::of);
  }

  private static ProviderDescriptor descriptor(String name, UUID generation) {
    return new ProviderDescriptor(
        name,
        ProviderType.OPENAI_RESPONSES,
        "https://api.example.com/v1",
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(60)),
        generation);
  }

  private static ProviderRequest request(
      ProviderDescriptor descriptor, String model, List<ProviderMessage> messages) {
    return new ProviderRequest(
        new ModelDescriptor(
            descriptor.providerName(), model, model, Set.of(ModelInputModality.TEXT), true, true),
        new ModelVariant("default", "medium"),
        1024,
        "Synthetic system instruction.",
        messages,
        List.of(),
        ProviderCacheControl.none());
  }

  private static JsonNode fixture() throws Exception {
    try (InputStream input =
        OpenAiResponsesReasoningContentReplayTest.class.getResourceAsStream(
            "fixtures/reasoning-content-replay.json")) {
      assertNotNull(input);
      return MAPPER.readTree(input);
    }
  }

  private static JsonNode encode(
      ProviderDescriptor descriptor, String model, List<ProviderMessage> messages)
      throws Exception {
    return MAPPER
        .readTree(
            new OpenAiResponsesRequestEncoder()
                .encode(request(descriptor, model, messages), descriptor)
                .bodyUtf8Bytes())
        .get("input");
  }

  /** 完整实际链路：delta/terminal → durable 双 codec → projector → native 与三种 affinity 回退。 */
  @Test
  void streamedContentSurvivesDurableRoundTripAndAffinitySwitches() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(request(SOURCE, MODEL, List.of()), SOURCE, e -> {});
    JsonNode events = fixture();
    for (JsonNode event : events) {
      accumulator.handleEvent(event.path("type").asText(), event.toString());
    }
    assertEquals(50, accumulator.response().thinking().length());
    assertEquals(THINKING, accumulator.response().thinking());
    assertEquals(TEXT, accumulator.response().text());
    assertFalse(accumulator.response().thinking().contains("synthetic_opaque_content"));

    MessagePayload payload =
        new HistoryPayloadMapper().assistantPayload(accumulator.response(), List.of());
    HistoryEntryPayloadJsonCodec historyCodec = new HistoryEntryPayloadJsonCodec();
    MessagePayload durable =
        (MessagePayload) historyCodec.decode(EntryType.MESSAGE, historyCodec.encode(payload));
    assertEquals(payload, durable);
    ProviderReplayStateJsonCodec replayCodec = new ProviderReplayStateJsonCodec();
    ProviderReplayState replay = replayCodec.decode(replayCodec.encode(accumulator.replayState()));
    assertEquals(accumulator.replayState(), replay);
    List<ProviderMessage> projected =
        ProviderMessageProjector.byNames(Set.of())
            .projectSources(
                List.of(ProviderMessageProjector.ProjectedMessage.of(durable.message(), replay)));
    JsonNode originalOutput = events.get(1).path("response").path("output");
    assertEquals(originalOutput, encode(SOURCE, MODEL, projected));
    assertEquals(originalOutput, replay.payload().get("output"), "encoding must not mutate native");

    assertSemantic(encode(descriptor("openai_test", GENERATION), MODEL, projected));
    assertSemantic(encode(SOURCE, "synthetic-target-model", projected));
    assertSemantic(
        encode(
            descriptor("deepseek_test", UUID.fromString("22222222-2222-2222-2222-222222222222")),
            MODEL,
            projected));
  }

  /** 存量 durable consumer 不依赖重新流式生成，切换厂商/模型仍先正确比对再语义回退。 */
  @Test
  void existingContentReplayEncodesWithoutProducerRepair() throws Exception {
    ObjectNode payload = MAPPER.createObjectNode();
    payload.set("output", fixture().get(1).path("response").path("output"));
    ProviderReplayState stored =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES, SOURCE.affinity(MODEL), payload);
    ProviderReplayStateJsonCodec codec = new ProviderReplayStateJsonCodec();
    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderThinkingBlock(THINKING), new ProviderTextBlock(TEXT)),
            codec.decode(codec.encode(stored)));
    assertEquals(payload.get("output"), encode(SOURCE, MODEL, List.of(assistant)));
    assertSemantic(
        encode(
            descriptor("openai_test", GENERATION), "synthetic-target-model", List.of(assistant)));
  }

  private static void assertSemantic(JsonNode input) {
    assertEquals(2, input.size());
    assertEquals(THINKING, input.get(0).path("summary").get(0).path("text").asText());
    assertEquals(TEXT, input.get(1).path("content").get(0).path("text").asText());
    for (JsonNode item : input) {
      assertFalse(item.has("id"));
      assertFalse(item.has("encrypted_content"));
    }
  }

  /** content-only 无 delta、多个 item、摘要优先与空白回退，在两种终态路径都以 item 顺序聚合。 */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void terminalItemsReplaceDraftAndAggregateConsistently(boolean explicitOutput) throws Exception {
    ArrayNode output = MAPPER.createArrayNode();
    ObjectNode first = reasoning(output, "first content");
    first.putArray("summary").addObject().put("type", "summary_text").put("text", " \n\t");
    ObjectNode second = reasoning(output, "different private content");
    second.putArray("summary").addObject().put("type", "summary_text").put("text", " summary ");
    reasoning(output, "third content");
    // 无可读文本的 item 不追加空白、不重复计算草稿。
    ObjectNode fourth = output.addObject().put("type", "reasoning");
    fourth.putArray("summary").addObject().put("type", "summary_text").put("text", "   ");
    String expected = "first content summary third content";

    for (boolean withDelta : List.of(false, true)) {
      OpenAiResponsesStreamAccumulator accumulator =
          new OpenAiResponsesStreamAccumulator(request(SOURCE, MODEL, List.of()), SOURCE, e -> {});
      if (withDelta) {
        accumulator.processEvent(
            MAPPER
                .createObjectNode()
                .put("type", "response.reasoning_text.delta")
                .put("delta", "obsolete draft"));
      }
      if (!explicitOutput) {
        for (JsonNode item : output) {
          ObjectNode done = MAPPER.createObjectNode().put("type", "response.output_item.done");
          done.set("item", item);
          accumulator.processEvent(done);
        }
      }
      ObjectNode terminal = MAPPER.createObjectNode().put("type", "response.completed");
      ObjectNode response = terminal.putObject("response");
      if (explicitOutput) {
        response.set("output", output);
      }
      accumulator.processEvent(terminal);
      assertEquals(expected, accumulator.response().thinking());
      assertNotNull(accumulator.replayState(), "content is not an empty placeholder");
      ProviderMessage assistant =
          new ProviderMessage(
              ProviderMessageRole.ASSISTANT,
              List.of(new ProviderThinkingBlock(expected)),
              accumulator.replayState());
      assertEquals(output, encode(SOURCE, MODEL, List.of(assistant)));
    }
  }

  private static ObjectNode reasoning(ArrayNode output, String text) {
    ObjectNode item = output.addObject().put("type", "reasoning");
    item.putArray("content").addObject().put("type", "reasoning_text").put("text", text);
    return item;
  }

  /** 每种 known content 损坏都严格拒绝，即便有可用 summary 且 affinity 不匹配。 */
  @ParameterizedTest
  @MethodSource("malformedContentShapes")
  void malformedContentRejectedBeforeAffinityFallback(String contentJson) throws Exception {
    ObjectNode payload = MAPPER.createObjectNode();
    ObjectNode item = payload.putArray("output").addObject().put("type", "reasoning");
    item.putArray("summary").addObject().put("type", "summary_text").put("text", "valid");
    item.set("content", MAPPER.readTree(contentJson));
    assertRejected(payload, "valid");
  }

  /** 真正的 content plaintext 矛盾与有文本却无 durable thinking 均不允许绕过严格校验。 */
  @ParameterizedTest
  @ValueSource(strings = {"different", ""})
  void contradictoryContentRejectedBeforeAffinityFallback(String durableThinking) {
    ObjectNode payload = MAPPER.createObjectNode();
    reasoning(payload.putArray("output"), "native thought");
    assertRejected(payload, durableThinking);
  }

  /** 非空 content 即使只有空白也不是空占位符，不能借占位符修复放行不可比较的思考。 */
  @Test
  void blankContentDoesNotRelaxThinkingConsistency() {
    ObjectNode payload = MAPPER.createObjectNode();
    reasoning(payload.putArray("output"), "  ");
    assertRejected(payload, "durable thought");
  }

  /** producer 明确 terminal.output 路径：已知 content 坏形状必须在捕获阶段以 INVALID_RESPONSE 拒绝。 */
  @ParameterizedTest
  @MethodSource("malformedContentShapes")
  void malformedTerminalReasoningContentRejectedAtCapture(String contentJson) throws Exception {
    JsonNode item = secretReasoningItem(contentJson);
    OpenAiResponsesStreamAccumulator accumulator = newAccumulator();
    ProviderException error =
        assertThrows(
            ProviderException.class, () -> accumulator.processEvent(completedWithOutput(item)));
    assertCaptureRejection(error);
  }

  /** producer output_item.done 后无 output 的 finish 路径：拒绝发生在 done 捕获而非延迟到 finish。 */
  @ParameterizedTest
  @MethodSource("malformedContentShapes")
  void malformedDoneReasoningContentRejectedAtCapture(String contentJson) throws Exception {
    JsonNode item = secretReasoningItem(contentJson);
    OpenAiResponsesStreamAccumulator accumulator = newAccumulator();
    ObjectNode done = MAPPER.createObjectNode().put("type", "response.output_item.done");
    done.set("item", item);
    // 未调用 finish()：若把畸形 content 延迟到终态聚合才判错，这里不会抛异常而会失败。
    ProviderException error =
        assertThrows(ProviderException.class, () -> accumulator.processEvent(done));
    assertCaptureRejection(error);
  }

  private static OpenAiResponsesStreamAccumulator newAccumulator() {
    return new OpenAiResponsesStreamAccumulator(request(SOURCE, MODEL, List.of()), SOURCE, e -> {});
  }

  /** 畸形 reasoning item 内嵌可识别的思考/密文标记，用于断言失败消息不回显原文。 */
  private static ObjectNode secretReasoningItem(String contentJson) throws Exception {
    ObjectNode item =
        MAPPER
            .createObjectNode()
            .put("type", "reasoning")
            .put("id", "rs_secret")
            .put("encrypted_content", "SECRET_CIPHER");
    item.putArray("summary").addObject().put("type", "summary_text").put("text", "SECRET_THOUGHT");
    item.set("content", MAPPER.readTree(contentJson));
    return item;
  }

  private static ObjectNode completedWithOutput(JsonNode reasoningItem) {
    ObjectNode terminal = MAPPER.createObjectNode().put("type", "response.completed");
    ObjectNode response = terminal.putObject("response");
    response.put("id", "resp_capture").put("status", "completed");
    ArrayNode output = response.putArray("output");
    output.add(reasoningItem);
    ObjectNode message = MAPPER.createObjectNode().put("type", "message").put("role", "assistant");
    message.putArray("content").addObject().put("type", "output_text").put("text", TEXT);
    output.add(message);
    return terminal;
  }

  private static void assertCaptureRejection(ProviderException error) {
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, error.kind());
    assertFalse(error.getMessage().contains("SECRET"));
  }

  private static void assertRejected(ObjectNode payload, String thinking) {
    ProviderReplayState replay =
        new ProviderReplayState(
            ProviderReplayFormat.OPENAI_RESPONSES, SOURCE.affinity(MODEL), payload);
    ProviderMessage assistant =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            thinking.isEmpty()
                ? List.of(new ProviderTextBlock(""))
                : List.of(new ProviderThinkingBlock(thinking)),
            replay);
    for (ProviderDescriptor target : List.of(SOURCE, descriptor("openai_test", GENERATION))) {
      ProviderException error =
          assertThrows(
              ProviderException.class,
              () ->
                  new OpenAiResponsesRequestEncoder()
                      .encode(request(target, MODEL, List.of(assistant)), target));
      assertEquals(ProviderErrorKind.INVALID_REQUEST, error.kind());
      assertFalse(error.getMessage().contains(thinking.isEmpty() ? "native thought" : thinking));
    }
  }
}
