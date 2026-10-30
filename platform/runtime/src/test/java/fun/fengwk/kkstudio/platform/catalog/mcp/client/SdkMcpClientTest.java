package fun.fengwk.kkstudio.platform.catalog.mcp.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import fun.fengwk.kkstudio.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.common.result.TextResultContent;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link SdkMcpClient} 的调用级语义测试。
 *
 * <p>{@link RemoteMcpClientTest} 用真实 Streamable HTTP 服务覆盖正常链路；本测试用 SDK
 * 替身隔离底层阻塞调用，覆盖真实服务端难以稳定构造的路径：工具错误结果透传、本地入参校验、超时/取消/中断的收敛、结果内容与详情映射，以及关闭时的清理与异常隔离。
 *
 * <p>测试意图是锁定「失败分类」与「绝不泄漏底层事实」这两条对外契约，而不仅是执行到代码。
 */
class SdkMcpClientTest {

  private static final Duration SHORT_BUDGET = Duration.ofMillis(200);
  private static final Duration BUDGET = Duration.ofSeconds(5);

  private final List<SdkMcpClient> clients = new ArrayList<>();

  @AfterEach
  void closeClients() {
    for (SdkMcpClient client : clients) {
      client.close();
    }
    clients.clear();
  }

  /** name 为空必须在本地拒绝，且绝不触碰 SDK（否则会把畸形请求发到服务端）。 */
  @Test
  void rejectsBlankToolNameBeforeTouchingSdk() {
    McpSyncClient sdk = mock(McpSyncClient.class);
    SdkMcpClient client = newClient(sdk);

    assertThatThrownBy(() -> client.callTool("  ", "{}", McpDeadline.of(BUDGET), none()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name");
    verify(sdk, never()).callTool(any());
  }

  /** 入参必须是严格 JSON object：非 object、重复键与尾随 token 都在本地拒绝，且错误文本不回显 payload。 */
  @Test
  void rejectsNonStrictJsonArgumentsLocallyWithoutEchoingPayload() {
    McpSyncClient sdk = mock(McpSyncClient.class);
    SdkMcpClient client = newClient(sdk);

    for (String malformed :
        List.of("not json", "[1]", "{} {}", "{\"secret_key\":1,\"secret_key\":2}")) {
      assertThatThrownBy(() -> client.callTool("tool", malformed, McpDeadline.of(BUDGET), none()))
          .as("畸形入参必须被本地拒绝：%s", malformed)
          .isInstanceOf(McpException.class)
          .hasMessage("MCP tool arguments must be a strict JSON object")
          .hasNoCause();
    }
    verify(sdk, never()).callTool(any());
  }

  /** 空入参沿用「缺省为空对象」的既有语义：规范化后交给 SDK 的是空 arguments map。 */
  @Test
  void normalizesBlankArgumentsToEmptyJsonObject() {
    McpSyncClient sdk = mock(McpSyncClient.class);
    when(sdk.callTool(any()))
        .thenReturn(McpSchema.CallToolResult.builder().addTextContent("ok").build());
    SdkMcpClient client = newClient(sdk);

    for (String blank : List.of("", "   ")) {
      assertThat(client.callTool("tool", blank, McpDeadline.of(BUDGET), none()).error()).isFalse();
    }
    verify(sdk, times(2)).callTool(any());
  }

  /** SDK 以 isError 结果返回工具错误：必须映射为 error 结果透传工具输出，而不是上抛为连接失败。 */
  @Test
  void mapsToolErrorResultToErrorResult() {
    McpSyncClient sdk = mock(McpSyncClient.class);
    when(sdk.callTool(any()))
        .thenReturn(
            McpSchema.CallToolResult.builder()
                .addTextContent("tool exploded")
                .isError(true)
                .build());
    SdkMcpClient client = newClient(sdk);

    McpToolCallResult result = client.callTool("tool", "{}", McpDeadline.of(BUDGET), none());

    assertThat(result.error()).isTrue();
    assertThat(result.contents()).containsExactly(new TextResultContent("tool exploded"));
    assertThat(result.detailsJson()).isEqualTo("{}");
  }

  /** SDK 异常没有可读文本时必须给出固定兜底文案，绝不把 null 或空白当成错误内容暴露。 */
  @Test
  void mapsMessageLessToolErrorResultToCanonicalFallback() {
    McpSyncClient sdk = mock(McpSyncClient.class);
    when(sdk.callTool(any()))
        .thenReturn(
            McpSchema.CallToolResult.builder().isError(true).build(),
            McpSchema.CallToolResult.builder().addTextContent("   ").isError(true).build());
    SdkMcpClient client = newClient(sdk);

    for (int attempt = 0; attempt < 2; attempt++) {
      McpToolCallResult result = client.callTool("tool", "{}", McpDeadline.of(BUDGET), none());
      assertThat(result.error()).isTrue();
      assertThat(result.contents())
          .containsExactly(new TextResultContent("MCP tool returned an error result"));
    }
  }

  /** 底层失败必须收敛为固定不透明文本：不回显 SDK 消息，也不保留 cause。 */
  @Test
  void keepsUnderlyingFailuresOpaqueAndLabelsTheOperation() {
    McpSyncClient sdk = mock(McpSyncClient.class);
    when(sdk.callTool(any())).thenThrow(new IllegalStateException("token=super-secret"));
    when(sdk.listTools()).thenThrow(new IllegalStateException("host=internal.local"));
    SdkMcpClient client = newClient(sdk);

    assertThatThrownBy(() -> client.callTool("tool", "{}", McpDeadline.of(BUDGET), none()))
        .isInstanceOf(McpException.class)
        .hasMessage("MCP tool call failed")
        .hasNoCause()
        .satisfies(error -> assertThat(error.getMessage()).doesNotContain("super-secret"));
    assertThatThrownBy(() -> client.listTools(McpDeadline.of(BUDGET), none()))
        .isInstanceOf(McpException.class)
        .hasMessage("MCP listTools failed");
  }

  /** JSON-RPC 协议错误同样属于连接/协议失败，不能伪装成可读的工具错误结果。 */
  @Test
  void mapsProtocolErrorToOpaqueMcpException() {
    McpSyncClient sdk = mock(McpSyncClient.class);
    when(sdk.callTool(any()))
        .thenThrow(McpError.builder(-32601).message("method not found").build());
    SdkMcpClient client = newClient(sdk);

    assertThatThrownBy(() -> client.callTool("tool", "{}", McpDeadline.of(BUDGET), none()))
        .isInstanceOf(McpException.class)
        .hasMessage("MCP tool call failed")
        .hasNoCause();
  }

  /** SDK 自身请求超时被包装为执行失败：必须沿 cause 链识别并映射为超时。 */
  @Test
  void mapsSdkRequestTimeoutToTimeoutException() {
    McpSyncClient sdk = mock(McpSyncClient.class);
    when(sdk.callTool(any()))
        .thenThrow(
            new RuntimeException(
                "Client failed to initialize calling tool",
                new TimeoutException("request timed out")));
    SdkMcpClient client = newClient(sdk);

    assertThatThrownBy(() -> client.callTool("tool", "{}", McpDeadline.of(BUDGET), none()))
        .isInstanceOf(McpTimeoutException.class)
        .hasMessage("MCP tool call timed out");
  }

  /** 预算耗尽必须迅速结束本地等待，并真正中断仍在执行的 SDK 调用，避免后台线程继续占用连接。 */
  @Test
  @Timeout(30)
  void timesOutAndInterruptsInFlightSdkCall() throws Exception {
    CountDownLatch callStarted = new CountDownLatch(1);
    CountDownLatch workerInterrupted = new CountDownLatch(1);
    McpSyncClient sdk = mock(McpSyncClient.class);
    when(sdk.callTool(any()))
        .thenAnswer(
            invocation -> {
              callStarted.countDown();
              try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(30));
              } catch (InterruptedException error) {
                workerInterrupted.countDown();
                throw new IllegalStateException("interrupted sdk call", error);
              }
              return McpSchema.CallToolResult.builder().addTextContent("late").build();
            });
    SdkMcpClient client = newClient(sdk);

    long startNanos = System.nanoTime();
    assertThatThrownBy(() -> client.callTool("tool", "{}", McpDeadline.of(SHORT_BUDGET), none()))
        .isInstanceOf(McpTimeoutException.class)
        .hasMessage("MCP tool call timed out");
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

    assertThat(elapsedMillis).as("超时必须按预算收敛").isLessThan(3_000L);
    assertThat(callStarted.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(workerInterrupted.await(5, TimeUnit.SECONDS)).as("在途调用必须被中断").isTrue();
  }

  /** SDK 自身以取消结束时必须如实报告取消，而不是退化为「连接失败」并让上层销毁共享 client。 */
  @Test
  void mapsSdkCancellationToCancelledException() {
    McpSyncClient sdk = mock(McpSyncClient.class);
    when(sdk.callTool(any())).thenThrow(new CancellationException("cancelled by sdk"));
    SdkMcpClient client = newClient(sdk);

    assertThatThrownBy(() -> client.callTool("tool", "{}", McpDeadline.of(BUDGET), none()))
        .isInstanceOf(McpCancelledException.class)
        .hasMessageContaining("cancelled");
  }

  /** 令牌在派发前已取消：不发起任何调用即结束，服务端不可见。 */
  @Test
  void returnsCancelledWithoutDispatchingWhenTokenAlreadyCancelled() {
    McpSyncClient sdk = mock(McpSyncClient.class);
    SdkMcpClient client = newClient(sdk);
    McpCancellationToken token = new McpCancellationToken();
    token.cancel();

    assertThatThrownBy(() -> client.callTool("tool", "{}", McpDeadline.of(BUDGET), token))
        .isInstanceOf(McpCancelledException.class)
        .hasMessageContaining("cancelled");
    verify(sdk, never()).callTool(any());
  }

  /** 调用中被取消：必须迅速终止本地等待并中断在途调用，同时保持调用者线程不被外部中断。 */
  @Test
  @Timeout(30)
  void cancellationDuringInFlightCallTerminatesWaitQuickly() throws Exception {
    CountDownLatch callStarted = new CountDownLatch(1);
    CountDownLatch workerInterrupted = new CountDownLatch(1);
    McpSyncClient sdk = mock(McpSyncClient.class);
    when(sdk.callTool(any()))
        .thenAnswer(
            invocation -> {
              callStarted.countDown();
              try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(30));
              } catch (InterruptedException error) {
                workerInterrupted.countDown();
                throw new IllegalStateException("interrupted sdk call", error);
              }
              return McpSchema.CallToolResult.builder().addTextContent("late").build();
            });
    SdkMcpClient client = newClient(sdk);
    McpCancellationToken token = new McpCancellationToken();
    Thread canceller =
        new Thread(
            () -> {
              try {
                if (!callStarted.await(5, TimeUnit.SECONDS)) {
                  return;
                }
              } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
              }
              token.cancel();
            },
            "test-canceller");
    canceller.start();

    long startNanos = System.nanoTime();
    try {
      assertThatThrownBy(() -> client.callTool("tool", "{}", McpDeadline.of(BUDGET), token))
          .isInstanceOf(McpCancelledException.class)
          .hasMessageContaining("cancelled");
    } finally {
      canceller.join(TimeUnit.SECONDS.toMillis(5));
    }
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

    assertThat(elapsedMillis).as("取消必须立即结束本地等待").isLessThan(3_000L);
    assertThat(workerInterrupted.await(5, TimeUnit.SECONDS)).as("在途调用必须被中断").isTrue();
    assertThat(Thread.currentThread().isInterrupted()).as("调用者线程不应被外部中断").isFalse();
  }

  /** 调用者线程被中断：按中断报告失败并归还中断标记，绝不吞掉中断语义。 */
  @Test
  @Timeout(30)
  void interruptedCallerReportsFailureAndRestoresInterruptFlag() throws Exception {
    McpSyncClient sdk = mock(McpSyncClient.class);
    when(sdk.callTool(any()))
        .thenAnswer(
            invocation -> {
              Thread.sleep(TimeUnit.SECONDS.toMillis(30));
              return McpSchema.CallToolResult.builder().addTextContent("late").build();
            });
    SdkMcpClient client = newClient(sdk);

    try {
      Thread.currentThread().interrupt();
      assertThatThrownBy(() -> client.callTool("tool", "{}", McpDeadline.of(BUDGET), none()))
          .isInstanceOf(McpException.class)
          .hasMessage("MCP tool call interrupted");
      assertThat(Thread.currentThread().isInterrupted()).as("中断标记必须归还给调用者").isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  /** 工具规格必须映射为稳定 JSON Schema 文本；无参数 schema 退化为 {@code "{}"}，描述缺失规范化为空串。 */
  @Test
  void mapsToolSpecificationsToSchemaJsonText() {
    McpSyncClient sdk = mock(McpSyncClient.class);
    Map<String, Object> city = new LinkedHashMap<>();
    city.put("type", "string");
    city.put("description", "城市");
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("city", city);
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", properties);
    schema.put("required", List.of("city"));
    when(sdk.listTools())
        .thenReturn(
            McpSchema.ListToolsResult.builder(
                    List.of(
                        McpSchema.Tool.builder("weather", schema).description("查询天气").build(),
                        McpSchema.Tool.builder("ping", Map.of()).build()))
                .build());
    SdkMcpClient client = newClient(sdk);

    List<McpToolDefinition> tools = client.listTools(McpDeadline.of(BUDGET), none());

    assertThat(tools).hasSize(2);
    assertThat(tools.get(0).name()).isEqualTo("weather");
    assertThat(tools.get(0).description()).isEqualTo("查询天气");
    assertThat(tools.get(0).inputSchemaJson())
        .isEqualTo(
            "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\",\"description\":\"城市\"}},"
                + "\"required\":[\"city\"]}");
    assertThat(tools.get(1).name()).isEqualTo("ping");
    assertThat(tools.get(1).description()).isEmpty();
    assertThat(tools.get(1).inputSchemaJson()).isEqualTo("{}");
  }

  /** schema 无法编码为 JSON 时必须失败，绝不把非法 schema 文本交给上层。 */
  @Test
  void failsWhenToolSchemaCannotBeEncoded() {
    McpSyncClient sdk = mock(McpSyncClient.class);
    Map<String, Object> cyclicSchema = new LinkedHashMap<>();
    cyclicSchema.put("self", cyclicSchema);
    when(sdk.listTools())
        .thenReturn(
            McpSchema.ListToolsResult.builder(
                    List.of(McpSchema.Tool.builder("deep", cyclicSchema).build()))
                .build());
    SdkMcpClient client = newClient(sdk);

    assertThatThrownBy(() -> client.listTools(McpDeadline.of(BUDGET), none()))
        .isInstanceOf(McpException.class)
        .hasMessage("failed to encode MCP tool input schema");
  }

  /** SDK 结果必须完整映射：内容单元逐项保留，_meta 中的业务详情序列化为 detailsJson。 */
  @Test
  void mapsSdkResultContentsAndDetails() {
    McpSyncClient sdk = mock(McpSyncClient.class);
    McpSchema.CallToolResult result =
        McpSchema.CallToolResult.builder()
            .addContent(McpSchema.TextContent.builder("hello").build())
            .addContent(McpSchema.ImageContent.builder("AQID", "image/png").build())
            .meta(Map.of("traceId", "t-1"))
            .build();
    when(sdk.callTool(any())).thenReturn(result);
    SdkMcpClient client = newClient(sdk);

    McpToolCallResult mapped = client.callTool("tool", "{}", McpDeadline.of(BUDGET), none());

    assertThat(mapped.error()).isFalse();
    assertThat(mapped.contents())
        .containsExactly(
            new TextResultContent("hello"),
            new BinaryResultContent("image/png", new byte[] {1, 2, 3}));
    assertThat(mapped.detailsJson()).isEqualTo("{\"traceId\":\"t-1\"}");
  }

  /** 模块自有的 details 序列化走 Jackson 3：非 ASCII 直接写为 UTF-8 原文。 */
  @Test
  void serializesOwnDetailsWithJackson3RawUtf8() {
    McpSyncClient sdk = mock(McpSyncClient.class);
    when(sdk.callTool(any()))
        .thenReturn(
            McpSchema.CallToolResult.builder()
                .addTextContent("ok")
                .meta(Map.of("note", "中文😀"))
                .build());
    SdkMcpClient client = newClient(sdk);

    McpToolCallResult result = client.callTool("tool", "{}", McpDeadline.of(BUDGET), none());

    assertThat(result.detailsJson()).isEqualTo("{\"note\":\"中文😀\"}");
    assertThat(result.detailsJson())
        .as("自有 JSON 是 Jackson 3 profile：非 ASCII 不转义")
        .contains("中文😀")
        .doesNotContain("\\u");
    assertThat(result.detailsJson().getBytes(StandardCharsets.UTF_8)).hasSize(21);
  }

  /** details 无法序列化时必须回退为空 JSON object，绝不因为详情编码失败而让整个工具调用失败。 */
  @Test
  void fallsBackToEmptyDetailsWhenDetailsCannotBeEncoded() {
    Map<String, Object> cyclicMeta = new HashMap<>();
    cyclicMeta.put("self", cyclicMeta);
    McpSyncClient sdk = mock(McpSyncClient.class);
    when(sdk.callTool(any()))
        .thenReturn(
            McpSchema.CallToolResult.builder().addTextContent("ok").meta(cyclicMeta).build());
    SdkMcpClient client = newClient(sdk);

    McpToolCallResult result = client.callTool("tool", "{}", McpDeadline.of(BUDGET), none());

    assertThat(result.error()).isFalse();
    assertThat(result.detailsJson()).isEqualTo("{}");
  }

  /** 关闭失败必须可见但不回显 SDK 事实；worker 池仍先关闭，不再接收新调用。 */
  @Test
  void closeReleasesWorkersAndReportsOpaqueCloseFailure() {
    McpSyncClient sdk = mock(McpSyncClient.class);
    doThrow(new IllegalStateException("sdk close failed")).when(sdk).close();
    SdkMcpClient client = new SdkMcpClient(sdk);

    assertThatThrownBy(client::close)
        .isInstanceOf(McpException.class)
        .hasMessage("MCP client close failed")
        .hasNoCause();
    verify(sdk).close();

    assertThatThrownBy(client::close)
        .isInstanceOf(McpException.class)
        .hasMessage("MCP client close failed");
    verify(sdk, times(2)).close();

    // worker 池已被终止：关闭之后不再接受新调用，因此不会留下后台线程继续持有连接。
    // 池满与已关闭都映射为可重试 McpException：调用方拿到确定性失败，而不是被挂起。
    assertThatThrownBy(() -> client.callTool("tool", "{}", McpDeadline.of(BUDGET), none()))
        .isInstanceOf(McpException.class)
        .hasMessageContaining("closed");
  }

  /**
   * 并发上限是有界且无排队的：不响应 interrupt 的调用占满 64 个真实 worker 后，后续提交立即以可重试 {@link McpException} 拒绝而不是挂起；{@code
   * future.cancel} 不会提前释放容量；释放后 worker 线程回落到基线。
   */
  @Test
  @Timeout(60)
  void boundsConcurrentNonInterruptibleCallsToWorkerUpperBound() throws Exception {
    int baselineWorkers = workerThreadCount();
    CountDownLatch entered = new CountDownLatch(SdkMcpClient.MAX_CONCURRENT_OPERATIONS);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    McpSyncClient sdk = mock(McpSyncClient.class);
    when(sdk.callTool(any()))
        .thenAnswer(
            invocation -> {
              peak.accumulateAndGet(active.incrementAndGet(), Math::max);
              entered.countDown();
              // 模拟不响应 interrupt 的阻塞 SDK 调用：只由 release 放行。
              awaitIgnoringInterrupt(release);
              active.decrementAndGet();
              return McpSchema.CallToolResult.builder().addTextContent("late").build();
            });
    SdkMcpClient client = newClient(sdk);

    List<Thread> callers = new ArrayList<>();
    for (int index = 0; index < SdkMcpClient.MAX_CONCURRENT_OPERATIONS; index++) {
      Thread caller =
          new Thread(
              () -> {
                try {
                  client.callTool("tool", "{}", McpDeadline.of(SHORT_BUDGET), none());
                } catch (RuntimeException ignored) {
                  // 本次验证只关心容量：超时/取消/池满都按预期结束。
                }
              },
              "mcp-capacity-caller");
      callers.add(caller);
      caller.start();
    }

    assertThat(entered.await(20, TimeUnit.SECONDS)).as("64 个真实 worker 都必须进入 SDK 调用").isTrue();
    assertThat(peak.get())
        .as("真实并发不得超过 worker 上界")
        .isEqualTo(SdkMcpClient.MAX_CONCURRENT_OPERATIONS);

    long startNanos = System.nanoTime();
    assertThatThrownBy(() -> client.callTool("tool", "{}", McpDeadline.of(BUDGET), none()))
        .isInstanceOf(McpException.class)
        .hasMessageContaining("capacity");
    assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos))
        .as("池满必须立即拒绝而不是挂起")
        .isLessThan(2_000L);

    for (Thread caller : callers) {
      caller.join(TimeUnit.SECONDS.toMillis(10));
    }
    // 调用方都已超时返回，但 worker 仍被不响应 interrupt 的调用占用：容量没有因为 cancel 提前回落。
    assertThatThrownBy(() -> client.callTool("tool", "{}", McpDeadline.of(BUDGET), none()))
        .isInstanceOf(McpException.class)
        .hasMessageContaining("capacity");

    release.countDown();
    client.close();
    awaitWorkerThreadsAtMost(baselineWorkers);
  }

  /** 阻塞直到释放，期间忽略 interrupt：模拟不响应中断、无法被 cancel 提前回收的 SDK 调用。 */
  private static void awaitIgnoringInterrupt(CountDownLatch release) {
    boolean released = false;
    while (!released) {
      try {
        released = release.await(50, TimeUnit.MILLISECONDS);
      } catch (InterruptedException ignored) {
        // 不响应中断：继续占用 worker，直到显式释放。
      }
    }
  }

  private static int workerThreadCount() {
    return (int)
        Thread.getAllStackTraces().keySet().stream()
            .filter(Thread::isAlive)
            .filter(thread -> "mcp-client-worker".equals(thread.getName()))
            .count();
  }

  /** worker 线程终止是异步的：轮询到稳定状态，断言本 client 的 64 个 worker 已回落到基线。 */
  private static void awaitWorkerThreadsAtMost(int baselineWorkers) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (workerThreadCount() > baselineWorkers && System.nanoTime() < deadline) {
      try {
        Thread.sleep(20);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while waiting for worker cleanup", error);
      }
    }
    assertThat(workerThreadCount()).as("关闭后 worker 线程必须回落").isLessThanOrEqualTo(baselineWorkers);
  }

  private SdkMcpClient newClient(McpSyncClient sdk) {
    SdkMcpClient client = new SdkMcpClient(sdk);
    clients.add(client);
    return client;
  }

  private static McpCancellationToken none() {
    return McpCancellationToken.none();
  }
}
