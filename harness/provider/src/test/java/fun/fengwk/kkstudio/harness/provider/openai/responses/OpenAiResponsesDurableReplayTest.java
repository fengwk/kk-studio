package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolDefinition;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;
import fun.fengwk.kkstudio.harness.runtime.model.provider.codec.ProviderReplayStateJsonCodec;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessage;
import fun.fengwk.kkstudio.harness.runtime.session.AgentMessageRole;
import fun.fengwk.kkstudio.harness.runtime.session.TextMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ThinkingMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolCallMessageContent;
import fun.fengwk.kkstudio.harness.runtime.session.ToolResultMessageContent;
import fun.fengwk.kkstudio.harness.runtime.thread.ProviderMessageProjector;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 验证 encrypted reasoning 的完整 durable 链路：Responses 流捕获 → provider replay state 持久化 codec → session
 * 投影 → 下一轮请求回放，且不外泄加密推理内容之外的私有推理文本。
 */
class OpenAiResponsesDurableReplayTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final ModelVariant DEFAULT_VARIANT = new ModelVariant("default", "medium");
  private static final ProviderToolDefinition TOOL =
      new ProviderToolDefinition(
          "query",
          "query tool",
          """
          {"type":"object","properties":{"q":{"type":"string"}},"required":["q"],
           "additionalProperties":false}
          """);

  private ProviderDescriptor createDescriptor() {
    return new ProviderDescriptor(
        "openai_test",
        ProviderType.OPENAI_RESPONSES,
        "https://api.openai.com/v1",
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(60)),
        UUID.fromString("11111111-1111-1111-1111-111111111111"));
  }

  private ProviderRequest request(List<ProviderMessage> messages) {
    ModelPricing pricing =
        new ModelPricing(
            "USD",
            "tier-1",
            "default",
            BigDecimal.ONE,
            "v1",
            BigDecimal.ONE,
            BigDecimal.ONE,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ONE);
    ModelDescriptor model =
        new ModelDescriptor(
            "openai_test",
            "gpt-5.4-mini",
            "gpt-5.4-mini",
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            pricing);
    return new ProviderRequest(
        model,
        DEFAULT_VARIANT,
        1024,
        "Test system instruction.",
        messages,
        List.of(TOOL),
        ProviderCacheControl.none());
  }

  /**
   * 意图：encrypted reasoning 必须从流式与终态 output 捕获、经 durable codec/投影后在下一轮以原 reasoning item 回放； 私有
   * reasoning 文本与不透明密文都不得出现在 durable 消息内容中。
   */
  @Test
  void encryptedReasoningSurvivesDurableRoundTripAndReplay() throws Exception {
    ProviderDescriptor descriptor = createDescriptor();
    OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();
    ProviderRequest firstRequest =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("call the tool")))));
    OpenAiResponsesEncodedRequest first =
        encoder.encode(firstRequest, descriptor, OpenAiResponsesConfig.defaultConfig());

    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            firstRequest, descriptor, first.sourcePrefixHash(), event -> {});
    accumulator.processEvent(
        MAPPER.readTree("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_durable\"}}"));
    // 私有推理文本草稿：只用于流式草稿，绝不允许进入 durable thinking 或 replay summary。
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.reasoning_text.delta\",\"output_index\":0,\"delta\":\"private draft\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.reasoning_summary_text.delta\",\"output_index\":0,\"summary_index\":0,\"delta\":\"Check the weather\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            """
            {"type":"response.completed","response":{"id":"resp_durable","status":"completed","output":[
              {"id":"rs_durable","type":"reasoning","encrypted_content":"enc_blob_durable",
               "summary":[{"type":"summary_text","text":"Check the weather"}]},
              {"id":"fc_durable","type":"function_call","call_id":"call_1","name":"query",
               "arguments":"{\\"q\\":\\"Paris\\"}"}
            ]}}
            """));

    ProviderResponse response = accumulator.response();
    assertEquals("Check the weather", response.thinking());
    assertFalse(
        response.thinking().contains("private draft"),
        "private reasoning text must not be exposed");
    assertFalse(
        response.thinking().contains("enc_blob_durable"),
        "encrypted blob must not be exposed as text");
    assertEquals(1, response.toolCalls().size());

    ProviderReplayState replayState = accumulator.replayState();
    assertNotNull(replayState);
    assertEquals(ProviderReplayFormat.OPENAI_RESPONSES, replayState.format());
    assertEquals(first.sourcePrefixHash(), replayState.sourcePrefixHash());
    JsonNode replayOutput = replayState.payload().get("output");
    assertEquals("enc_blob_durable", replayOutput.get(0).path("encrypted_content").asText());
    assertEquals(
        "Check the weather", replayOutput.get(0).path("summary").get(0).path("text").asText());

    // durable 持久化：replay state 经严格 codec 往返后保持全等（含加密内容）。
    ProviderReplayStateJsonCodec codec = new ProviderReplayStateJsonCodec();
    ProviderReplayState durableReplay = codec.decode(codec.encode(replayState));
    assertEquals(replayState, durableReplay);

    // session 投影：durable assistant 消息只携带 summary thinking 与 tool call，加密内容留在 replay state。
    AgentMessage durableAssistant =
        new AgentMessage(
            AgentMessageRole.ASSISTANT,
            List.of(
                new ThinkingMessageContent(response.thinking()),
                new ToolCallMessageContent("call_1", "query", "query", "{\"q\":\"Paris\"}")));
    AgentMessage durableToolResult =
        new AgentMessage(
            AgentMessageRole.TOOL,
            List.of(
                new ToolResultMessageContent(
                    "call_1",
                    "query",
                    "query",
                    List.of(new TextMessageContent("sunny")),
                    false,
                    "{}")));
    // 只有当前请求绑定的 native 工具名才保持 provider 原生结构，否则回放与 native tool 结果都会被降级
    List<ProviderMessage> projected =
        ProviderMessageProjector.byNames(Set.of("query"))
            .projectSources(
                List.of(
                    ProviderMessageProjector.ProjectedMessage.of(
                        new AgentMessage(
                            AgentMessageRole.USER,
                            List.of(new TextMessageContent("call the tool")))),
                    ProviderMessageProjector.ProjectedMessage.of(durableAssistant, durableReplay),
                    ProviderMessageProjector.ProjectedMessage.of(durableToolResult)));

    OpenAiResponsesEncodedRequest second =
        encoder.encode(request(projected), descriptor, OpenAiResponsesConfig.defaultConfig());
    JsonNode input = MAPPER.readTree(second.bodyUtf8Bytes()).get("input");

    // input 移位：user(0) → reasoning(1) → function_call(2) → function_call_output(3)
    assertEquals(4, input.size());
    assertEquals("user", input.get(0).path("role").asText());
    assertEquals("reasoning", input.get(1).path("type").asText());
    assertEquals("enc_blob_durable", input.get(1).path("encrypted_content").asText());
    assertEquals("Check the weather", input.get(1).path("summary").get(0).path("text").asText());
    assertEquals("function_call", input.get(2).path("type").asText());
    assertEquals("call_1", input.get(2).path("call_id").asText());
    assertEquals("function_call_output", input.get(3).path("type").asText());
    assertEquals("sunny", input.get(3).path("output").asText());
    assertFalse(
        MAPPER.readTree(second.bodyUtf8Bytes()).toString().contains("private draft"),
        "private reasoning text must never reach the wire replay");
  }

  /** 意图：历史缺少 replay state 时（例如旧历史或非 Responses provider）回退为语义编码，不合成 encrypted_content。 */
  @Test
  void durableHistoryWithoutReplayStateFallsBackToSemanticEncoding() throws Exception {
    ProviderDescriptor descriptor = createDescriptor();
    OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();

    // 只有当前请求绑定的 native 工具名才保持 provider 原生结构，否则回放与 native tool 结果都会被降级
    List<ProviderMessage> projected =
        ProviderMessageProjector.byNames(Set.of("query"))
            .projectSources(
                List.of(
                    ProviderMessageProjector.ProjectedMessage.of(
                        new AgentMessage(
                            AgentMessageRole.USER,
                            List.of(new TextMessageContent("call the tool")))),
                    ProviderMessageProjector.ProjectedMessage.of(
                        new AgentMessage(
                            AgentMessageRole.ASSISTANT,
                            List.of(
                                new ThinkingMessageContent("durable thought"),
                                new ToolCallMessageContent(
                                    "call_1", "query", "query", "{\"q\":\"Paris\"}")))),
                    ProviderMessageProjector.ProjectedMessage.of(
                        new AgentMessage(
                            AgentMessageRole.TOOL,
                            List.of(
                                new ToolResultMessageContent(
                                    "call_1",
                                    "query",
                                    "query",
                                    List.of(new TextMessageContent("sunny")),
                                    false,
                                    "{}"))))));

    JsonNode input =
        MAPPER
            .readTree(
                encoder
                    .encode(request(projected), descriptor, OpenAiResponsesConfig.defaultConfig())
                    .bodyUtf8Bytes())
            .get("input");

    assertEquals(4, input.size());
    assertEquals("user", input.get(0).path("role").asText());
    assertEquals("reasoning", input.get(1).path("type").asText());
    assertEquals("durable thought", input.get(1).path("summary").get(0).path("text").asText());
    assertFalse(input.get(1).has("encrypted_content"));
    assertEquals("function_call", input.get(2).path("type").asText());
    assertTrue(input.get(2).has("arguments"));
    assertEquals("function_call_output", input.get(3).path("type").asText());
  }
}
