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
            "openai_test",
            "gpt-5.4-mini",
            "gpt-5.4-mini",
            Set.of(ModelInputModality.TEXT),
            true,
            true,
            pricing);
    return new ProviderRequest(
        model,
        new ModelVariant("default"),
        1024,
        List.of(),
        List.of(),
        ProviderCacheControl.none());
  }

  /** 意图：验证正常文本流式生成完整生命周期。 */
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

    // 验证 rawUsageJson 保真
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

  /** 意图：验证如果未收到终态事件而过早结束流，finish 明确抛出 INVALID_RESPONSE。 */
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

  /** 意图：验证 incomplete_details 包含 max_output_tokens 时映射为 LENGTH，未闭合工具生成诊断且禁止回放。 */
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
    assertTrue(
        resp.toolCalls().isEmpty(), "truncated tool call must not be synthesized into toolCalls");
    assertNull(accumulator.replayState());
  }

  /** 意图：验证 incomplete_details 包含 content_filter 时映射为 FILTERED，撤回工具且禁止回放。 */
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

  /** 意图：验证流中收到 response.failed 或 response.error 事件时立即抛出 ProviderException。 */
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

  /** 意图：验证 SSE ping、[DONE]、空数据以及未知事件被忽略，非 JSON 数据抛出 INVALID_RESPONSE。 */
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

  /** 意图：验证当 native usage 缺失 total_tokens 时，providerTotalTokens 置 0 且 rawUsageJson 省略该字段，无虚假合成。 */
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

    // 验证 rawUsageJson 保真不包含 total_tokens
    JsonNode rawUsage = MAPPER.readTree(resp.rawUsageJson());
    assertEquals(40L, rawUsage.path("input_tokens").asLong());
    assertEquals(20L, rawUsage.path("output_tokens").asLong());
    assertFalse(
        rawUsage.has("total_tokens"),
        "rawUsageJson must omit total_tokens when absent from native usage");
  }

  /** 意图：验证非 JSON Object 的 usage 字段（如数组、字符串、数字）直接抛出 INVALID_RESPONSE。 */
  @Test
  void test_rejectsNonObjectUsage() throws Exception {
    List<String> invalidUsages = List.of("[1, 2, 3]", "\"not_an_object\"", "12345");
    for (String invalidUsage : invalidUsages) {
      OpenAiResponsesStreamAccumulator accumulator =
          new OpenAiResponsesStreamAccumulator(
              createRequest(),
              createDescriptor(),
              "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              e -> {});
      String event =
          "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"usage\":"
              + invalidUsage
              + "}}";
      ProviderException ex =
          assertThrows(
              ProviderException.class, () -> accumulator.processEvent(MAPPER.readTree(event)));
      assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
    }
  }

  /** 意图：验证非 JSON Object 的 usage details 字段直接抛出 INVALID_RESPONSE。 */
  @Test
  void test_rejectsNonObjectUsageDetails() throws Exception {
    OpenAiResponsesStreamAccumulator acc1 =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});
    String badInDetails =
        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":10,\"input_tokens_details\":[1,2]}}}";
    ProviderException ex1 =
        assertThrows(
            ProviderException.class, () -> acc1.processEvent(MAPPER.readTree(badInDetails)));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex1.kind());

    OpenAiResponsesStreamAccumulator acc2 =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});
    String badOutDetails =
        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"usage\":{\"input_tokens\":10,\"output_tokens\":10,\"output_tokens_details\":\"bad\"}}}";
    ProviderException ex2 =
        assertThrows(
            ProviderException.class, () -> acc2.processEvent(MAPPER.readTree(badOutDetails)));
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex2.kind());
  }

  /** 意图：验证 usage 中负数 token 计数严格抛出 INVALID_RESPONSE。 */
  @Test
  void test_rejectsNegativeUsageTokens() {
    List<String> badUsages =
        List.of(
            "{\"input_tokens\": -1, \"output_tokens\": 10}",
            "{\"input_tokens\": 10, \"output_tokens\": -5}",
            "{\"input_tokens\": 10, \"output_tokens\": 10, \"total_tokens\": -1}",
            "{\"input_tokens\": 10, \"output_tokens\": 10,\"input_tokens_details\": {\"cached_tokens\": -2}}");

    for (String badUsage : badUsages) {
      OpenAiResponsesStreamAccumulator accumulator =
          new OpenAiResponsesStreamAccumulator(
              createRequest(),
              createDescriptor(),
              "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              e -> {});
      String event =
          "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"usage\":"
              + badUsage
              + "}}";
      ProviderException ex =
          assertThrows(
              ProviderException.class, () -> accumulator.processEvent(MAPPER.readTree(event)));
      assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
    }
  }

  /** 意图：验证 usage 中浮点数或字符串类型的 token 计数严格抛出 INVALID_RESPONSE。 */
  @Test
  void test_rejectsNonIntegralUsageTokens() {
    List<String> nonIntegralUsages =
        List.of(
            "{\"input_tokens\": 10.5, \"output_tokens\": 10}",
            "{\"input_tokens\": \"100\", \"output_tokens\": 10}",
            "{\"input_tokens\": 10, \"output_tokens\": 10, \"total_tokens\": 20.0}",
            "{\"input_tokens\": 10, \"output_tokens\": 10,\"input_tokens_details\": {\"cached_tokens\": \"20\"}}");

    for (String badUsage : nonIntegralUsages) {
      OpenAiResponsesStreamAccumulator accumulator =
          new OpenAiResponsesStreamAccumulator(
              createRequest(),
              createDescriptor(),
              "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              e -> {});
      String event =
          "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"usage\":"
              + badUsage
              + "}}";
      ProviderException ex =
          assertThrows(
              ProviderException.class, () -> accumulator.processEvent(MAPPER.readTree(event)));
      assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
    }
  }

  /** 意图：验证 usage 中超出 long 范围的大整数严格抛出 INVALID_RESPONSE。 */
  @Test
  void test_rejectsOutOfRangeUsageTokens() {
    List<String> outOfRangeUsages =
        List.of(
            "{\"input_tokens\": 999999999999999999999999999999999999999999, \"output_tokens\": 10}",
            "{\"input_tokens\": 10, \"output_tokens\": 999999999999999999999999999999999999999999}",
            "{\"input_tokens\": 10, \"output_tokens\": 10, \"total_tokens\": 999999999999999999999999999999999999999999}",
            "{\"input_tokens\": 10, \"output_tokens\": 10, \"input_tokens_details\": {\"cached_tokens\": 999999999999999999999999999999999999999999}}");

    for (String badUsage : outOfRangeUsages) {
      OpenAiResponsesStreamAccumulator accumulator =
          new OpenAiResponsesStreamAccumulator(
              createRequest(),
              createDescriptor(),
              "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              e -> {});
      String event =
          "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\",\"usage\":"
              + badUsage
              + "}}";
      ProviderException ex =
          assertThrows(
              ProviderException.class, () -> accumulator.processEvent(MAPPER.readTree(event)));
      assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
    }
  }

  /** 意图：验证后续 usage snapshot 完整覆盖前一 snapshot，缺失字段重置为 0 而非继承旧值，rawUsageJson 精确保留最新快照。 */
  @Test
  void test_usageSnapshotOverwritesPreviousSnapshotCompletely() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});

    // 快照 1：包含所有字段
    String snapshot1 =
        "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_snap\",\"service_tier\":\"default\","
            + "\"usage\":{\"input_tokens\":100,\"output_tokens\":50,\"total_tokens\":150,"
            + "\"input_tokens_details\":{\"cached_tokens\":30,\"cache_write_tokens\":10},"
            + "\"output_tokens_details\":{\"reasoning_tokens\":20},\"snapshot_version\":1}}}";
    accumulator.processEvent(MAPPER.readTree(snapshot1));

    // 快照 2：total_tokens、details 全部省略
    String snapshot2 =
        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_snap\",\"status\":\"completed\","
            + "\"usage\":{\"input_tokens\":80,\"output_tokens\":40,\"snapshot_version\":2}}}";
    accumulator.processEvent(MAPPER.readTree(snapshot2));

    ProviderResponse resp = accumulator.response();
    ModelUsage usage = resp.usage();
    assertEquals(80L, usage.inputTokens());
    assertEquals(40L, usage.outputTokens());
    assertEquals(0L, usage.providerTotalTokens(), "total_tokens must be reset to 0L");
    assertEquals(0L, usage.cacheReadTokens(), "cached_tokens must be reset to 0L");
    assertEquals(0L, usage.cacheWriteTokens(), "cache_write_tokens must be reset to 0L");
    assertEquals(0L, usage.reasoningTokens(), "reasoning_tokens must be reset to 0L");

    // rawUsageJson 保留快照 2
    JsonNode rawUsage = MAPPER.readTree(resp.rawUsageJson());
    assertEquals(2, rawUsage.path("snapshot_version").asInt());
    assertFalse(rawUsage.has("total_tokens"));
    assertFalse(rawUsage.has("input_tokens_details"));
    assertFalse(rawUsage.has("output_tokens_details"));
  }

  /** 意图：验证 native usage 中的厂商自定义扩展元数据在 rawUsageJson 中完整保真。 */
  @Test
  void test_preservesCustomVendorMetadataInRawUsageJson() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});

    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hi\"}]}}"));

    String completedEvent =
        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_vendor\",\"status\":\"completed\","
            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"total_tokens\":15,"
            + "\"vendor_extra_id\":\"ext-999\",\"upstream_latency_ms\":123,"
            + "\"extra_details\":{\"region\":\"us-east-1\"}}}}";
    accumulator.processEvent(MAPPER.readTree(completedEvent));

    ProviderResponse resp = accumulator.response();
    JsonNode rawUsage = MAPPER.readTree(resp.rawUsageJson());
    assertEquals("ext-999", rawUsage.path("vendor_extra_id").asText());
    assertEquals(123, rawUsage.path("upstream_latency_ms").asInt());
    assertEquals("us-east-1", rawUsage.path("extra_details").path("region").asText());
  }

  /** 意图：验证当响应中完全未携带 usage 块时，rawUsageJson 产出 {}。 */
  @Test
  void test_emitsEmptyObjectRawUsageWhenNoUsageProvided() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});

    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hi\"}]}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_no_usage\",\"status\":\"completed\"}}"));

    ProviderResponse resp = accumulator.response();
    assertEquals("{}", resp.rawUsageJson());
    assertEquals(0L, resp.usage().providerTotalTokens());
  }

  /** 意图：验证 COMPLETE 终态下工具调用如果参数为空或未闭合，严禁合成 {}，严格抛出 INVALID_RESPONSE。 */
  @Test
  void test_completeRejectsIncompleteToolCallMissingArguments() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});

    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"id\":\"item_1\",\"type\":\"function_call\",\"call_id\":\"call_abc\",\"name\":\"search\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":{\"id\":\"item_1\",\"type\":\"function_call\",\"call_id\":\"call_abc\",\"name\":\"search\"}}"));

    String completedEvent =
        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\","
            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":20,\"total_tokens\":30}}}";
    accumulator.processEvent(MAPPER.readTree(completedEvent));

    ProviderException ex = assertThrows(ProviderException.class, accumulator::finish);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
  }

  /** 意图：验证 COMPLETE 终态下工具调用参数如果格式畸形或非 JSON Object，严格抛出 INVALID_RESPONSE。 */
  @Test
  void test_completeRejectsMalformedToolCallArguments() throws Exception {
    List<String> badArgs =
        List.of("not_json", "[1, 2, 3]", "12345", "\"scalar_str\"", "{\"unclosed\":");
    for (String badArg : badArgs) {
      OpenAiResponsesStreamAccumulator accumulator =
          new OpenAiResponsesStreamAccumulator(
              createRequest(),
              createDescriptor(),
              "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              e -> {});

      accumulator.processEvent(
          MAPPER.readTree(
              "{\"type\":\"response.output_item.added\",\"output_index\":0,"
                  + "\"item\":{\"id\":\"item_1\",\"type\":\"function_call\",\"call_id\":\"call_abc\",\"name\":\"search\"}}"));
      accumulator.processEvent(
          MAPPER.readTree(
              "{\"type\":\"response.function_call_arguments.done\",\"item_id\":\"item_1\",\"arguments\":"
                  + MAPPER.writeValueAsString(badArg)
                  + "}"));
      accumulator.processEvent(
          MAPPER.readTree(
              "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\","
                  + "\"usage\":{\"input_tokens\":10,\"output_tokens\":20,\"total_tokens\":30}}}"));

      ProviderException ex = assertThrows(ProviderException.class, accumulator::finish);
      assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
    }
  }

  /** 意图：验证 LENGTH 终态下参数畸形的工具调用生成诊断，不进入 toolCalls 也不进入 replay。 */
  @Test
  void test_lengthDiagnosesMalformedToolCallArgumentsAndDisablesReplay() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});

    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"id\":\"item_1\",\"type\":\"function_call\",\"call_id\":\"call_abc\",\"name\":\"search\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"item_1\",\"delta\":\"{\\\"query\\\": \\\"par\"}"));

    // 发生截断
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\","
                + "\"incomplete_details\":{\"reason\":\"max_output_tokens\"},"
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":20,\"total_tokens\":30}}}"));

    ProviderResponse resp = accumulator.response();
    assertEquals(GenerationStopReason.LENGTH, resp.stopReason());
    assertTrue(
        resp.toolCalls().isEmpty(), "malformed tool call must not be synthesized into toolCalls");
    assertEquals(1, resp.toolCallDiagnostics().size());
    assertEquals("call_abc", resp.toolCallDiagnostics().get(0).id());
    assertEquals("{\"query\": \"par", resp.toolCallDiagnostics().get(0).partialArguments());
    assertNull(
        accumulator.replayState(),
        "replay state must be disabled when tool arguments are truncated");
  }

  /** 意图：验证当模型显式产出合法的空对象 arguments "{}" 时，正常保留在 toolCalls 与 replayState 中。 */
  @Test
  void test_retainsValidExplicitEmptyObjectToolCallArguments() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});

    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"id\":\"item_1\",\"type\":\"function_call\",\"call_id\":\"call_abc\",\"name\":\"get_current_time\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.function_call_arguments.done\",\"item_id\":\"item_1\",\"arguments\":\"{}\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":{\"id\":\"item_1\",\"type\":\"function_call\",\"call_id\":\"call_abc\",\"name\":\"get_current_time\",\"arguments\":\"{}\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"status\":\"completed\","
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":20,\"total_tokens\":30}}}"));

    ProviderResponse resp = accumulator.response();
    assertEquals(GenerationStopReason.COMPLETE, resp.stopReason());
    assertEquals(1, resp.toolCalls().size());
    assertEquals("{}", resp.toolCalls().get(0).argumentsJson());
    assertNotNull(accumulator.replayState());
    assertEquals(
        "{}", accumulator.replayState().payload().get("output").get(0).get("arguments").asText());
  }

  /** 意图：验证 COMPLETE 终态下仅在 terminal output 出现的合法空对象 "{}" 产出 tool call 与 replay。 */
  @Test
  void test_completeTerminalOnlyValidEmptyObjectArguments() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});

    String completedEvent =
        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_term_valid\",\"status\":\"completed\","
            + "\"output\":[{\"type\":\"function_call\",\"call_id\":\"call_term_1\",\"name\":\"get_time\",\"arguments\":\"{}\"}]}}";
    accumulator.processEvent(MAPPER.readTree(completedEvent));

    ProviderResponse resp = accumulator.response();
    assertEquals(GenerationStopReason.COMPLETE, resp.stopReason());
    assertEquals(1, resp.toolCalls().size());
    assertEquals("call_term_1", resp.toolCalls().get(0).id());
    assertEquals("get_time", resp.toolCalls().get(0).name());
    assertEquals("{}", resp.toolCalls().get(0).argumentsJson());

    assertNotNull(accumulator.replayState());
    JsonNode replayOutput = accumulator.replayState().payload().get("output").get(0);
    assertEquals("function_call", replayOutput.path("type").asText());
    assertEquals("call_term_1", replayOutput.path("call_id").asText());
    assertEquals("get_time", replayOutput.path("name").asText());
    assertEquals("{}", replayOutput.path("arguments").asText());
  }

  /** 意图：验证 COMPLETE 终态下仅在 terminal output 出现的 tool call 若缺失 arguments 则抛出 INVALID_RESPONSE。 */
  @Test
  void test_completeTerminalOnlyRejectsMissingArguments() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});

    String completedEvent =
        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_term_no_arg\",\"status\":\"completed\","
            + "\"output\":[{\"type\":\"function_call\",\"call_id\":\"call_term_1\",\"name\":\"get_time\"}]}}";
    accumulator.processEvent(MAPPER.readTree(completedEvent));

    ProviderException ex = assertThrows(ProviderException.class, accumulator::finish);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
  }

  /** 意图：验证 COMPLETE 终态下仅在 terminal output 出现的 tool call 若 arguments 为非字符串类型则抛出 INVALID_RESPONSE。 */
  @Test
  void test_completeTerminalOnlyRejectsNonTextArguments() throws Exception {
    List<String> nonTextArgs = List.of("{}", "[1, 2]", "123");
    for (String arg : nonTextArgs) {
      OpenAiResponsesStreamAccumulator accumulator =
          new OpenAiResponsesStreamAccumulator(
              createRequest(),
              createDescriptor(),
              "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              e -> {});

      String completedEvent =
          "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_term_non_text\",\"status\":\"completed\","
              + "\"output\":[{\"type\":\"function_call\",\"call_id\":\"call_term_1\",\"name\":\"get_time\",\"arguments\":"
              + arg
              + "}]}}";
      accumulator.processEvent(MAPPER.readTree(completedEvent));

      ProviderException ex = assertThrows(ProviderException.class, accumulator::finish);
      assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
    }
  }

  /**
   * 意图：验证 COMPLETE 终态下仅在 terminal output 出现的 tool call 若 arguments 为非 Object JSON 抛出
   * INVALID_RESPONSE。
   */
  @Test
  void test_completeTerminalOnlyRejectsNonObjectArguments() throws Exception {
    List<String> nonObjectArgs = List.of("\"[1, 2, 3]\"", "\"12345\"", "\"scalar_string\"");
    for (String arg : nonObjectArgs) {
      OpenAiResponsesStreamAccumulator accumulator =
          new OpenAiResponsesStreamAccumulator(
              createRequest(),
              createDescriptor(),
              "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              e -> {});

      String completedEvent =
          "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_term_non_obj\",\"status\":\"completed\","
              + "\"output\":[{\"type\":\"function_call\",\"call_id\":\"call_term_1\",\"name\":\"get_time\",\"arguments\":"
              + arg
              + "}]}}";
      accumulator.processEvent(MAPPER.readTree(completedEvent));

      ProviderException ex = assertThrows(ProviderException.class, accumulator::finish);
      assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
    }
  }

  /** 意图：验证 COMPLETE 终态下仅在 terminal output 出现的 tool call 若 arguments 格式畸形抛出 INVALID_RESPONSE。 */
  @Test
  void test_completeTerminalOnlyRejectsMalformedArguments() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});

    String completedEvent =
        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_term_bad_json\",\"status\":\"completed\","
            + "\"output\":[{\"type\":\"function_call\",\"call_id\":\"call_term_1\",\"name\":\"get_time\",\"arguments\":\"{\\\"unclosed\\\":\"}]}}";
    accumulator.processEvent(MAPPER.readTree(completedEvent));

    ProviderException ex = assertThrows(ProviderException.class, accumulator::finish);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
  }

  /** 意图：验证 COMPLETE 终态下仅在 terminal output 出现的 tool call 若缺失 id 抛出 INVALID_RESPONSE。 */
  @Test
  void test_completeTerminalOnlyRejectsMissingId() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});

    String completedEvent =
        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_term_no_id\",\"status\":\"completed\","
            + "\"output\":[{\"type\":\"function_call\",\"name\":\"get_time\",\"arguments\":\"{}\"}]}}";
    accumulator.processEvent(MAPPER.readTree(completedEvent));

    ProviderException ex = assertThrows(ProviderException.class, accumulator::finish);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
  }

  /** 意图：验证 COMPLETE 终态下仅在 terminal output 出现的 tool call 若缺失 name 抛出 INVALID_RESPONSE。 */
  @Test
  void test_completeTerminalOnlyRejectsMissingName() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});

    String completedEvent =
        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_term_no_name\",\"status\":\"completed\","
            + "\"output\":[{\"type\":\"function_call\",\"call_id\":\"call_1\",\"arguments\":\"{}\"}]}}";
    accumulator.processEvent(MAPPER.readTree(completedEvent));

    ProviderException ex = assertThrows(ProviderException.class, accumulator::finish);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
  }

  /** 意图：验证 LENGTH 终态下仅在 terminal output 出现的不完整 tool call 生成诊断且禁用回放。 */
  @Test
  void test_lengthTerminalOnlyIncompleteToolCallProducesDiagnosticAndNoReplay() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});

    // terminal output 中参数截断
    String incompleteEvent =
        "{\"type\":\"response.incomplete\",\"response\":{\"id\":\"resp_term_trunc\",\"status\":\"incomplete\","
            + "\"incomplete_details\":{\"reason\":\"max_output_tokens\"},"
            + "\"output\":[{\"type\":\"function_call\",\"call_id\":\"call_term_trunc\",\"name\":\"search\",\"arguments\":\"{\\\"query\\\": \\\"part\"}]}}";
    accumulator.processEvent(MAPPER.readTree(incompleteEvent));

    ProviderResponse resp = accumulator.response();
    assertEquals(GenerationStopReason.LENGTH, resp.stopReason());
    assertTrue(
        resp.toolCalls().isEmpty(), "truncated tool call must not be synthesized into toolCalls");
    assertEquals(1, resp.toolCallDiagnostics().size());
    assertEquals("call_term_trunc", resp.toolCallDiagnostics().get(0).id());
    assertEquals("search", resp.toolCallDiagnostics().get(0).name());
    assertEquals("{\"query\": \"part", resp.toolCallDiagnostics().get(0).partialArguments());
    assertNull(
        accumulator.replayState(),
        "replay state must be disabled when tool arguments are truncated");
  }

  /** 意图：验证显式 usage: null 作为最新快照清空前一 usage 状态并使 rawUsageJson 为 {}。 */
  @Test
  void test_usageNullSnapshotOverwritesPreviousSnapshotAndClearsUsage() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});

    // 快照 1：包含用量对象
    String snapshot1 =
        "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_snap_null\",\"service_tier\":\"default\","
            + "\"usage\":{\"input_tokens\":100,\"output_tokens\":50,\"total_tokens\":150,"
            + "\"input_tokens_details\":{\"cached_tokens\":30,\"cache_write_tokens\":10},"
            + "\"output_tokens_details\":{\"reasoning_tokens\":20}}}}";
    accumulator.processEvent(MAPPER.readTree(snapshot1));

    // 快照 2：显式 usage: null
    String snapshot2 =
        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_snap_null\",\"status\":\"completed\","
            + "\"usage\":null}}";
    accumulator.processEvent(MAPPER.readTree(snapshot2));

    ProviderResponse resp = accumulator.response();
    ModelUsage usage = resp.usage();
    assertEquals(0L, usage.inputTokens());
    assertEquals(0L, usage.outputTokens());
    assertEquals(0L, usage.providerTotalTokens());
    assertEquals(0L, usage.cacheReadTokens());
    assertEquals(0L, usage.cacheWriteTokens());
    assertEquals(0L, usage.reasoningTokens());
    assertEquals("{}", resp.rawUsageJson());
  }

  /** 意图：验证当 terminal response 提供 output 且为空/仅消息时，前序流式有效 tool 被完全清空，终态权威。 */
  @Test
  void test_terminalOutputAuthoritativeOverPreviousStreamToolsEmpty() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});

    // 前序流式事件包含有效工具调用
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_auth_empty\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"id\":\"item_old\",\"type\":\"function_call\",\"call_id\":\"call_old\",\"name\":\"old_tool\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.function_call_arguments.done\",\"item_id\":\"item_old\",\"arguments\":\"{}\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":{\"id\":\"item_old\",\"type\":\"function_call\",\"call_id\":\"call_old\",\"name\":\"old_tool\",\"arguments\":\"{}\"}}"));

    // 终态 completed 显式提供 output 数组，但为空（或仅包含 assistant 消息）
    String completedEvent =
        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_auth_empty\",\"status\":\"completed\","
            + "\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Final text without tools\"}]}]}}";
    accumulator.processEvent(MAPPER.readTree(completedEvent));

    ProviderResponse resp = accumulator.response();
    assertEquals(GenerationStopReason.COMPLETE, resp.stopReason());
    assertEquals("Final text without tools", resp.text());
    assertTrue(
        resp.toolCalls().isEmpty(),
        "tool call from stream must be purged when terminal output has no tools");
    assertNotNull(accumulator.replayState());
    JsonNode replayOutput = accumulator.replayState().payload().get("output");
    assertEquals(1, replayOutput.size());
    assertEquals("message", replayOutput.get(0).path("type").asText());
  }

  /** 意图：验证当 terminal response 提供 output 时，前序流式 tool 被 terminal output 中的 tool 权威替换。 */
  @Test
  void test_terminalOutputAuthoritativeOverPreviousStreamToolsReplaced() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});

    // 前序流式事件包含工具 call_1
    accumulator.processEvent(
        MAPPER.readTree("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_auth_rep\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"id\":\"item_1\",\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"old_tool\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.function_call_arguments.done\",\"item_id\":\"item_1\",\"arguments\":\"{}\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":{\"id\":\"item_1\",\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"old_tool\",\"arguments\":\"{}\"}}"));

    // 终态 completed 显式提供 output 数组，替换为 call_2
    String completedEvent =
        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_auth_rep\",\"status\":\"completed\","
            + "\"output\":[{\"type\":\"function_call\",\"id\":\"item_2\",\"call_id\":\"call_2\",\"name\":\"new_tool\",\"arguments\":\"{}\"}]}}";
    accumulator.processEvent(MAPPER.readTree(completedEvent));

    ProviderResponse resp = accumulator.response();
    assertEquals(GenerationStopReason.COMPLETE, resp.stopReason());
    assertEquals(1, resp.toolCalls().size());
    assertEquals("call_2", resp.toolCalls().get(0).id());
    assertEquals("new_tool", resp.toolCalls().get(0).name());
    assertEquals("{}", resp.toolCalls().get(0).argumentsJson());

    assertNotNull(accumulator.replayState());
    JsonNode replayOutput = accumulator.replayState().payload().get("output");
    assertEquals(1, replayOutput.size());
    assertEquals("call_2", replayOutput.get(0).path("call_id").asText());
    assertEquals("new_tool", replayOutput.get(0).path("name").asText());
  }

  /** 意图：验证 response 中显式提供非 Array 的 output 字段（如对象、标量或 null）时严格抛出 INVALID_RESPONSE。 */
  @Test
  void test_rejectsNonArrayOutputInResponse() {
    List<String> invalidOutputs =
        List.of("{\"not\":\"an_array\"}", "\"scalar_string\"", "123", "null");
    for (String invalidOutput : invalidOutputs) {
      OpenAiResponsesStreamAccumulator accumulator =
          new OpenAiResponsesStreamAccumulator(
              createRequest(),
              createDescriptor(),
              "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
              e -> {});

      String completedEvent =
          "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_bad_output\",\"status\":\"completed\",\"output\":"
              + invalidOutput
              + "}}";
      ProviderException ex =
          assertThrows(
              ProviderException.class,
              () -> accumulator.processEvent(MAPPER.readTree(completedEvent)));
      assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
    }
  }

  /** 意图：验证即使前序流式 buffer 中存在参数值，若 terminal output 中 raw item 缺失 arguments，在 COMPLETE 终态下必须拒绝。 */
  @Test
  void test_completeRejectsTerminalOutputItemMissingArgumentsEvenIfStreamBufferHadValue()
      throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});

    // 前序流式事件包含有效参数增量
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_buf_missing_term\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.added\",\"output_index\":0,"
                + "\"item\":{\"id\":\"item_1\",\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"calc\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"item_1\",\"delta\":\"{\\\"x\\\": 1}\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.function_call_arguments.done\",\"item_id\":\"item_1\",\"arguments\":\"{\\\"x\\\": 1}\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":{\"id\":\"item_1\",\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"calc\",\"arguments\":\"{\\\"x\\\": 1}\"}}"));

    // 终态 completed 显式提供 output，但其中的 function_call 缺失 arguments 字段
    String completedEvent =
        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_buf_missing_term\",\"status\":\"completed\","
            + "\"output\":[{\"type\":\"function_call\",\"id\":\"item_1\",\"call_id\":\"call_1\",\"name\":\"calc\"}]}}";
    accumulator.processEvent(MAPPER.readTree(completedEvent));

    // 根据 terminal output 权威重建原则，该 tool 在 terminal 中缺失 arguments，COMPLETE 必须拒绝
    ProviderException ex = assertThrows(ProviderException.class, accumulator::finish);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
  }

  /** 意图：验证 terminal response 显式提供 output 数组时，权威替换前序流式草稿文本与思考内容，且不触发重复流式事件。 */
  @Test
  void test_terminalOutputReplacesStreamedTextAndThinkingWithoutDuplicateEvents() throws Exception {
    List<ProviderStreamEvent> emittedEvents = new ArrayList<>();
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            emittedEvents::add);

    // 1. 流式输出草稿文本与思考内容
    accumulator.processEvent(
        MAPPER.readTree("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_rep_1\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"Draft stream text\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.reasoning_text.delta\",\"output_index\":0,\"delta\":\"Draft stream thinking\"}"));

    assertEquals(
        2, emittedEvents.size(), "stream must have emitted 2 deltas during streaming phase");

    // 2. 终态 completed 显式提供权威 output，替换文本与思考内容
    String completedEvent =
        """
        {
          "type": "response.completed",
          "response": {
            "id": "resp_rep_1",
            "status": "completed",
            "output": [
              {
                "type": "reasoning",
                "summary": [
                  {
                    "type": "summary_text",
                    "text": "Authoritative final thinking"
                  }
                ]
              },
              {
                "type": "message",
                "role": "assistant",
                "content": [
                  {
                    "type": "output_text",
                    "text": "Authoritative final answer"
                  }
                ]
              }
            ]
          }
        }
        """;
    accumulator.processEvent(MAPPER.readTree(completedEvent));

    // 验证未发射重复流式事件
    assertEquals(
        2,
        emittedEvents.size(),
        "processing terminal output must not emit duplicate stream events");

    ProviderResponse resp = accumulator.response();
    assertEquals(GenerationStopReason.COMPLETE, resp.stopReason());
    assertEquals("Authoritative final answer", resp.text());
    assertEquals("Authoritative final thinking", resp.thinking());
    assertFalse(resp.text().contains("Draft"), "draft text must not survive terminal replacement");
    assertFalse(
        resp.thinking().contains("Draft"), "draft thinking must not survive terminal replacement");

    // 验证 replayState
    assertNotNull(accumulator.replayState());
    JsonNode replayOutput = accumulator.replayState().payload().get("output");
    assertEquals(2, replayOutput.size());
    assertEquals("reasoning", replayOutput.get(0).path("type").asText());
    assertEquals("message", replayOutput.get(1).path("type").asText());
  }

  /** 意图：验证 terminal response 显式提供空的 output 数组时，前序流式草稿文本、思考内容与工具调用被完全清空，且无 replayState。 */
  @Test
  void test_terminalEmptyOutputClearsStreamedContentAndReplay() throws Exception {
    List<ProviderStreamEvent> emittedEvents = new ArrayList<>();
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            emittedEvents::add);

    // 1. 流式输出草稿文本、思考内容与工具调用
    accumulator.processEvent(
        MAPPER.readTree("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_clear_1\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"Draft text to clear\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.reasoning_text.delta\",\"output_index\":0,\"delta\":\"Draft thinking to clear\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.added\",\"output_index\":1,\"item\":{\"id\":\"item_1\",\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"search\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.function_call_arguments.done\",\"item_id\":\"item_1\",\"arguments\":\"{}\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.done\",\"output_index\":1,\"item\":{\"id\":\"item_1\",\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"search\",\"arguments\":\"{}\"}}"));

    // 2. 终态 completed 显式提供空 output 数组
    String completedEvent =
        "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_clear_1\",\"status\":\"completed\",\"output\":[]}}";
    accumulator.processEvent(MAPPER.readTree(completedEvent));

    ProviderResponse resp = accumulator.response();
    assertEquals(GenerationStopReason.COMPLETE, resp.stopReason());
    assertEquals("", resp.text(), "streamed draft text must be cleared by terminal empty output");
    assertEquals(
        "", resp.thinking(), "streamed draft thinking must be cleared by terminal empty output");
    assertTrue(
        resp.toolCalls().isEmpty(), "streamed tool calls must be cleared by terminal empty output");
    assertNull(
        accumulator.replayState(), "replay state must be null when terminal output is empty");
  }

  /** 意图：验证 terminal response 显式提供仅包含 message 的 output 时，前序流式思考内容被完全清除，终态权威。 */
  @Test
  void test_terminalOutputReplacesStreamedContentAndOmitsThinking() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});

    accumulator.processEvent(
        MAPPER.readTree("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_omit_th\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"Old draft text\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.reasoning_text.delta\",\"output_index\":0,\"delta\":\"Old draft thinking\"}"));

    String completedEvent =
        """
        {
          "type": "response.completed",
          "response": {
            "id": "resp_omit_th",
            "status": "completed",
            "output": [
              {
                "type": "message",
                "role": "assistant",
                "content": [
                  {
                    "type": "output_text",
                    "text": "Replacement message only"
                  }
                ]
              }
            ]
          }
        }
        """;
    accumulator.processEvent(MAPPER.readTree(completedEvent));

    ProviderResponse resp = accumulator.response();
    assertEquals(GenerationStopReason.COMPLETE, resp.stopReason());
    assertEquals("Replacement message only", resp.text());
    assertEquals(
        "", resp.thinking(), "streamed thinking must not survive when omitted by terminal output");
    assertNotNull(accumulator.replayState());
    JsonNode replayOutput = accumulator.replayState().payload().get("output");
    assertEquals(1, replayOutput.size());
    assertEquals("message", replayOutput.get(0).path("type").asText());
  }

  /**
   * 意图：验证 response.created 携带 output:[] 时，随后通过 output_item 流式接收文本， 且 terminal completed response 省略
   * output 字段时，最终文本与 raw replay 正确保留，不被初始 snapshot 误清。
   */
  @Test
  void test_createdWithEmptyOutputAndStreamedItemDonePreservesReplayWhenTerminalOmitsOutput()
      throws Exception {
    List<ProviderStreamEvent> emittedEvents = new ArrayList<>();
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            emittedEvents::add);

    // 1. response.created 携带初始空 output 数组
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_stream_raw\",\"output\":[]}}"));

    // 2. 流式接收 message item 与 text delta
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.added\",\"output_index\":0,\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[]}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"Hello streamed world\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.done\",\"output_index\":0,\"item\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Hello streamed world\"}]}}"));

    // 3. terminal completed response 省略 output 字段
    String completedEvent =
        """
        {
          "type": "response.completed",
          "response": {
            "id": "resp_stream_raw",
            "status": "completed",
            "usage": {
              "input_tokens": 10,
              "output_tokens": 5,
              "total_tokens": 15
            }
          }
        }
        """;
    accumulator.processEvent(MAPPER.readTree(completedEvent));

    ProviderResponse resp = accumulator.response();
    assertEquals(GenerationStopReason.COMPLETE, resp.stopReason());
    assertEquals("Hello streamed world", resp.text());

    assertNotNull(
        accumulator.replayState(), "replay state must not be cleared by created output:[]");
    JsonNode replayOutput = accumulator.replayState().payload().get("output");
    assertNotNull(replayOutput);
    assertEquals(1, replayOutput.size());
    assertEquals("message", replayOutput.get(0).path("type").asText());
    assertEquals(
        "Hello streamed world", replayOutput.get(0).path("content").get(0).path("text").asText());
  }

  /**
   * 意图：验证 response.created 携带 output:[] 时，随后仅收到纯文本 delta（无 output_item.done）， 且 terminal completed
   * response 省略 output 字段时，最终文本与合成 replay 均正确保留。
   */
  @Test
  void
      test_createdWithEmptyOutputAndPureTextDeltasPreservesSynthesizedReplayWhenTerminalOmitsOutput()
          throws Exception {
    List<ProviderStreamEvent> emittedEvents = new ArrayList<>();
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            emittedEvents::add);

    // 1. response.created 携带初始空 output 数组
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_pure_delta\",\"output\":[]}}"));

    // 2. 仅接收文本 delta
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_text.delta\",\"output_index\":0,\"content_index\":0,\"delta\":\"Pure streamed delta\"}"));

    // 3. terminal completed response 省略 output 字段
    String completedEvent =
        """
        {
          "type": "response.completed",
          "response": {
            "id": "resp_pure_delta",
            "status": "completed",
            "usage": {
              "input_tokens": 10,
              "output_tokens": 5,
              "total_tokens": 15
            }
          }
        }
        """;
    accumulator.processEvent(MAPPER.readTree(completedEvent));

    ProviderResponse resp = accumulator.response();
    assertEquals(GenerationStopReason.COMPLETE, resp.stopReason());
    assertEquals("Pure streamed delta", resp.text());

    assertNotNull(accumulator.replayState(), "replay state must be synthesized from textBuffer");
    JsonNode replayOutput = accumulator.replayState().payload().get("output");
    assertNotNull(replayOutput);
    assertEquals(1, replayOutput.size());
    assertEquals("message", replayOutput.get(0).path("type").asText());
    assertEquals(
        "Pure streamed delta", replayOutput.get(0).path("content").get(0).path("text").asText());
  }

  /** 意图：验证 terminal output 的兼容文本简写在终态结果与标准化 replay 中均被无损保留。 */
  @Test
  void test_terminalTextualMessageAndReasoningContentPreservedInReplay() throws Exception {
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            e -> {});

    accumulator.processEvent(
        MAPPER.readTree(
            """
            {
              "type": "response.completed",
              "response": {
                "id": "resp_textual_terminal",
                "output": [
                  {"type": "reasoning", "summary": "Condensed reasoning"},
                  {"type": "message", "role": "assistant", "content": "Final answer"}
                ]
              }
            }
            """));

    ProviderResponse response = accumulator.response();
    assertEquals("Final answer", response.text());
    assertEquals("Condensed reasoning", response.thinking());

    JsonNode replayOutput = accumulator.replayState().payload().get("output");
    assertEquals(
        "Condensed reasoning", replayOutput.get(0).path("summary").get(0).path("text").asText());
    assertEquals("Final answer", replayOutput.get(1).path("content").get(0).path("text").asText());
  }

  /**
   * 意图：前序 reasoning item 占据 output_index=0 时，其后的 function_call（output_index=1）仍必须以连续工具序号 0 发布
   * ToolCallDelta；若直接透出 output_index，Runtime 会因稀疏序号误判 "final response omits a streamed tool
   * call"，因此这里同时断言最终响应确实产出该工具调用。
   */
  @Test
  void test_nonToolOutputPrecedingFunctionCallKeepsContiguousToolOrdinal() throws Exception {
    List<ProviderStreamEvent> emittedEvents = new ArrayList<>();
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            emittedEvents::add);

    accumulator.processEvent(
        MAPPER.readTree("{\"type\":\"response.created\",\"response\":{\"id\":\"resp_ordinal\"}}"));
    // reasoning item 占用 output_index=0，本身不产生任何工具调用
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.reasoning_summary_text.delta\",\"output_index\":0,\"summary_index\":0,\"delta\":\"Need a tool\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.done\",\"output_index\":0,"
                + "\"item\":{\"id\":\"rs_1\",\"type\":\"reasoning\","
                + "\"summary\":[{\"type\":\"summary_text\",\"text\":\"Need a tool\"}]}}"));
    // function_call 位于 output_index=1，但工具序号必须是 0
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.added\",\"output_index\":1,"
                + "\"item\":{\"id\":\"item_1\",\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"search\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.function_call_arguments.delta\",\"item_id\":\"item_1\",\"delta\":\"{\\\"query\\\": \\\"paris\\\"}\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.function_call_arguments.done\",\"item_id\":\"item_1\",\"arguments\":\"{\\\"query\\\": \\\"paris\\\"}\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.done\",\"output_index\":1,"
                + "\"item\":{\"id\":\"item_1\",\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"search\","
                + "\"arguments\":\"{\\\"query\\\": \\\"paris\\\"}\"}}"));

    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_ordinal\",\"status\":\"completed\","
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":20,\"total_tokens\":30},"
                + "\"output\":["
                + "{\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"Need a tool\"}]},"
                + "{\"type\":\"function_call\",\"id\":\"item_1\",\"call_id\":\"call_1\",\"name\":\"search\","
                + "\"arguments\":\"{\\\"query\\\": \\\"paris\\\"}\"}"
                + "]}}"));

    // 首条 added 增量与后续 arguments 增量必须共用连续序号 0，绝不透出 output_index=1
    List<ProviderStreamEvent.ToolCallDelta> toolDeltas = toolDeltas(emittedEvents);
    assertEquals(
        List.of(0, 0),
        toolDeltas.stream().map(ProviderStreamEvent.ToolCallDelta::index).toList(),
        "tool call deltas must use the contiguous tool ordinal rather than the Responses output_index");
    assertEquals("call_1", toolDeltas.get(0).id());
    assertEquals("search", toolDeltas.get(0).name());
    assertEquals("{\"query\": \"paris\"}", toolDeltas.get(1).argumentsJson());

    ProviderResponse resp = accumulator.response();
    assertEquals(GenerationStopReason.COMPLETE, resp.stopReason());
    assertEquals(1, resp.toolCalls().size(), "final response must contain the streamed tool call");
    assertEquals("call_1", resp.toolCalls().get(0).id());
    assertEquals("search", resp.toolCalls().get(0).name());
    assertEquals("{\"query\": \"paris\"}", resp.toolCalls().get(0).argumentsJson());
  }

  /**
   * 意图：前序 reasoning / message item 抬高 output_index 后，多个 function_call 的工具序号仍必须是连续的 0..N-1，
   * 且顺序与最终响应一致，避免任何空洞导致 Runtime reconcile 失败。
   */
  @Test
  void test_multipleFunctionCallsKeepContiguousOrdinalsAfterNonToolOutputs() throws Exception {
    List<ProviderStreamEvent> emittedEvents = new ArrayList<>();
    OpenAiResponsesStreamAccumulator accumulator =
        new OpenAiResponsesStreamAccumulator(
            createRequest(),
            createDescriptor(),
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            emittedEvents::add);

    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_multi_ordinal\"}}"));
    // reasoning 与 message 分别占据 output_index=0 / 1
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.reasoning_summary_text.delta\",\"output_index\":0,\"summary_index\":0,\"delta\":\"Plan\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_text.delta\",\"output_index\":1,\"content_index\":0,\"delta\":\"Calling tools\"}"));
    // 两个 function_call 位于 output_index=2 / 3，工具序号必须是 0 / 1
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.added\",\"output_index\":2,"
                + "\"item\":{\"id\":\"item_1\",\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"search\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.function_call_arguments.done\",\"item_id\":\"item_1\",\"arguments\":\"{\\\"q\\\":\\\"abc\\\"}\"}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.output_item.added\",\"output_index\":3,"
                + "\"item\":{\"id\":\"item_2\",\"type\":\"function_call\",\"call_id\":\"call_2\",\"name\":\"calc\"}}"));
    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.function_call_arguments.done\",\"item_id\":\"item_2\",\"arguments\":\"{\\\"expr\\\":\\\"1+1\\\"}\"}"));

    accumulator.processEvent(
        MAPPER.readTree(
            "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_multi_ordinal\",\"status\":\"completed\","
                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":20,\"total_tokens\":30},"
                + "\"output\":["
                + "{\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"Plan\"}]},"
                + "{\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"output_text\",\"text\":\"Calling tools\"}]},"
                + "{\"type\":\"function_call\",\"id\":\"item_1\",\"call_id\":\"call_1\",\"name\":\"search\","
                + "\"arguments\":\"{\\\"q\\\":\\\"abc\\\"}\"},"
                + "{\"type\":\"function_call\",\"id\":\"item_2\",\"call_id\":\"call_2\",\"name\":\"calc\","
                + "\"arguments\":\"{\\\"expr\\\":\\\"1+1\\\"}\"}"
                + "]}}"));

    List<ProviderStreamEvent.ToolCallDelta> toolDeltas = toolDeltas(emittedEvents);
    assertEquals(
        List.of(0, 1),
        toolDeltas.stream().map(ProviderStreamEvent.ToolCallDelta::index).toList(),
        "tool call deltas must stay contiguous even when non-tool outputs occupy output_index slots");
    assertEquals("call_1", toolDeltas.get(0).id());
    assertEquals("call_2", toolDeltas.get(1).id());

    ProviderResponse resp = accumulator.response();
    assertEquals(GenerationStopReason.COMPLETE, resp.stopReason());
    assertEquals(2, resp.toolCalls().size());
    assertEquals("call_1", resp.toolCalls().get(0).id());
    assertEquals("call_2", resp.toolCalls().get(1).id());
  }

  /** 提取流式阶段发布的工具调用增量，用于断言工具序号连续性。 */
  private static List<ProviderStreamEvent.ToolCallDelta> toolDeltas(
      List<ProviderStreamEvent> events) {
    List<ProviderStreamEvent.ToolCallDelta> deltas = new ArrayList<>();
    for (ProviderStreamEvent event : events) {
      if (event instanceof ProviderStreamEvent.ToolCallDelta delta) {
        deltas.add(delta);
      }
    }
    return deltas;
  }
}
