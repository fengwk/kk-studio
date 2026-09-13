package fun.fengwk.kkstudio.harness.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.result.ResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link McpClient} 包装器的本地校验、错误映射与结果形状测试。
 *
 * <p>使用真实 stdio 子进程提供稳定环境，但只断言不依赖服务端行为的分支；服务端行为由 {@link CustomStdioMcpTransportTest} 覆盖。
 */
class LangChainMcpClientEdgeTest {

  @TempDir Path tempDir;

  private McpClient client;

  @BeforeEach
  void setUp() {
    String javaBin = ProcessHandle.current().info().command().orElse("java");
    StdioMcpConfig config =
        new StdioMcpConfig(
            List.of(
                javaBin,
                "-cp",
                System.getProperty("java.class.path"),
                TestMcpServer.class.getName()),
            tempDir.toString(),
            Map.of());
    client = McpClientFactory.createStdio(config, Duration.ofSeconds(20));
  }

  @AfterEach
  void tearDown() {
    if (client != null) {
      client.close();
    }
  }

  /** 工具名空白属于调用方参数错误，必须在发起调用前失败。 */
  @Test
  void rejectsBlankToolName() {
    assertThatThrownBy(
            () ->
                client.callTool(
                    "  ", "{}", McpDeadline.of(Duration.ofSeconds(5)), McpCancellationToken.none()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** 已取消的令牌必须在不发起请求的情况下立即以取消结束。 */
  @Test
  void alreadyCancelledTokenFailsImmediately() {
    McpCancellationToken token = new McpCancellationToken();
    token.cancel();

    assertThatThrownBy(
            () -> client.callTool("check_cwd", "{}", McpDeadline.of(Duration.ofSeconds(5)), token))
        .isInstanceOf(McpCancelledException.class);
    assertThatThrownBy(() -> client.listTools(McpDeadline.of(Duration.ofSeconds(5)), token))
        .isInstanceOf(McpCancelledException.class);
  }

  /** 已过期的 deadline 不再启动任何操作。 */
  @Test
  void expiredDeadlineFailsBeforeOperation() {
    McpDeadline expired = McpDeadline.ofDeadline(Instant.now().minusSeconds(1));
    assertThatThrownBy(() -> client.listTools(expired, McpCancellationToken.none()))
        .isInstanceOf(McpTimeoutException.class);
    assertThatThrownBy(
            () -> client.callTool("check_cwd", "{}", expired, McpCancellationToken.none()))
        .isInstanceOf(McpTimeoutException.class);
  }

  /** 工具参数必须是 JSON object；非法或非 object 文本都必须在本地失败而不发给服务端。 */
  @Test
  void rejectsNonObjectToolArguments() {
    McpDeadline deadline = McpDeadline.of(Duration.ofSeconds(5));
    for (String invalid : List.of("{not json", "[1,2]", "\"text\"", "42")) {
      assertThatThrownBy(
              () -> client.callTool("check_cwd", invalid, deadline, McpCancellationToken.none()))
          .isInstanceOf(McpException.class)
          .hasMessageContaining("JSON object");
    }
    // 空白参数规范化为空 object，而不是本地失败
    assertThat(client.callTool("check_cwd", "  ", deadline, McpCancellationToken.none()).error())
        .isFalse();
  }

  /**
   * 工具参数必须是<strong>严格</strong> JSON object：重复键与尾随 token 都必须在本地拒绝。
   *
   * <p>宽松解析会把 {@code {"a":1,"a":2}} 静默降级为「后者胜出」、把两个连续 object 当成一个参数集，让服务端按偶然实现执行未经验证的入参。
   * 错误文本必须是固定不透明字符串，绝不回显 payload 片段。
   */
  @Test
  void rejectsDuplicateKeysAndTrailingTokensInArguments() {
    McpDeadline deadline = McpDeadline.of(Duration.ofSeconds(5));
    List<String> malformed =
        List.of(
            "{\"a\":1,\"a\":2}",
            "{\"name\":\"x\",\"name\":\"y\"}",
            "{\"a\":1}{\"b\":2}",
            "{\"a\":1} trailing",
            "{\"a\":1},{\"b\":2}");

    for (String arguments : malformed) {
      McpException error =
          (McpException)
              assertThatThrownBy(
                      () ->
                          client.callTool(
                              "check_cwd", arguments, deadline, McpCancellationToken.none()))
                  .isInstanceOf(McpException.class)
                  .actual();
      // 不复述 payload：错误文本必须是固定的
      assertThat(error.getMessage()).isEqualTo("MCP tool arguments must be a strict JSON object");
      assertThat(error.getMessage()).doesNotContain("a\":1");
      assertThat(error.getCause()).isNull();
    }

    // 合法入参不受影响：正常 object 仍然可以调用成功
    assertThat(
            client
                .callTool("check_cwd", "{\"a\":1}", deadline, McpCancellationToken.none())
                .error())
        .isFalse();
  }

  /** 关闭必须幂等：重复 close 不得抛错。 */
  @Test
  void closeIsIdempotent() {
    client.close();
    client.close();
    client = null;
  }

  /** 结果模型必须拒绝非 object 的 details JSON，避免下游拿到无法判定的结构。 */
  @Test
  void resultModelRequiresObjectDetails() {
    assertThatThrownBy(() -> new McpToolCallResult(false, List.<ResultContent>of(), "[1]"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> McpToolCallResult.success(List.of(), "\"text\""))
        .isInstanceOf(IllegalArgumentException.class);

    // 空白 details 规范化为空 object，便于调用方直接解析
    assertThat(McpToolCallResult.success(List.of(), " ").detailsJson()).isEqualTo("{}");
  }

  /** 工具定义必须拒绝非法 schema，不允许把无法解析的 schema 降级为字符串传递。 */
  @Test
  void definitionModelRequiresObjectSchema() {
    assertThatThrownBy(() -> new McpToolDefinition("t", "d", "[]"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new McpToolDefinition(" ", "d", "{}"))
        .isInstanceOf(IllegalArgumentException.class);

    // 描述缺省为空串而非 null，保证下游渲染不必处理 null
    assertThat(new McpToolDefinition("t", null, "{}").description()).isEmpty();
  }

  /** 错误结果保留服务端文本与 error 标志，不被压平为通用失败。 */
  @Test
  void errorResultsPreserveServerText() {
    McpToolCallResult result =
        client.callTool(
            "unknown_tool",
            "{}",
            McpDeadline.of(Duration.ofSeconds(5)),
            McpCancellationToken.none());
    assertThat(result.error()).isTrue();
    assertThat(((TextResultContent) result.contents().getFirst()).text()).contains("unknown tool");
  }

  /** 结构化结果与 _meta 属性必须走 SDK 自身通路，而不是被自定义抽取器改写。 */
  @Test
  void handlesStructuredResultAndMetaAttributes() {
    McpDeadline deadline = McpDeadline.of(Duration.ofSeconds(5));

    McpToolCallResult structured =
        client.callTool("structured_result", "{}", deadline, McpCancellationToken.none());
    assertThat(structured.error()).isFalse();
    // 结构化结果保留为可解析 JSON 详情
    assertThat(structured.detailsJson()).isNotBlank();

    McpToolCallResult plain =
        client.callTool("text_only", "{}", deadline, McpCancellationToken.none());
    assertThat(plain.error()).isFalse();
    assertThat(((TextResultContent) plain.contents().getFirst()).text()).isEqualTo("plain only");
    // _meta 属性进入 details，不进入模型可见内容
    assertThat(plain.detailsJson()).contains("traceId");
  }

  /** 调用线程被中断时必须在本地结束等待、中止在途 request，并且绝不影响共享 client 的后续可用性。 */
  @Test
  @Timeout(30)
  void interruptedCallerEndsWaitWithoutBreakingSharedClient() throws Exception {
    McpDeadline deadline = McpDeadline.of(Duration.ofSeconds(20));
    AtomicReference<Throwable> failure = new AtomicReference<>();
    CountDownLatch started = new CountDownLatch(1);
    Thread caller =
        new Thread(
            () -> {
              started.countDown();
              try {
                client.callTool(
                    "sleep_delay", "{\"millis\":15000}", deadline, McpCancellationToken.none());
              } catch (RuntimeException error) {
                failure.set(error);
              }
            },
            "interrupted-mcp-caller");
    caller.start();
    assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
    // 等到慢调用确实在服务端执行后再中断调用线程
    awaitServerBusy();
    caller.interrupt();
    caller.join(TimeUnit.SECONDS.toMillis(10));
    assertThat(caller.isAlive()).isFalse();

    assertThat(failure.get()).isInstanceOf(McpException.class);
    assertThat(caller.isInterrupted()).isTrue();

    // 中断只结束本次等待：共享 client 之后仍可正常服务
    McpToolCallResult afterInterrupt =
        client.callTool(
            "check_cwd", "{}", McpDeadline.of(Duration.ofSeconds(5)), McpCancellationToken.none());
    assertThat(afterInterrupt.error()).isFalse();
  }

  /** 等到服务端确实在处理慢调用（用同连接探测确认连接仍可并发服务）。 */
  private void awaitServerBusy() {
    long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < limit) {
      if (!client
          .callTool(
              "check_cwd", "{}", McpDeadline.of(Duration.ofSeconds(5)), McpCancellationToken.none())
          .error()) {
        return;
      }
      try {
        Thread.sleep(10);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while waiting", error);
      }
    }
    throw new AssertionError("server did not start handling the slow call");
  }

  /** 关闭之后再次调用必须失败（而不是静默成功或挂起）。 */
  @Test
  void rejectsOperationsAfterClose() {
    client.close();
    assertThatThrownBy(
            () ->
                client.listTools(
                    McpDeadline.of(Duration.ofSeconds(5)), McpCancellationToken.none()))
        .isInstanceOf(RuntimeException.class);
    client = null;
  }
}
