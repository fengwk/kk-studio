package fun.fengwk.kkstudio.harness.provider.openai.responses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 验证 OpenAI Responses SSE 流式状态机的事件聚合、截断诊断、用量归一化与回放状态生成。 */
class OpenAiResponsesStreamAccumulatorTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ProviderDescriptor createDescriptor() {
    return new ProviderDescriptor(
        "openai_test",
        ProviderType.OPENAI_RESPONSES,
        "https://api.openai.com/v1",
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(60)),
        UUID.fromString("11111111-1111-1111-1111-111111111111"));
  }

  private ProviderRequest createRequest() {
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
            "openai_test", "gpt-5.4-mini", Set.of(ModelInputModality.TEXT), true, true, pricing);
    return new ProviderRequest(
        model,
        new ModelVariant("default", null, null, null, null, null, null, List.of(), null),
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  /** 验证正常文本流式生成完整生命周期。 */
  @Test
  void test_normalTextStreamAccumulation() throws Exception {
    List<ProviderStreamEvent> emittedEvents = new ArrayList<>();
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            emittedEvents::add);

    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_123\",\"service_tier\":\"default\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"type\":\"message\",\"role\":\"assistant\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.content_part.added\",\"output_index\":0,\"content_index\":0,\"part\":{\"type\":\"output_text\",\"text\":\"\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"Hello\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\" World\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_text.done\",\"output_index\":0,\"content_index\":0,\"text\":\"Hello World\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello World\"}]}}"));

    String completedEvent =
        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_123\",\"status\":\"completed\","
            + "\"usage\":{\"input_tokens\":100,\"output_tokens\":50,\"total_tokens\":150,"
            + "\"input_tokens_details\":{\"cached_tokens\":30,\"cache_write_tokens\":10},"
            + "\"output_tokens_details\":{\"reasoning_tokens\":20}}}}";
    accumulator.processEvent(MAPPER.readTree(completedEvent));

    ProviderResponse resp = accumulator.response();
    assertEquals("Hello World", resp.text());
    assertEquals(GenerationStopReason.COMPLETE, resp.stopReason());
    assertEquals("resp_123", resp.requestId());
    assertEquals("default", resp.serviceTier());

    // 验证用量归一化：60 普通输入, 30 普通输出, 30 cached, 10 cacheWrite, 20 reasoning, 150 total
    ModelUsage usage = resp.usage();
    assertEquals(60L, usage.inputTokens());
    assertEquals(30L, usage.outputTokens());
    assertEquals(30L, usage.cacheReadTokens());
    assertEquals(10L, usage.cacheWriteTokens());
    assertEquals(20L, usage.reasoningTokens());
    assertEquals(150L, usage.providerTotalTokens());

    // 验证 rawUsageJson 白名单
    JsonNode rawUsage = MAPPER.readTree(resp.rawUsageJson());
    assertEquals(100L, rawUsage.path("input_tokens").asLong());
    assertEquals(50L, rawUsage.path("output_tokens").asLong());
    assertEquals(150L, rawUsage.path("total_tokens").asLong());

    // 验证 replayState 生成
    assertNotNull(accumulator.replayState());
    assertEquals(
        "Hello World",
        accumulator
            .replayState()
            .payload()
            .get("output")
            .get(0)
            .get("content")
            .get(0)
            .get("text")
            .asText());

    // 验证事件分发
    assertEquals(2, emittedEvents.size());
    assertTrue(emittedEvents.get(0) instanceof ProviderStreamEvent.TextDelta);
    assertEquals("Hello", ((ProviderStreamEvent.TextDelta) emittedEvents.get(0)).text());
    assertEquals(" World", ((ProviderStreamEvent.TextDelta) emittedEvents.get(1)).text());
  }

  /** 验证如果未收到终态事件而过早结束流，finish 明确抛出 INVALID_RESPONSE。 */
  @Test
  void test_prematureEofThrowsInvalidResponse() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});

    accumulator.processEvent(
        MAPPER.readTree("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_123\"}}"));
    accumulator.processEvent(
        MAPPER.readTree("{\"type\":\"response.output_text.delta\",\"delta\":\"Hi\"}"));

    ProviderException ex = assertThrows(ProviderException.class, accumulator::finish);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
    assertTrue(ex.getMessage().contains("premature EOF"));
  }

  /** 验证 incomplete_details 包含 max_output_tokens 时映射为 LENGTH，未闭合工具生成诊断且禁止回放。 */
  @Test
  void test_incompleteLengthWithTruncatedToolCall() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});

    accumulator.processEvent(
        MAPPER.readTree("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"id\":\"item_1\",\"type\":\"function_call\",\"call_id\":\"call_abc\",\"name\":\"search\"}}"));
    // 未收到 arguments 结束，直接返回 incomplete (max_output_tokens)
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\","
                + "\"incomplete_details\":{\"reason\":\"max_output_tokens\"},"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":20,\"total_tokens\":30}}}"));

    ProviderResponse resp = accumulator.response();
    assertEquals(GenerationStopReason.LENGTH, resp.stopReason());
    assertEquals(1, resp.toolCallDiagnostics().size());
    assertEquals("call_abc", resp.toolCallDiagnostics().get(0).id());
    assertNull(accumulator.replayState());
  }

  /** 验证 incomplete_details 包含 content_filter 时映射为 FILTERED，撤回工具且禁止回放。 */
  @Test
  void test_incompleteContentFilterYieldsFilteredStopReason() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});

    accumulator.processEvent(
        MAPPER.readTree("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\","
                + "\"incomplete_details\":{\"reason\":\"content_filter\"},"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":20,\"total_tokens\":30}}}"));

    ProviderResponse resp = accumulator.response();
    assertEquals(GenerationStopReason.FILTERED, resp.stopReason());
    assertTrue(resp.toolCalls().isEmpty());
    assertTrue(resp.toolCallDiagnostics().isEmpty());
    assertNull(accumulator.replayState());
  }

  /** 验证流中收到 response.failed 或 response.error 事件时立即抛出 ProviderException。 */
  @Test
  void test_streamErrorPayloadThrowsImmediately() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});

    JsonNode failedEvent =
        MAPPER.readTree(
            "{\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"server_error\",\"message\":\"fail\"}}}");

    ProviderException ex =
        assertThrows(ProviderException.class, () -> accumulator.processEvent(failedEvent));
    assertEquals(ProviderErrorKind.TRANSIENT, ex.kind());
  }

  /** 验证 SSE ping、[DONE]、空数据以及未知事件被忽略，非 JSON 数据抛出 INVALID_RESPONSE。 */
  @Test
  void test_sseEdgeEventsAndInvalidJson() {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});

    // ping / [DONE] / blank / null
    accumulator.handleEvent("ping", "");
    accumulator.handleEvent(null, "");
    accumulator.handleEvent(null, "   ");
    accumulator.handleEvent(null, "[DONE]");
    accumulator.handleEvent(null, "  [DONE]  ");

    // unknown json event
    accumulator.handleEvent(null, "{\"type\":\"custom.unknown.event\"}");

    // invalid json throws INVALID_RESPONSE
    assertThrows(
        ProviderException.class, () -> accumulator.handleEvent(null, "not a valid json object"));
  }

  /** 验证当 native usage 缺失 total_tokens 时，providerTotalTokens 置 0 且 rawUsageJson 省略该字段，无虚假合成。 */
  @Test
  void test_missingTotalTokensPreservesZeroAndOmitsFromRawUsageJson() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});

    accumulator.processEvent(
        MAPPER.readTree("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_no_tot\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Done\"}]}}"));

    // usage 块仅有 input_tokens 与 output_tokens，无 total_tokens
    String completedEvent =
        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_no_tot\",\"status\":\"completed\","
            + "\"usage\":{\"input_tokens\":40,\"output_tokens\":20,"
            + "\"input_tokens_details\":{\"cached_tokens\":10,\"cache_write_tokens\":5},"
            + "\"output_tokens_details\":{\"reasoning_tokens\":5}}}}";
    accumulator.processEvent(MAPPER.readTree(completedEvent));

    ProviderResponse resp = accumulator.response();
    assertEquals("Done", resp.text());
    assertEquals(GenerationStopReason.COMPLETE, resp.stopReason());

    ModelUsage usage = resp.usage();
    assertEquals(25L, usage.inputTokens());
    assertEquals(15L, usage.outputTokens());
    assertEquals(10L, usage.cacheReadTokens());
    assertEquals(5L, usage.cacheWriteTokens());
    assertEquals(5L, usage.reasoningTokens());
    // 关键：缺失 total_tokens 时严格为 0，不合成 60
    assertEquals(0L, usage.providerTotalTokens());
    assertEquals(0L, usage.totalTokens());
    assertEquals(60L, usage.categorizedTokens());

    // 验证 rawUsageJson 白名单不包含 total_tokens
    JsonNode rawUsage = MAPPER.readTree(resp.rawUsageJson());
    assertEquals(40L, rawUsage.path("input_tokens").asLong());
    assertEquals(20L, rawUsage.path("output_tokens").asLong());
    assertFalse(
        rawUsage.has("total_tokens"),
        "rawUsageJson must omit total_tokens when absent from native usage");
  }
}
