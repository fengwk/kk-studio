package fun.fengwk.kkstudio.harness.provider.anthropic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import fun.fengwk.kkstudio.harness.provider.transport.JdkHttpSseTransport;
import fun.fengwk.kkstudio.harness.runtime.model.ModelDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.ModelInputModality;
import fun.fengwk.kkstudio.harness.runtime.model.ModelPricing;
import fun.fengwk.kkstudio.harness.runtime.model.ModelUsage;
import fun.fengwk.kkstudio.harness.runtime.model.ModelVariant;
import fun.fengwk.kkstudio.harness.runtime.model.cache.ProviderCacheControl;
import fun.fengwk.kkstudio.harness.runtime.model.provider.GenerationStopReason;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ModelCallTimeoutPolicy;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderCompletion;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderDescriptor;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderErrorKind;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderException;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessage;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderMessageRole;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderReplayFormat;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderRequest;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderResponse;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStream;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamEvent;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderStreamHandler;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderTextBlock;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderToolCall;
import fun.fengwk.kkstudio.harness.runtime.model.provider.ProviderType;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Anthropic Messages native SSE 流式解码与上游对等单测套件。
 *
 * <p>覆盖 Anthropic Messages API 标准流协议：
 *
 * <ul>
 *   <li>未知 content 类型平稳容错且 replayState 安全置空
 *   <li>text、thinking、redacted_thinking、tool_use 完整解码与 replayState 拼装
 *   <li>message_start 与 message_delta 累计 usage 快照准确更新（含 5m+1h cache breakdown 一致性检查）
 *   <li>HTTP 请求 URL 校验（包含 user-info/query/fragment 严格拒绝拦截，合法 URL 挂载 /messages）与 headers 校验
 *   <li>安全忽略 SSE [DONE] 哨兵与未识别事件帧
 *   <li>交错到达的多工具调用 delta 严格按出现次序分配连续稳定的 toolOrdinal
 * </ul>
 */
class AnthropicStreamingDecoderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String VALID_PREFIX_HASH =
      "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

  private HttpServer server;
  private int port;
  private ExecutorService workerExecutor;
  private ScheduledExecutorService scheduler;
  private HttpClient client;
  private JdkHttpSseTransport transport;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    port = server.getAddress().getPort();
    server.start();

    workerExecutor = Executors.newCachedThreadPool();
    scheduler = Executors.newSingleThreadScheduledExecutor();

    client =
        HttpClient.newBuilder()
            .executor(workerExecutor)
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    transport = new JdkHttpSseTransport(client, workerExecutor, scheduler);
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
    if (client != null) {
      client.shutdownNow();
    }
    if (workerExecutor != null) {
      workerExecutor.shutdownNow();
    }
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }

  /**
   * 测试意图：上游模型若下发未知类型的 content block（如未来扩展）， 流式解析器不得抛异常炸流，必须平稳忽略该未知 block，保留已知语义内容，并将 replayState 置为
   * null。
   */
  @Test
  void should_deserialize_content_with_unknown_type() {
    List<ProviderStreamEvent> events = new ArrayList<>();
    AnthropicStreamBridge bridge = new AnthropicStreamBridge(new RecordingHandler(events));

    ProviderRequest request = sampleRequest();
    ProviderDescriptor descriptor = sampleDescriptor("http://127.0.0.1:" + port);
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    // 1. message_start
    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_unk\",\"usage\":{\"input_tokens\":10}}}");

    // 2. content_block_start: 未知类型 "future_audio_block"
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"future_audio_block\",\"format\":\"flac\"}}");

    // 3. content_block_delta: 未知 block 的 delta
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"audio_data\",\"bytes\":\"AQID\"}}");

    // 4. content_block_stop: 未知 block 结束
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");

    // 5. content_block_start: 常规 text block
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"text\",\"text\":\"Known text.\"}}");

    // 6. content_block_stop
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}");

    // 7. message_delta
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":5}}");

    // 8. message_stop
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertNotNull(completion);
    ProviderResponse response = completion.response();
    assertEquals(GenerationStopReason.COMPLETE, response.stopReason());

    // 校验已知语义 text 得到保留
    assertEquals("Known text.", response.text());

    // 因包含非白名单原生 block，replayState 必须安全置为 null
    assertNull(completion.replayState());

    // usage 正常合并：Anthropic 无原生 total，providerTotalTokens 为 0，分类求和通过 categorizedTokens() 校验
    ModelUsage usage = response.usage();
    assertEquals(10, usage.inputTokens());
    assertEquals(5, usage.outputTokens());
    assertEquals(0, usage.totalTokens());
    assertEquals(0, usage.providerTotalTokens());
    assertEquals(15, usage.categorizedTokens());
  }

  /**
   * 测试意图：官方标准消息创建响应全流解码，覆盖 text、thinking（含 signature）、 redacted_thinking 和
   * tool_use，验证完整事件分发、completion 构造与 replayState 序列化。
   */
  @Test
  void shouldStreamCreateMessageResponse() {
    List<ProviderStreamEvent> events = new ArrayList<>();
    AnthropicStreamBridge bridge = new AnthropicStreamBridge(new RecordingHandler(events));

    ProviderRequest request = sampleRequest();
    ProviderDescriptor descriptor = sampleDescriptor("http://127.0.0.1:" + port);
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    // message_start 携带细分 cache creation
    accumulator.handleEvent(
        "message_start",
        """
        {
          "type": "message_start",
          "message": {
            "id": "msg_all_blocks",
            "usage": {
              "input_tokens": 100,
              "output_tokens": 1,
              "cache_read_input_tokens": 20,
              "cache_creation_input_tokens": 15,
              "cache_creation": {
                "ephemeral_5m_input_tokens": 10,
                "ephemeral_1h_input_tokens": 5
              }
            }
          }
        }
        """);

    // index 0: thinking
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"thinking\",\"thinking\":\"Plan: \"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"call weather API.\"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"signature_delta\",\"signature\":\"sig_xyz\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");

    // index 1: redacted_thinking
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"redacted_thinking\",\"data\":\"encrypted_blob\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}");

    // index 2: text
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"text\",\"text\":\"I will check \"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"text_delta\",\"text\":\"the weather.\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":2}");

    // index 3: tool_use
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":3,\"content_block\":{\"type\":\"tool_use\",\"id\":\"call_weather_1\",\"name\":\"get_weather\"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":3,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\":\\\"\"}}");
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":3,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"Hangzhou\\\"}\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":3}");

    // message_delta
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":40}}");

    // message_stop
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertNotNull(completion);
    ProviderResponse response = completion.response();

    // tool_use 映射为 COMPLETE
    assertEquals(GenerationStopReason.COMPLETE, response.stopReason());

    // 校验事件流
    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    e instanceof ProviderStreamEvent.ThinkingDelta td
                        && "Plan: ".equals(td.text())));
    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    e instanceof ProviderStreamEvent.ThinkingDelta td
                        && "call weather API.".equals(td.text())));
    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    e instanceof ProviderStreamEvent.TextDelta td
                        && "I will check ".equals(td.text())));
    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    e instanceof ProviderStreamEvent.TextDelta td
                        && "the weather.".equals(td.text())));
    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    e instanceof ProviderStreamEvent.ToolCallDelta td
                        && td.index() == 0
                        && "call_weather_1".equals(td.id())));

    // 校验 response 字段
    assertEquals("Plan: call weather API.", response.thinking());
    assertEquals("I will check the weather.", response.text());
    assertEquals(1, response.toolCalls().size());
    ProviderToolCall toolCall = response.toolCalls().get(0);
    assertEquals("call_weather_1", toolCall.id());
    assertEquals("get_weather", toolCall.name());
    assertEquals("{\"city\":\"Hangzhou\"}", toolCall.argumentsJson());

    // 校验累计 usage 快照更新（包含 cache read/write tokens）
    ModelUsage usage = response.usage();
    assertEquals(100, usage.inputTokens());
    assertEquals(40, usage.outputTokens());
    assertEquals(20, usage.cacheReadTokens());
    assertEquals(10, usage.cacheWriteTokens());
    assertEquals(5, usage.cacheWriteLongTokens());
    assertEquals(0, usage.totalTokens());
    assertEquals(0, usage.providerTotalTokens());
    assertEquals(175, usage.categorizedTokens());

    // 校验 replayState 包含完整的原生 payload
    assertNotNull(completion.replayState());
    assertEquals(ProviderReplayFormat.ANTHROPIC_MESSAGES, completion.replayState().format());
    assertEquals(VALID_PREFIX_HASH, completion.replayState().sourcePrefixHash());

    JsonNode replayContent = completion.replayState().payload().path("content");
    assertEquals(4, replayContent.size());
    assertEquals("thinking", replayContent.get(0).path("type").asText());
    assertEquals("Plan: call weather API.", replayContent.get(0).path("thinking").asText());
    assertEquals("sig_xyz", replayContent.get(0).path("signature").asText());

    assertEquals("redacted_thinking", replayContent.get(1).path("type").asText());
    assertEquals("encrypted_blob", replayContent.get(1).path("data").asText());

    assertEquals("text", replayContent.get(2).path("type").asText());
    assertEquals("I will check the weather.", replayContent.get(2).path("text").asText());

    assertEquals("tool_use", replayContent.get(3).path("type").asText());
    assertEquals("call_weather_1", replayContent.get(3).path("id").asText());
    assertEquals("Hangzhou", replayContent.get(3).path("input").path("city").asText());
  }

  /**
   * 测试意图：校验 message_start 中的缓存诊断信息，真实映射上游 diagnostics.cache_miss_reason。 断言 type 与
   * cache_missed_input_tokens 完整写入 rawUsageJson 中。
   */
  @Test
  void shouldIncludeCacheDiagnosticsFromMessageStartEvent() throws Exception {
    ProviderRequest request = sampleRequest();
    ProviderDescriptor descriptor = sampleDescriptor("http://127.0.0.1:" + port);

    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(
            request, descriptor, VALID_PREFIX_HASH, new AnthropicStreamBridge(new NoopHandler()));

    accumulator.handleEvent(
        "message_start",
        """
        {
          "type": "message_start",
          "message": {
            "id": "msg_diag",
            "usage": {
              "input_tokens": 50000
            },
            "diagnostics": {
              "cache_miss_reason": {
                "type": "system_changed",
                "cache_missed_input_tokens": 41850
              }
            }
          }
        }
        """);
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":20}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertNotNull(completion);
    JsonNode usageRoot = MAPPER.readTree(completion.response().rawUsageJson());
    assertEquals("system_changed", usageRoot.path("cache_miss_reason").path("type").asText());
    assertEquals(
        41850, usageRoot.path("cache_miss_reason").path("cache_missed_input_tokens").asLong());
  }

  /** 测试意图：当 message_start 未包含 diagnostics 字段时，rawUsageJson 中不得出现 cache_miss_reason。 */
  @Test
  void shouldOmitCacheMissReasonWhenDiagnosticsAreAbsent() throws Exception {
    ProviderRequest request = sampleRequest();
    ProviderDescriptor descriptor = sampleDescriptor("http://127.0.0.1:" + port);

    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(
            request, descriptor, VALID_PREFIX_HASH, new AnthropicStreamBridge(new NoopHandler()));

    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_diag_absent\",\"usage\":{\"input_tokens\":120}}}");
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":15}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertNotNull(completion);
    ProviderResponse response = completion.response();
    assertEquals(GenerationStopReason.COMPLETE, response.stopReason());
    assertEquals(120, response.usage().inputTokens());
    assertEquals(15, response.usage().outputTokens());

    JsonNode usageRoot = MAPPER.readTree(response.rawUsageJson());
    assertFalse(
        usageRoot.has("cache_miss_reason"),
        "rawUsageJson must not contain cache_miss_reason when diagnostics is absent");
  }

  /**
   * 测试意图：当 message_start 包含 diagnostics 节点但为空对象（无 cache_miss_reason）时，rawUsageJson 中不得包含
   * cache_miss_reason。
   */
  @Test
  void shouldOmitCacheMissReasonWhenDiagnosticsHaveNoReason() throws Exception {
    ProviderRequest request = sampleRequest();
    ProviderDescriptor descriptor = sampleDescriptor("http://127.0.0.1:" + port);

    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(
            request, descriptor, VALID_PREFIX_HASH, new AnthropicStreamBridge(new NoopHandler()));

    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_diag_empty\",\"usage\":{\"input_tokens\":200},\"diagnostics\":{}}}");
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":25}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertNotNull(completion);
    ProviderResponse response = completion.response();
    assertEquals(GenerationStopReason.COMPLETE, response.stopReason());
    assertEquals(200, response.usage().inputTokens());
    assertEquals(25, response.usage().outputTokens());

    JsonNode usageRoot = MAPPER.readTree(response.rawUsageJson());
    assertFalse(
        usageRoot.has("cache_miss_reason"),
        "rawUsageJson must not contain cache_miss_reason when diagnostics has no reason");
  }

  @Test
  void shouldHandleCacheCreation5mAnd1hBreakdown() {
    ProviderRequest request = sampleRequest();
    ProviderDescriptor descriptor = sampleDescriptor("http://127.0.0.1:" + port);

    AnthropicStreamAccumulator validAccumulator =
        new AnthropicStreamAccumulator(
            request, descriptor, VALID_PREFIX_HASH, new AnthropicStreamBridge(new NoopHandler()));

    validAccumulator.handleEvent(
        "message_start",
        """
        {
          "type": "message_start",
          "message": {
            "usage": {
              "input_tokens": 250,
              "cache_read_input_tokens": 40,
              "cache_creation_input_tokens": 100,
              "cache_creation": {
                "ephemeral_5m_input_tokens": 70,
                "ephemeral_1h_input_tokens": 30
              }
            }
          }
        }
        """);
    validAccumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":25}}");
    validAccumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = validAccumulator.finish();
    ProviderResponse response = completion.response();
    ModelUsage usage = response.usage();
    assertEquals(250, usage.inputTokens());
    assertEquals(25, usage.outputTokens());
    assertEquals(40, usage.cacheReadTokens());
    assertEquals(70, usage.cacheWriteTokens());
    assertEquals(30, usage.cacheWriteLongTokens());
    assertEquals(0, usage.totalTokens());
    assertEquals(0, usage.providerTotalTokens());
    assertEquals(415, usage.categorizedTokens());

    assertNotNull(response.rawUsageJson());
    assertTrue(response.rawUsageJson().contains("\"ephemeral_5m_input_tokens\":70"));
    assertTrue(response.rawUsageJson().contains("\"ephemeral_1h_input_tokens\":30"));
  }

  @Test
  void shouldRejectCacheBreakdownMismatchOnFinish() {
    ProviderRequest request = sampleRequest();
    ProviderDescriptor descriptor = sampleDescriptor("http://127.0.0.1:" + port);

    AnthropicStreamAccumulator invalidAccumulator =
        new AnthropicStreamAccumulator(
            request, descriptor, VALID_PREFIX_HASH, new AnthropicStreamBridge(new NoopHandler()));

    invalidAccumulator.handleEvent(
        "message_start",
        """
        {
          "type": "message_start",
          "message": {
            "usage": {
              "input_tokens": 250,
              "cache_creation_input_tokens": 100,
              "cache_creation": {
                "ephemeral_5m_input_tokens": 70,
                "ephemeral_1h_input_tokens": 20
              }
            }
          }
        }
        """);
    invalidAccumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":25}}");
    invalidAccumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderException ex = assertThrows(ProviderException.class, invalidAccumulator::finish);
    assertEquals(ProviderErrorKind.INVALID_RESPONSE, ex.kind());
  }

  @Test
  void shouldSafelyIgnoreUnknownCacheMissReasonAndRejectInvalidDiagnosticsFields() {
    ProviderRequest request = sampleRequest();
    ProviderDescriptor descriptor = sampleDescriptor("http://127.0.0.1:" + port);

    // 1. 未知 reason 不进入 rawUsageJson
    AnthropicStreamAccumulator unknownReasonAccumulator =
        new AnthropicStreamAccumulator(
            request, descriptor, VALID_PREFIX_HASH, new AnthropicStreamBridge(new NoopHandler()));
    unknownReasonAccumulator.handleEvent(
        "message_start",
        """
        {
          "type": "message_start",
          "message": {
            "usage": {"input_tokens": 10},
            "diagnostics": {
              "cache_miss_reason": {
                "type": "some_future_reason",
                "cache_missed_input_tokens": 5
              }
            }
          }
        }
        """);
    unknownReasonAccumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}");
    unknownReasonAccumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");
    ProviderCompletion completion = unknownReasonAccumulator.finish();
    assertFalse(completion.response().rawUsageJson().contains("cache_miss_reason"));

    // 2. diagnostics 节点不是 object -> INVALID_RESPONSE
    AnthropicStreamAccumulator nonObjDiag =
        new AnthropicStreamAccumulator(
            request, descriptor, VALID_PREFIX_HASH, new AnthropicStreamBridge(new NoopHandler()));
    assertThrows(
        ProviderException.class,
        () ->
            nonObjDiag.handleEvent(
                "message_start",
                "{\"type\":\"message_start\",\"message\":{\"diagnostics\":\"not_obj\"}}"));

    // 3. cache_missed_input_tokens 为负数 -> INVALID_RESPONSE
    AnthropicStreamAccumulator negTokens =
        new AnthropicStreamAccumulator(
            request, descriptor, VALID_PREFIX_HASH, new AnthropicStreamBridge(new NoopHandler()));
    assertThrows(
        ProviderException.class,
        () ->
            negTokens.handleEvent(
                "message_start",
                """
                {
                  "type": "message_start",
                  "message": {
                    "diagnostics": {
                      "cache_miss_reason": {
                        "type": "system_changed",
                        "cache_missed_input_tokens": -1
                      }
                    }
                  }
                }
                """));
  }

  /**
   * 测试意图：校验 AnthropicRequestEncoder 与底座网络发送端到端发送的 HTTP 报文：
   *
   * <ul>
   *   <li>baseUrl 若包含 user-info、query 或 fragment，必须 fail-closed 拦截拒绝；合法 baseUrl 严格挂载 /messages
   *   <li>x-api-key、Authorization: Bearer、anthropic-version、content-type、accept 请求头齐全且正确
   *   <li>不发送 MiniMax Anthropic-compatible endpoint 不接受的 anthropic-beta 请求头
   *   <li>POST body 满足 stream=true、messages 结构合规且不超过 32MiB
   * </ul>
   */
  @Test
  void shouldSendCorrectStreamingHttpRequest() throws Exception {
    AtomicReference<String> capturedPath = new AtomicReference<>();
    AtomicReference<String> capturedQuery = new AtomicReference<>();
    AtomicReference<String> capturedApiKey = new AtomicReference<>();
    AtomicReference<String> capturedAuthorization = new AtomicReference<>();
    AtomicReference<String> capturedVersion = new AtomicReference<>();
    AtomicReference<String> capturedBeta = new AtomicReference<>();
    AtomicReference<String> capturedAccept = new AtomicReference<>();
    AtomicReference<String> capturedContentType = new AtomicReference<>();
    AtomicReference<String> capturedBody = new AtomicReference<>();
    CountDownLatch serverLatch = new CountDownLatch(1);

    server.createContext(
        "/custom/v1/messages",
        exchange -> {
          try {
            capturedPath.set(exchange.getRequestURI().getPath());
            capturedQuery.set(exchange.getRequestURI().getQuery());
            capturedApiKey.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            capturedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            capturedVersion.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            capturedBeta.set(exchange.getRequestHeaders().getFirst("anthropic-beta"));
            capturedAccept.set(exchange.getRequestHeaders().getFirst("accept"));
            capturedContentType.set(exchange.getRequestHeaders().getFirst("content-type"));
            capturedBody.set(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));

            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);

            try (OutputStream os = exchange.getResponseBody()) {
              String sse =
                  """
                  event: message_start
                  data: {"type":"message_start","message":{"id":"msg_ok","usage":{"input_tokens":5}}}

                  event: content_block_start
                  data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                  event: content_block_delta
                  data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"HTTP OK"}}

                  event: content_block_stop
                  data: {"type":"content_block_stop","index":0}

                  event: message_delta
                  data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":2}}

                  event: message_stop
                  data: {"type":"message_stop"}

                  """;
              os.write(sse.getBytes(StandardCharsets.UTF_8));
              os.flush();
            }
          } finally {
            serverLatch.countDown();
          }
        });

    // 1. 验证包含 user-info、query 或 fragment 的 dirty baseUrl 会在解析时被安全拦截拒绝
    assertThrows(
        IllegalArgumentException.class,
        () ->
            AnthropicEndpoints.resolveMessagesUri(
                "http://user:secret@127.0.0.1:" + port + "/custom/v1?token=123#anchor"));

    // 2. 正常端到端请求校验
    String cleanBaseUrl = "http://127.0.0.1:" + port + "/custom/v1";
    ProviderDescriptor descriptor = sampleDescriptor(cleanBaseUrl);
    AnthropicProviderAdapter adapter = new AnthropicProviderAdapter(transport, "test-api-key");
    AnthropicModelProvider provider = (AnthropicModelProvider) adapter.create(descriptor);

    ProviderRequest request = sampleRequest();
    CountDownLatch completeLatch = new CountDownLatch(1);
    AtomicReference<ProviderCompletion> completionRef = new AtomicReference<>();

    provider.stream(
        request,
        new ProviderStreamHandler() {
          @Override
          public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

          @Override
          public void onComplete(ProviderCompletion completion, ProviderStream stream) {
            completionRef.set(completion);
            completeLatch.countDown();
          }

          @Override
          public void onError(ProviderException error, ProviderStream stream) {
            completeLatch.countDown();
          }
        });

    assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
    assertTrue(completeLatch.await(5, TimeUnit.SECONDS));

    // 校验规范化后的请求路径与去敏结果
    assertEquals("/custom/v1/messages", capturedPath.get());
    assertNull(capturedQuery.get()); // query 参数必须被完全剥除
    assertEquals("test-api-key", capturedApiKey.get());
    assertEquals("Bearer test-api-key", capturedAuthorization.get());
    assertEquals("2023-06-01", capturedVersion.get());
    assertNull(capturedBeta.get());
    assertEquals("text/event-stream", capturedAccept.get());
    assertTrue(capturedContentType.get().startsWith("application/json"));

    // 校验请求体
    JsonNode bodyJson = MAPPER.readTree(capturedBody.get());
    assertTrue(bodyJson.path("stream").asBoolean());
    assertEquals("claude-3-5-sonnet", bodyJson.path("model").asText());
    assertEquals(1, bodyJson.path("messages").size());

    // 校验客户端正常收敛
    assertNotNull(completionRef.get());
    assertEquals(GenerationStopReason.COMPLETE, completionRef.get().response().stopReason());
    assertEquals("HTTP OK", completionRef.get().response().text());
  }

  /**
   * 测试意图：SSE 流中可能存在注释帧、未知事件（如 ping、custom_heartbeat）以及类 OpenAI 网关注入的 `data: [DONE]`
   * 哨兵。解码器必须安全忽略这些帧，不能当作畸变数据报错。
   */
  @Test
  void shouldIgnoreDoneSentinelAndUnknownEventFrames() {
    List<ProviderStreamEvent> events = new ArrayList<>();
    AnthropicStreamBridge bridge = new AnthropicStreamBridge(new RecordingHandler(events));

    ProviderRequest request = sampleRequest();
    ProviderDescriptor descriptor = sampleDescriptor("http://127.0.0.1:" + port);
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    // 1. 注释帧与未知 ping 事件
    accumulator.handleEvent("ping", "{\"type\":\"ping\"}");
    accumulator.handleEvent("heartbeat", "{}");

    // 2. 正常流
    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_done\",\"usage\":{\"input_tokens\":10}}}");

    // 3. 乱入未知事件帧
    accumulator.handleEvent("custom_metric", "{\"metric\":\"load\",\"val\":1}");

    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"hello\"}}");
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}");

    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":2}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    // 4. 类 OpenAI [DONE] 哨兵或末尾空帧
    accumulator.handleEvent(null, "[DONE]");
    accumulator.handleEvent("", "[DONE]");

    ProviderCompletion completion = accumulator.finish();
    assertNotNull(completion);
    assertEquals(GenerationStopReason.COMPLETE, completion.response().stopReason());
    assertEquals("hello", completion.response().text());
  }

  /**
   * 测试意图：官方支持多个并行 tool call，且其 delta 在流中以交错（interleaved）形式到达。 解码器必须按原生 index 维护独立状态，按 tool
   * 出现顺序稳定分配连续的外部 toolOrdinal (0, 1)， 确保每个 delta 派发给外部 handler 的 toolOrdinal 稳定唯一，且最终能组装出完整
   * arguments。
   */
  @Test
  void shouldHandleInterleavedParallelToolCalls() {
    List<ProviderStreamEvent> events = new CopyOnWriteArrayList<>();
    AnthropicStreamBridge bridge = new AnthropicStreamBridge(new RecordingHandler(events));

    ProviderRequest request = sampleRequest();
    ProviderDescriptor descriptor = sampleDescriptor("http://127.0.0.1:" + port);
    AnthropicStreamAccumulator accumulator =
        new AnthropicStreamAccumulator(request, descriptor, VALID_PREFIX_HASH, bridge);

    // 0. message_start
    accumulator.handleEvent(
        "message_start",
        "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_interleaved\",\"usage\":{\"input_tokens\":30}}}");

    // 1. start1 (native index 1): call_1 ("weather")
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":1,\"content_block\":{\"type\":\"tool_use\",\"id\":\"call_1\",\"name\":\"weather\"}}");

    // 2. frag1 (native index 1): {"city"
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"city\\\"\"}}");

    // 3. start2 before stop1 (native index 2): call_2 ("time")
    accumulator.handleEvent(
        "content_block_start",
        "{\"type\":\"content_block_start\",\"index\":2,\"content_block\":{\"type\":\"tool_use\",\"id\":\"call_2\",\"name\":\"time\"}}");

    // 4. frag2 (native index 2): {"zone"
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"zone\\\"\"}}");

    // 5. frag1 (native index 1): : "Paris"}
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":1,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\": \\\"Paris\\\"}\"}}");

    // 6. frag2 (native index 2): : "UTC"}
    accumulator.handleEvent(
        "content_block_delta",
        "{\"type\":\"content_block_delta\",\"index\":2,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\": \\\"UTC\\\"}\"}}");

    // 7. stop1
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":1}");

    // 8. stop2
    accumulator.handleEvent("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":2}");

    // 9. message_delta & 10. message_stop
    accumulator.handleEvent(
        "message_delta",
        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"tool_use\"},\"usage\":{\"output_tokens\":20}}");
    accumulator.handleEvent("message_stop", "{\"type\":\"message_stop\"}");

    ProviderCompletion completion = accumulator.finish();
    assertNotNull(completion);
    assertEquals(GenerationStopReason.COMPLETE, completion.response().stopReason());

    // 精确断言所有 6 个本地 ToolCallDelta 事件，不按 id 过滤
    List<ProviderStreamEvent.ToolCallDelta> toolDeltas =
        events.stream()
            .filter(e -> e instanceof ProviderStreamEvent.ToolCallDelta)
            .map(e -> (ProviderStreamEvent.ToolCallDelta) e)
            .toList();

    assertEquals(6, toolDeltas.size());

    // 0: start ordinal 0
    assertEquals(0, toolDeltas.get(0).index());
    assertEquals("call_1", toolDeltas.get(0).id());
    assertEquals("weather", toolDeltas.get(0).name());
    assertNull(toolDeltas.get(0).argumentsJson());

    // 1: frag ordinal 0
    assertEquals(0, toolDeltas.get(1).index());
    assertNull(toolDeltas.get(1).id());
    assertNull(toolDeltas.get(1).name());
    assertEquals("{\"city\"", toolDeltas.get(1).argumentsJson());

    // 2: start ordinal 1
    assertEquals(1, toolDeltas.get(2).index());
    assertEquals("call_2", toolDeltas.get(2).id());
    assertEquals("time", toolDeltas.get(2).name());
    assertNull(toolDeltas.get(2).argumentsJson());

    // 3: frag ordinal 1
    assertEquals(1, toolDeltas.get(3).index());
    assertNull(toolDeltas.get(3).id());
    assertNull(toolDeltas.get(3).name());
    assertEquals("{\"zone\"", toolDeltas.get(3).argumentsJson());

    // 4: frag ordinal 0
    assertEquals(0, toolDeltas.get(4).index());
    assertNull(toolDeltas.get(4).id());
    assertNull(toolDeltas.get(4).name());
    assertEquals(": \"Paris\"}", toolDeltas.get(4).argumentsJson());

    // 5: frag ordinal 1
    assertEquals(1, toolDeltas.get(5).index());
    assertNull(toolDeltas.get(5).id());
    assertNull(toolDeltas.get(5).name());
    assertEquals(": \"UTC\"}", toolDeltas.get(5).argumentsJson());

    // 断言两个 final args
    List<ProviderToolCall> finalCalls = completion.response().toolCalls();
    assertEquals(2, finalCalls.size());

    assertEquals("call_1", finalCalls.get(0).id());
    assertEquals("weather", finalCalls.get(0).name());
    assertEquals("{\"city\": \"Paris\"}", finalCalls.get(0).argumentsJson());

    assertEquals("call_2", finalCalls.get(1).id());
    assertEquals("time", finalCalls.get(1).name());
    assertEquals("{\"zone\": \"UTC\"}", finalCalls.get(1).argumentsJson());

    // 断言 replay 顺序
    assertNotNull(completion.replayState());
    JsonNode replayContent = completion.replayState().payload().path("content");
    assertEquals(2, replayContent.size());
    assertEquals("call_1", replayContent.get(0).path("id").asText());
    assertEquals("call_2", replayContent.get(1).path("id").asText());
  }

  // --- 辅助工厂方法 ---

  private static ProviderRequest sampleRequest() {
    ModelDescriptor model =
        new ModelDescriptor(
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
                BigDecimal.valueOf(3.0),
                BigDecimal.valueOf(15.0),
                BigDecimal.valueOf(0.3),
                BigDecimal.valueOf(3.75),
                BigDecimal.valueOf(6.0),
                BigDecimal.ZERO));
    ModelVariant variant = new ModelVariant("default");
    return new ProviderRequest(
        model,
        variant,
        1024,
        "Test system instruction.",
        List.of(
            new ProviderMessage(
                ProviderMessageRole.USER, List.of(new ProviderTextBlock("Hello Anthropic")))),
        List.of(),
        ProviderCacheControl.none());
  }

  private static ProviderDescriptor sampleDescriptor(String baseUrl) {
    return new ProviderDescriptor(
        "test-anthropic",
        ProviderType.ANTHROPIC,
        baseUrl,
        new ModelCallTimeoutPolicy(Duration.ofSeconds(30), Duration.ofSeconds(10)),
        UUID.randomUUID());
  }

  private static final class RecordingHandler implements ProviderStreamHandler {
    private final List<ProviderStreamEvent> target;

    private RecordingHandler(List<ProviderStreamEvent> target) {
      this.target = target;
    }

    @Override
    public void onEvent(ProviderStreamEvent event, ProviderStream stream) {
      target.add(event);
    }

    @Override
    public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

    @Override
    public void onError(ProviderException error, ProviderStream stream) {}
  }

  private static final class NoopHandler implements ProviderStreamHandler {
    @Override
    public void onEvent(ProviderStreamEvent event, ProviderStream stream) {}

    @Override
    public void onComplete(ProviderCompletion completion, ProviderStream stream) {}

    @Override
    public void onError(ProviderException error, ProviderStream stream) {}
  }
}
