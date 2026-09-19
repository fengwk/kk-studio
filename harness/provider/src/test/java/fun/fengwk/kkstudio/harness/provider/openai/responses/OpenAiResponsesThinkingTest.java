package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayState;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderThinkingBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCallBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolResultBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 验证 OpenAI Responses 思考过程、加密推理保留与带工具回放语义。 */
class OpenAiResponsesThinkingTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String VALID_PREFIX_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
  private static final ModelVariant DEFAULT_VARIANT = new ModelVariant("default");

  private ProviderDescriptor createDescriptor() {
    return new ProviderDescriptor(
        "openai_test",
        ProviderType.OPENAI_RESPONSES,
        "https://api.openai.com/v1",
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(60)),
        UUID.fromString("11111111-1111-1111-1111-111111111111"));
  }

  private ModelDescriptor createModel() {
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
    return new ModelDescriptor(
        "openai_test",
        "gpt-5.4-mini",
        "gpt-5.4-mini",
        Set.of(ModelInputModality.TEXT),
        true,
        true,
        pricing);
  }

  private ProviderRequest request(List<ProviderMessage> messages) {
    return new ProviderRequest(
        createModel(),
        DEFAULT_VARIANT,
        1024,
        "Test system instruction.",
        messages != null ? messages : List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  /** 对应 upstream should_return_reasoning_summary：验证模型推理摘要流式输出与累加。 */
  @Test
  void should_return_reasoning_summary() throws Exception {
    List<ProviderStreamEvent> events = new ArrayList<>();
    ProviderRequest req = request(List.of());
    OpenAiResponsesStreamAccumulator acc =
        new OpenAiResponsesStreamAccumulator(
            req, createDescriptor(), VALID_PREFIX_HASH, events::add);

    acc.processEvent(
        MAPPER.readTree("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_th\"}}"));
    acc.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\",\"summary\":[]}}"));
    acc.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.reasoning_summary_text.delta\",\"output_index\":0,\"summary_index\":0,\"delta\":\"Step 1: analyze\"}"));
    acc.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.reasoning_summary_text.done\",\"output_index\":0,\"summary_index\":0,\"text\":\"Step 1: analyze\"}"));
    acc.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"Step 1: analyze\"}]}}"));
    acc.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_th\",\"status\":\"completed\"}}"));

    ProviderResponse resp = acc.response();
    assertEquals("Step 1: analyze", resp.thinking());
    assertEquals(1, events.size());
    assertTrue(events.get(0) instanceof ProviderStreamEvent.ThinkingDelta);
    assertEquals("Step 1: analyze", ((ProviderStreamEvent.ThinkingDelta) events.get(0)).text());
  }

  /** 对应 upstream should_not_return_reasoning_summary_when_not_requested：未产生推理输出时 thinking 为空。 */
  @Test
  void should_not_return_reasoning_summary_when_not_requested() throws Exception {
    ProviderRequest req = request(List.of());
    OpenAiResponsesStreamAccumulator acc =
        new OpenAiResponsesStreamAccumulator(req, createDescriptor(), VALID_PREFIX_HASH, e -> {});

    acc.processEvent(
        MAPPER.readTree("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_no_th\"}}"));
    acc.processEvent(
        MAPPER.readTree("{\"type\":\"response.output_text.delta\",\"delta\":\"Just answer\"}"));
    acc.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_no_th\",\"status\":\"completed\"}}"));

    ProviderResponse resp = acc.response();
    assertEquals("", resp.thinking());
    assertEquals("Just answer", resp.text());
  }

  /**
   * 对应 upstream should_return_encrypted_reasoning_and_send_it_back__single_tool_call： 验证单工具调用场景下保留
   * encrypted_content 并能在下一轮原样发送回服务端。
   */
  @Test
  void should_return_encrypted_reasoning_and_send_it_back__single_tool_call() throws Exception {
    ProviderDescriptor desc = createDescriptor();

    // 1. Accumulator 接收带有 encrypted_content 的 reasoning 和 function_call
    ProviderRequest req1 =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("call tool")))));

    OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();
    OpenAiResponsesEncodedRequest encReq1 =
        encoder.encode(req1, desc, OpenAiResponsesConfig.defaultConfig());

    OpenAiResponsesStreamAccumulator acc =
        new OpenAiResponsesStreamAccumulator(req1, desc, encReq1.sourcePrefixHash(), e -> {});

    acc.processEvent(
        MAPPER.readTree("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_enc\"}}"));
    acc.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"id\":\"rs_enc\",\"type\":\"reasoning\",\"encrypted_content\":\"enc_blob_123\",\"summary\":[]}}"));
    acc.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":{\"id\":\"rs_enc\",\"type\":\"reasoning\",\"encrypted_content\":\"enc_blob_123\",\"summary\":[]}}"));

    acc.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.added\",\"output_index\":1,"
                + "\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"query\"}}"));
    acc.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.function_call_arguments.delta\",\"output_index\":1,\"call_id\":\"call_1\",\"delta\":\"{}\"}"));
    acc.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.function_call_arguments.done\",\"output_index\":1,\"call_id\":\"call_1\",\"arguments\":\"{}\"}"));
    acc.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.done\",\"output_index\":1,"
                + "\"item\":{\"id\":\"fc_1\",\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"query\",\"arguments\":\"{}\"}}"));

    acc.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_enc\",\"status\":\"completed\"}}"));

    ProviderResponse resp = acc.response();
    ProviderReplayState replayState = acc.replayState();
    assertNotNull(replayState);

    // 验证 replay payload 中包含保留了 encrypted_content 的 reasoning 项
    JsonNode replayOutput = replayState.payload().get("output");
    assertEquals(2, replayOutput.size());
    assertEquals("reasoning", replayOutput.get(0).path("type").asText());
    assertEquals("enc_blob_123", replayOutput.get(0).path("encrypted_content").asText());
    assertEquals("function_call", replayOutput.get(1).path("type").asText());

    // 2. 将 replayState 放入后续 Assistant 消息中回放给服务端
    ProviderMessage assistantMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(new ProviderToolCallBlock(new ProviderToolCall("call_1", "query", "{}"))),
            replayState);

    ProviderMessage toolResultMsg =
        new ProviderMessage(
            ProviderMessageRole.TOOL,
            List.of(
                new ProviderToolResultBlock(
                    "call_1", "query", List.of(new ProviderTextBlock("res")), false, null)));

    ProviderRequest req2 =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("call tool"))),
                assistantMsg,
                toolResultMsg));

    OpenAiResponsesEncodedRequest encReq2 =
        encoder.encode(req2, desc, OpenAiResponsesConfig.defaultConfig());
    JsonNode root2 = MAPPER.readTree(encReq2.bodyUtf8Bytes());

    JsonNode input2 = root2.get("input");
    assertEquals(4, input2.size());
    assertEquals("message", input2.get(0).path("type").asText());
    assertEquals("reasoning", input2.get(1).path("type").asText());
    assertEquals("enc_blob_123", input2.get(1).path("encrypted_content").asText());
    assertEquals("function_call", input2.get(2).path("type").asText());
    assertEquals("function_call_output", input2.get(3).path("type").asText());
  }

  /**
   * 对应 upstream should_return_encrypted_reasoning_and_send_it_back__two_parallel_tool_calls：
   * 验证并发多工具调用时完整的加密推理与工具项原样有序回放。
   */
  @Test
  void should_return_encrypted_reasoning_and_send_it_back__two_parallel_tool_calls()
      throws Exception {
    ProviderDescriptor desc = createDescriptor();
    OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();

    ProviderRequest req1 =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("parallel calls")))));
    OpenAiResponsesEncodedRequest encReq1 =
        encoder.encode(req1, desc, OpenAiResponsesConfig.defaultConfig());

    OpenAiResponsesStreamAccumulator acc =
        new OpenAiResponsesStreamAccumulator(req1, desc, encReq1.sourcePrefixHash(), e -> {});

    acc.processEvent(
        MAPPER.readTree("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_2tools\"}}"));
    acc.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":{\"id\":\"rs_2\",\"type\":\"reasoning\",\"encrypted_content\":\"blob_p\",\"summary\":[]}}"));
    acc.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.done\",\"output_index\":1,"
                + "\"item\":{\"id\":\"fc_p1\",\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"t1\",\"arguments\":\"{}\"}}"));
    acc.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.done\",\"output_index\":2,"
                + "\"item\":{\"id\":\"fc_p2\",\"type\":\"function_call\",\"call_id\":\"c2\",\"name\":\"t2\",\"arguments\":\"{}\"}}"));
    acc.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_2tools\",\"status\":\"completed\"}}"));

    acc.response();
    ProviderReplayState replayState = acc.replayState();
    assertNotNull(replayState);
    assertEquals(3, replayState.payload().get("output").size());

    ProviderMessage assistantMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderToolCallBlock(new ProviderToolCall("c1", "t1", "{}")),
                new ProviderToolCallBlock(new ProviderToolCall("c2", "t2", "{}"))),
            replayState);

    ProviderRequest req2 =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("parallel calls"))),
                assistantMsg));

    OpenAiResponsesEncodedRequest encReq2 =
        encoder.encode(req2, desc, OpenAiResponsesConfig.defaultConfig());
    JsonNode root = MAPPER.readTree(encReq2.bodyUtf8Bytes());
    JsonNode input = root.get("input");
    assertEquals(4, input.size());
    assertEquals("reasoning", input.get(1).path("type").asText());
    assertEquals("c1", input.get(2).path("call_id").asText());
    assertEquals("c2", input.get(3).path("call_id").asText());
  }

  /**
   * 精确非对称回归测试： output_item.done 包含 encrypted_content，而 terminal response.completed 包含相同的 reasoning
   * id 与 summary， 但省略了 encrypted_content；验证持久化 replay state 与下一轮请求中均完整保留并回传该 encrypted_content
   * blob。
   */
  @Test
  void preservesStreamedEncryptedReasoningAcrossTerminalAndReplay() throws Exception {
    ProviderDescriptor desc = createDescriptor();
    OpenAiResponsesRequestEncoder encoder = new OpenAiResponsesRequestEncoder();

    ProviderRequest req1 =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("asymmetric test")))));
    OpenAiResponsesEncodedRequest encReq1 =
        encoder.encode(req1, desc, OpenAiResponsesConfig.defaultConfig());

    OpenAiResponsesStreamAccumulator acc =
        new OpenAiResponsesStreamAccumulator(req1, desc, encReq1.sourcePrefixHash(), e -> {});

    // 1. 流式阶段：created -> reasoning added -> summary delta -> reasoning output_item.done (带
    // encrypted_content)
    acc.processEvent(
        MAPPER.readTree("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_asym\"}}"));
    acc.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"id\":\"rs_asym_1\",\"type\":\"reasoning\",\"encrypted_content\":\"enc_blob_asym_999\",\"summary\":[]}}"));
    acc.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.reasoning_summary_text.delta\",\"output_index\":0,\"summary_index\":0,\"delta\":\"Deliberating deeply\"}"));
    acc.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":{\"id\":\"rs_asym_1\",\"type\":\"reasoning\",\"encrypted_content\":\"enc_blob_asym_999\","
                + "\"summary\":[{\"type\":\"summary_text\",\"text\":\"Deliberating deeply\"}]}}"));

    // 2. 工具调用阶段：added -> arguments.done -> output_item.done
    acc.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.added\",\"output_index\":1,"
                + "\"item\":{\"id\":\"fc_asym\",\"type\":\"function_call\",\"call_id\":\"call_asym\",\"name\":\"do_search\"}}"));
    acc.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.function_call_arguments.done\",\"output_index\":1,\"call_id\":\"call_asym\",\"arguments\":\"{\\\"q\\\":\\\"test\\\"}\"}"));
    acc.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.done\",\"output_index\":1,"
                + "\"item\":{\"id\":\"fc_asym\",\"type\":\"function_call\",\"call_id\":\"call_asym\",\"name\":\"do_search\",\"arguments\":\"{\\\"q\\\":\\\"test\\\"}\"}}"));

    // 3. 终态 completed：显式提供 output 数组，包含同 id + summary 的 reasoning，但省略 encrypted_content！
    acc.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_asym\",\"status\":\"completed\","
                + "\"output\":["
                + "{\"type\":\"reasoning\",\"id\":\"rs_asym_1\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"Deliberating deeply\"}]},"
                + "{\"type\":\"function_call\",\"id\":\"fc_asym\",\"call_id\":\"call_asym\",\"name\":\"do_search\",\"arguments\":\"{\\\"q\\\":\\\"test\\\"}\"}"
                + "]}}"));

    ProviderResponse resp = acc.response();
    assertEquals("Deliberating deeply", resp.thinking());
    assertEquals(1, resp.toolCalls().size());
    assertEquals("call_asym", resp.toolCalls().get(0).id());

    // 4. 验证 durable replayState 必须包含从流式中保留的 encrypted_content
    ProviderReplayState replayState = acc.replayState();
    assertNotNull(replayState);
    JsonNode replayOutput = replayState.payload().get("output");
    assertEquals(2, replayOutput.size());
    assertEquals("reasoning", replayOutput.get(0).path("type").asText());
    assertEquals("enc_blob_asym_999", replayOutput.get(0).path("encrypted_content").asText());
    assertEquals(
        "Deliberating deeply", replayOutput.get(0).path("summary").get(0).path("text").asText());
    assertEquals("function_call", replayOutput.get(1).path("type").asText());
    assertEquals("call_asym", replayOutput.get(1).path("call_id").asText());

    // 5. 下一轮对话携带该 replayState 发起请求，验证 wire 编码中 encrypted_content 成功被回传
    ProviderMessage assistantMsg =
        new ProviderMessage(
            ProviderMessageRole.ASSISTANT,
            List.of(
                new ProviderThinkingBlock("Deliberating deeply"),
                new ProviderToolCallBlock(
                    new ProviderToolCall("call_asym", "do_search", "{\"q\":\"test\"}"))),
            replayState);

    ProviderMessage toolResultMsg =
        new ProviderMessage(
            ProviderMessageRole.TOOL,
            List.of(
                new ProviderToolResultBlock(
                    "call_asym",
                    "do_search",
                    List.of(new ProviderTextBlock("search result")),
                    false,
                    null)));

    ProviderRequest req2 =
        request(
            List.of(
                new ProviderMessage(
                    ProviderMessageRole.USER, List.of(new ProviderTextBlock("asymmetric test"))),
                assistantMsg,
                toolResultMsg));

    OpenAiResponsesEncodedRequest encReq2 =
        encoder.encode(req2, desc, OpenAiResponsesConfig.defaultConfig());
    JsonNode root2 = MAPPER.readTree(encReq2.bodyUtf8Bytes());

    JsonNode input2 = root2.get("input");
    assertEquals(4, input2.size());
    assertEquals("message", input2.get(0).path("type").asText());
    // input2[1] 必须是带有 encrypted_content 的 reasoning 回放项
    assertEquals("reasoning", input2.get(1).path("type").asText());
    assertEquals("enc_blob_asym_999", input2.get(1).path("encrypted_content").asText());
    assertEquals("Deliberating deeply", input2.get(1).path("summary").get(0).path("text").asText());
    // input2[2] 是 function_call，input2[3] 是 function_call_output
    assertEquals("function_call", input2.get(2).path("type").asText());
    assertEquals("call_asym", input2.get(2).path("call_id").asText());
    assertEquals("function_call_output", input2.get(3).path("type").asText());
  }
}
