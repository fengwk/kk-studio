package fun.fengwk.kkstudio.harness.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.exception.ToolExecutionException;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * {@link LangChainMcpClient} 的调用级语义测试。
 *
 * <p>{@link RemoteMcpClientTest} 用真实 Streamable HTTP 服务覆盖正常链路；本测试用 SDK
 * 替身隔离底层阻塞调用，覆盖真实服务端难以稳定构造的路径：工具错误结果透传、本地入参校验、超时/取消/中断的收敛、结果内容与详情映射，以及关闭时的清理与异常隔离。
 *
 * <p>测试意图是锁定「失败分类」与「绝不泄漏底层事实」这两条对外契约，而不仅是执行到代码。
 */
class LangChainMcpClientTest {

  private static final Duration SHORT_BUDGET = Duration.ofMillis(200);
  private static final Duration BUDGET = Duration.ofSeconds(5);

  private final List<LangChainMcpClient> clients = new ArrayList<>();

  @AfterEach
  void closeClients() {
    for (LangChainMcpClient client : clients) {
      client.close();
    }
    clients.clear();
  }

  /** name 为空必须在本地拒绝，且绝不触碰 SDK（否则会把畸形请求发到服务端）。 */
  @Test
  void rejectsBlankToolNameBeforeTouchingSdk() {
    DefaultMcpClient sdk = mock(DefaultMcpClient.class);
    LangChainMcpClient client = newClient(sdk, null);

    assertThatThrownBy(() -> client.callTool("  ", "{}", McpDeadline.of(BUDGET), none()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("name");
    verify(sdk, never()).executeTool(any(ToolExecutionRequest.class));
  }

  /** 入参必须是严格 JSON object：非 object、重复键与尾随 token 都在本地拒绝，且错误文本不回显 payload。 */
  @Test
  void rejectsNonStrictJsonArgumentsLocallyWithoutEchoingPayload() {
    DefaultMcpClient sdk = mock(DefaultMcpClient.class);
    LangChainMcpClient client = newClient(sdk, null);

    for (String malformed :
        List.of("not json", "[1]", "{} {}", "{\"secret_key\":1,\"secret_key\":2}")) {
      assertThatThrownBy(() -> client.callTool("tool", malformed, McpDeadline.of(BUDGET), none()))
          .as("畸形入参必须被本地拒绝：%s", malformed)
          .isInstanceOf(McpException.class)
          .hasMessage("MCP tool arguments must be a strict JSON object")
          .hasNoCause();
    }
    verify(sdk, never()).executeTool(any(ToolExecutionRequest.class));
  }

  /** 空入参沿用「缺省为空对象」的既有语义：规范化后仍必须是 object，而不是被当成畸形输入。 */
  @Test
  void normalizesBlankArgumentsToEmptyJsonObject() {
    DefaultMcpClient sdk = mock(DefaultMcpClient.class);
    when(sdk.executeTool(any(ToolExecutionRequest.class)))
        .thenReturn(ToolExecutionResult.builder().resultText("ok").build());
    LangChainMcpClient client = newClient(sdk, null);

    for (String blank : List.of("", "   ")) {
      assertThat(client.callTool("tool", blank, McpDeadline.of(BUDGET), none()).error()).isFalse();
    }
    verify(sdk, times(2)).executeTool(any(ToolExecutionRequest.class));
  }

  /** 工具以错误结束时 SDK 抛异常而非返回结果：必须映射为 error 结果透传工具输出，而不是上抛为连接失败。 */
  @Test
  void mapsToolExecutionExceptionToErrorResult() {
    DefaultMcpClient sdk = mock(DefaultMcpClient.class);
    when(sdk.executeTool(any(ToolExecutionRequest.class)))
        .thenThrow(new ToolExecutionException("tool exploded"));
    LangChainMcpClient client = newClient(sdk, null);

    McpToolCallResult result = client.callTool("tool", "{}", McpDeadline.of(BUDGET), none());

    assertThat(result.error()).isTrue();
    assertThat(result.contents()).containsExactly(new TextResultContent("tool exploded"));
    assertThat(result.detailsJson()).isEqualTo("{}");
  }

  /** SDK 异常没有可读文本时必须给出固定兜底文案，绝不把 null 或空白当成错误内容暴露。 */
  @Test
  void mapsMessageLessToolExecutionExceptionToCanonicalFallback() {
    DefaultMcpClient sdk = mock(DefaultMcpClient.class);
    when(sdk.executeTool(any(ToolExecutionRequest.class)))
        .thenThrow(new ToolExecutionException((String) null))
        .thenThrow(new ToolExecutionException("   "));
    LangChainMcpClient client = newClient(sdk, null);

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
    DefaultMcpClient sdk = mock(DefaultMcpClient.class);
    when(sdk.executeTool(any(ToolExecutionRequest.class)))
        .thenThrow(new IllegalStateException("token=super-secret"));
    when(sdk.listTools()).thenThrow(new IllegalStateException("host=internal.local"));
    LangChainMcpClient client = newClient(sdk, null);

    assertThatThrownBy(() -> client.callTool("tool", "{}", McpDeadline.of(BUDGET), none()))
        .isInstanceOf(McpException.class)
        .hasMessage("MCP tool call failed")
        .hasNoCause()
        .satisfies(error -> assertThat(error.getMessage()).doesNotContain("super-secret"));
    assertThatThrownBy(() -> client.listTools(McpDeadline.of(BUDGET), none()))
        .isInstanceOf(McpException.class)
        .hasMessage("MCP listTools failed");
  }

  /** 预算耗尽必须迅速结束本地等待，并真正中断仍在执行的 SDK 调用，避免后台线程继续占用连接。 */
  @Test
  @Timeout(30)
  void timesOutAndInterruptsInFlightSdkCall() throws Exception {
    CountDownLatch callStarted = new CountDownLatch(1);
    CountDownLatch workerInterrupted = new CountDownLatch(1);
    DefaultMcpClient sdk = mock(DefaultMcpClient.class);
    when(sdk.executeTool(any(ToolExecutionRequest.class)))
        .thenAnswer(
            invocation -> {
              callStarted.countDown();
              try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(30));
              } catch (InterruptedException error) {
                workerInterrupted.countDown();
                throw new IllegalStateException("interrupted sdk call", error);
              }
              return ToolExecutionResult.builder().resultText("late").build();
            });
    LangChainMcpClient client = newClient(sdk, null);

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
    DefaultMcpClient sdk = mock(DefaultMcpClient.class);
    when(sdk.executeTool(any(ToolExecutionRequest.class)))
        .thenThrow(new CancellationException("cancelled by sdk"));
    LangChainMcpClient client = newClient(sdk, null);

    assertThatThrownBy(() -> client.callTool("tool", "{}", McpDeadline.of(BUDGET), none()))
        .isInstanceOf(McpCancelledException.class)
        .hasMessageContaining("cancelled");
  }

  /** 令牌在派发前已取消：不发起任何调用即结束，服务端不可见。 */
  @Test
  void returnsCancelledWithoutDispatchingWhenTokenAlreadyCancelled() {
    DefaultMcpClient sdk = mock(DefaultMcpClient.class);
    LangChainMcpClient client = newClient(sdk, null);
    McpCancellationToken token = new McpCancellationToken();
    token.cancel();

    assertThatThrownBy(() -> client.callTool("tool", "{}", McpDeadline.of(BUDGET), token))
        .isInstanceOf(McpCancelledException.class)
        .hasMessageContaining("cancelled");
    verify(sdk, never()).executeTool(any(ToolExecutionRequest.class));
  }

  /** 调用中被取消：必须迅速终止本地等待并中断在途调用，同时保持调用者线程不被外部中断。 */
  @Test
  @Timeout(30)
  void cancellationDuringInFlightCallTerminatesWaitQuickly() throws Exception {
    CountDownLatch callStarted = new CountDownLatch(1);
    CountDownLatch workerInterrupted = new CountDownLatch(1);
    DefaultMcpClient sdk = mock(DefaultMcpClient.class);
    when(sdk.executeTool(any(ToolExecutionRequest.class)))
        .thenAnswer(
            invocation -> {
              callStarted.countDown();
              try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(30));
              } catch (InterruptedException error) {
                workerInterrupted.countDown();
                throw new IllegalStateException("interrupted sdk call", error);
              }
              return ToolExecutionResult.builder().resultText("late").build();
            });
    LangChainMcpClient client = newClient(sdk, null);
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
    DefaultMcpClient sdk = mock(DefaultMcpClient.class);
    when(sdk.executeTool(any(ToolExecutionRequest.class)))
        .thenAnswer(
            invocation -> {
              Thread.sleep(TimeUnit.SECONDS.toMillis(30));
              return ToolExecutionResult.builder().resultText("late").build();
            });
    LangChainMcpClient client = newClient(sdk, null);

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
    DefaultMcpClient sdk = mock(DefaultMcpClient.class);
    JsonObjectSchema schema =
        JsonObjectSchema.builder().addStringProperty("city", "城市").required("city").build();
    when(sdk.listTools())
        .thenReturn(
            List.of(
                ToolSpecification.builder()
                    .name("weather")
                    .description("查询天气")
                    .parameters(schema)
                    .build(),
                ToolSpecification.builder().name("ping").build()));
    LangChainMcpClient client = newClient(sdk, null);

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
    DefaultMcpClient sdk = mock(DefaultMcpClient.class);
    // 超过 Jackson 默认嵌套深度上限的 schema 在编码时失败：用于锁定「编码失败即 McpException」的兜底语义。
    // 深度取 600：既稳定超过编码器的嵌套上限（约 500 层即触发），又远低于 SDK 自身递归转 Map 的栈深度，
    // 因此命中的是「编码抛 JsonProcessingException」而不是 StackOverflowError。
    JsonObjectSchema schema = JsonObjectSchema.builder().addStringProperty("leaf").build();
    for (int depth = 0; depth < 600; depth++) {
      schema = JsonObjectSchema.builder().addProperty("n" + depth, schema).build();
    }
    when(sdk.listTools())
        .thenReturn(List.of(ToolSpecification.builder().name("deep").parameters(schema).build()));
    LangChainMcpClient client = newClient(sdk, null);

    assertThatThrownBy(() -> client.listTools(McpDeadline.of(BUDGET), none()))
        .isInstanceOf(McpException.class)
        .hasMessage("failed to encode MCP tool input schema");
  }

  /** SDK 结果必须完整映射：内容单元逐项保留，attributes 序列化为 detailsJson。 */
  @Test
  void mapsSdkResultContentsAndAttributes() {
    DefaultMcpClient sdk = mock(DefaultMcpClient.class);
    when(sdk.executeTool(any(ToolExecutionRequest.class)))
        .thenReturn(
            ToolExecutionResult.builder()
                .isError(false)
                .result(List.of(new TextResultContent("hello"), new JsonResultContent("{\"k\":1}")))
                .resultText("hello")
                .attributes(Map.of("traceId", "t-1"))
                .build());
    LangChainMcpClient client = newClient(sdk, null);

    McpToolCallResult result = client.callTool("tool", "{}", McpDeadline.of(BUDGET), none());

    assertThat(result.error()).isFalse();
    assertThat(result.contents())
        .containsExactly(new TextResultContent("hello"), new JsonResultContent("{\"k\":1}"));
    assertThat(result.detailsJson()).isEqualTo("{\"traceId\":\"t-1\"}");
  }

  /** 结果不可按内容单元投影时必须退化到 resultText，且空文本同样给出显式空内容而不是 null。 */
  @Test
  void fallsBackToResultTextWhenContentsAreNotProjectable() {
    DefaultMcpClient sdk = mock(DefaultMcpClient.class);
    when(sdk.executeTool(any(ToolExecutionRequest.class)))
        .thenReturn(
            ToolExecutionResult.builder().result("raw-text").resultText("plain text").build(),
            ToolExecutionResult.builder().result("raw-text").resultText("").build(),
            ToolExecutionResult.builder()
                .result(List.of("not-a-result-content"))
                .resultText("fallback")
                .build());
    LangChainMcpClient client = newClient(sdk, null);

    assertThat(client.callTool("tool", "{}", McpDeadline.of(BUDGET), none()).contents())
        .containsExactly(new TextResultContent("plain text"));
    assertThat(client.callTool("tool", "{}", McpDeadline.of(BUDGET), none()).contents())
        .containsExactly(new TextResultContent(""));
    assertThat(client.callTool("tool", "{}", McpDeadline.of(BUDGET), none()).contents())
        .as("无法投影的内容绝不能静默丢失文本")
        .containsExactly(new TextResultContent("fallback"));
  }

  /** attributes 无法序列化时必须回退为空 JSON object，绝不因为详情编码失败而让整个工具调用失败。 */
  @Test
  void fallsBackToEmptyDetailsWhenAttributesCannotBeEncoded() {
    Map<String, Object> cyclicAttributes = new HashMap<>();
    cyclicAttributes.put("self", cyclicAttributes);
    DefaultMcpClient sdk = mock(DefaultMcpClient.class);
    when(sdk.executeTool(any(ToolExecutionRequest.class)))
        .thenReturn(
            ToolExecutionResult.builder()
                .isError(true)
                .result("raw")
                .resultText("failed")
                .attributes(cyclicAttributes)
                .build());
    LangChainMcpClient client = newClient(sdk, null);

    McpToolCallResult result = client.callTool("tool", "{}", McpDeadline.of(BUDGET), none());

    assertThat(result.error()).isTrue();
    assertThat(result.detailsJson()).isEqualTo("{}");
  }

  /** 关闭必须释放 worker 池并关闭 SDK 与传输，且任一关闭失败都不得外抛或阻断后续清理。 */
  @Test
  void closeReleasesWorkersAndSwallowsCloseFailures() throws IOException {
    DefaultMcpClient sdk = mock(DefaultMcpClient.class);
    McpTransport transport = mock(McpTransport.class);
    doThrow(new IllegalStateException("sdk close failed")).when(sdk).close();
    doThrow(new IOException("transport close failed")).when(transport).close();
    LangChainMcpClient client = new LangChainMcpClient(sdk, transport);
    clients.add(client);

    assertThatCode(client::close).doesNotThrowAnyException();
    verify(sdk).close();
    verify(transport).close();

    // 幂等：重复关闭仍然逐个收敛，且不会把异常带给调用方
    assertThatCode(client::close).doesNotThrowAnyException();
    verify(sdk, times(2)).close();
    verify(transport, times(2)).close();

    // worker 池已被终止：关闭之后不再接受新调用，因此不会留下后台线程继续持有连接
    assertThatThrownBy(() -> client.callTool("tool", "{}", McpDeadline.of(BUDGET), none()))
        .isInstanceOf(RejectedExecutionException.class);
  }

  /** 无传输的 client（无外部传输所有权）也必须能安全关闭，不因缺少传输而失败。 */
  @Test
  void closeToleratesMissingTransport() {
    DefaultMcpClient sdk = mock(DefaultMcpClient.class);
    LangChainMcpClient client = newClient(sdk, null);

    assertThatCode(client::close).doesNotThrowAnyException();
    verify(sdk).close();
  }

  private LangChainMcpClient newClient(DefaultMcpClient sdk, McpTransport transport) {
    LangChainMcpClient client = new LangChainMcpClient(sdk, transport);
    clients.add(client);
    return client;
  }

  private static McpCancellationToken none() {
    return McpCancellationToken.none();
  }
}
