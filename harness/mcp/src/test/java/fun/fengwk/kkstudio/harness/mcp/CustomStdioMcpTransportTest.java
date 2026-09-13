package fun.fengwk.kkstudio.harness.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.protocol.McpPingRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import fun.fengwk.kkstudio.harness.common.result.BinaryResultContent;
import fun.fengwk.kkstudio.harness.common.result.JsonResultContent;
import fun.fengwk.kkstudio.harness.common.result.TextResultContent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 定制 Stdio MCP 传输真实子进程契约测试：
 *
 * <ul>
 *   <li>验证子进程真实 pwd 等于配置 cwd、覆盖环境生效、多模态结构完整保留；
 *   <li>验证 close 后子进程退出且无进程泄漏；
 *   <li>验证单一总预算 deadline 覆盖初始化与调用；
 *   <li>验证取消一个在途调用或发现会到达协议层（notifications/cancelled），且共享 client 上其它调用保持成功。
 * </ul>
 */
class CustomStdioMcpTransportTest {

  @TempDir Path tempDir;

  private List<String> buildJavaCommand() {
    String javaBin = ProcessHandle.current().info().command().orElse("java");
    String cp = System.getProperty("java.class.path");
    return List.of(javaBin, "-cp", cp, TestMcpServer.class.getName());
  }

  /** 真实启动子进程，验证 cwd、环境变量与多模态结果，并验证关闭后子进程退出。 */
  @Test
  void executesRealProcessWithExplicitCwdAndEnv() throws Exception {
    Path targetCwd = Files.createDirectories(tempDir.resolve("my-mcp-workspace")).toRealPath();
    StdioMcpConfig config =
        new StdioMcpConfig(
            buildJavaCommand(),
            targetCwd.toString(),
            Map.of("TEST_VAR_NAME", "super_secret_environment_value"));

    McpClient client = McpClientFactory.createStdio(config, Duration.ofSeconds(10));
    try {
      List<McpToolDefinition> tools = client.listTools(deadline(), McpCancellationToken.none());
      assertThat(tools)
          .extracting(McpToolDefinition::name)
          .contains("check_cwd", "check_env", "rich_result", "sleep_delay", "duplicate_tool");

      McpToolCallResult cwdResult =
          client.callTool("check_cwd", "{}", deadline(), McpCancellationToken.none());
      assertThat(cwdResult.error()).isFalse();
      assertThat(cwdResult.contents().getFirst()).isInstanceOf(TextResultContent.class);
      assertThat(((TextResultContent) cwdResult.contents().getFirst()).text())
          .isEqualTo(targetCwd.toString());

      McpToolCallResult envResult =
          client.callTool(
              "check_env", "{\"name\":\"TEST_VAR_NAME\"}", deadline(), McpCancellationToken.none());
      assertThat(envResult.error()).isFalse();
      assertThat(((TextResultContent) envResult.contents().getFirst()).text())
          .isEqualTo("super_secret_environment_value");

      McpToolCallResult richResult =
          client.callTool("rich_result", "{}", deadline(), McpCancellationToken.none());
      assertThat(richResult.error()).isFalse();
      assertThat(richResult.contents()).hasSize(3);
      assertThat(richResult.contents().get(0)).isInstanceOf(TextResultContent.class);
      assertThat(richResult.contents().get(1)).isInstanceOf(BinaryResultContent.class);
      assertThat(richResult.contents().get(2)).isInstanceOf(JsonResultContent.class);
    } finally {
      client.close();
    }
  }

  /** 验证总预算语义：deadline 小于调用耗时时必须抛出超时异常，且共享 client 之后仍可继续服务。 */
  @Test
  void enforcesTotalDeadlineAcrossOperations() {
    StdioMcpConfig config = new StdioMcpConfig(buildJavaCommand(), tempDir.toString(), Map.of());
    try (McpClient client = McpClientFactory.createStdio(config, Duration.ofSeconds(10))) {
      assertThatThrownBy(
              () ->
                  client.callTool(
                      "sleep_delay",
                      "{\"millis\":2000}",
                      McpDeadline.of(Duration.ofMillis(200)),
                      McpCancellationToken.none()))
          .isInstanceOf(McpTimeoutException.class)
          .hasMessageContaining("timed out");

      // 超时只中止本次在途请求，连接本身仍可复用
      McpToolCallResult afterTimeout =
          client.callTool("sleep_delay", "{\"millis\":1}", deadline(), McpCancellationToken.none());
      assertThat(afterTimeout.error()).isFalse();
    }
  }

  /** 验证单一总预算覆盖初始化：服务端初始化握手本身耗时超过预算时，调用必须在预算内失败， 而不是「初始化重新获得一份新预算」后再等到调用阶段才超时。 */
  @Test
  void enforcesSingleBudgetCoveringInitialization() {
    String javaBin = ProcessHandle.current().info().command().orElse("java");
    String cp = System.getProperty("java.class.path");
    StdioMcpConfig config =
        new StdioMcpConfig(
            List.of(
                javaBin,
                "-Dtest.mcp.initDelayMillis=1500",
                "-cp",
                cp,
                TestMcpServer.class.getName()),
            tempDir.toString(),
            Map.of());

    long startNanos = System.nanoTime();
    assertThatThrownBy(
            () -> McpClientFactory.createStdio(config, McpDeadline.of(Duration.ofMillis(500))))
        .isInstanceOf(McpTimeoutException.class);
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    // 初始化自身等待超过 500ms 预算即失败；若初始化另外获得独立预算，耗时将显著超出该上界。
    assertThat(elapsedMillis).isLessThan(4_000L);
  }

  /** 验证命令不存在时启动失败抛出固定不透明异常且资源被安全清理。 */
  @Test
  void handlesInvalidCommandGracefully() {
    StdioMcpConfig config =
        new StdioMcpConfig(List.of("non_existent_executable_12345"), tempDir.toString(), Map.of());
    assertThatThrownBy(() -> McpClientFactory.createStdio(config, Duration.ofSeconds(5)))
        .isInstanceOf(McpException.class);
  }

  /**
   * 取消一个在途调用必须：结束本地等待、把 {@code notifications/cancelled} 送达服务端（request id 精确对应）， 并且同一共享 client
   * 上的并发调用继续正常成功。
   */
  @Test
  @Timeout(30)
  void cancellationAbortsOnlyTargetRequestAndKeepsSharedClientHealthy() throws Exception {
    StdioMcpConfig config = new StdioMcpConfig(buildJavaCommand(), tempDir.toString(), Map.of());
    try (McpClient client = McpClientFactory.createStdio(config, Duration.ofSeconds(20))) {
      // 预热：确保子进程握手完成，使后续取消只针对工具调用这一阶段
      client.callTool("check_cwd", "{}", deadline(), McpCancellationToken.none());

      McpCancellationToken token = new McpCancellationToken();
      ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
      AtomicReference<Throwable> cancelledFailure = new AtomicReference<>();
      CountDownLatch slowStarted = new CountDownLatch(1);
      try {
        Future<?> slowCall =
            executor.submit(
                () -> {
                  slowStarted.countDown();
                  try {
                    client.callTool("sleep_delay", "{\"millis\":15000}", deadline(), token);
                  } catch (RuntimeException error) {
                    cancelledFailure.set(error);
                  }
                });
        assertThat(slowStarted.await(5, TimeUnit.SECONDS)).isTrue();
        // 等待在途请求真正发出（服务端开始处理即代表请求已到达）
        awaitCallStarted(client);

        // 同一 client 上的并发调用：取消另一个请求不得破坏它
        McpToolCallResult concurrent =
            client.callTool(
                "check_cwd",
                "{}",
                McpDeadline.of(Duration.ofSeconds(5)),
                McpCancellationToken.none());
        assertThat(concurrent.error()).isFalse();

        token.cancel();
        slowCall.get(10, TimeUnit.SECONDS);

        assertThat(cancelledFailure.get()).isInstanceOf(McpCancelledException.class);

        // 协议级证据：服务端确实收到了针对该 request id 的 notifications/cancelled
        assertThat(awaitCancelledRequestIds(client))
            .as("cancellation must reach the MCP server as notifications/cancelled")
            .isNotEqualTo("[]");
      } finally {
        executor.shutdownNow();
      }

      // 共享 client 仍然健康：同一 client 上的后续调用继续成功
      McpToolCallResult afterCancel =
          client.callTool("check_cwd", "{}", deadline(), McpCancellationToken.none());
      assertThat(afterCancel.error()).isFalse();
    }
  }

  /** 发现操作同样必须在传输派发处绑定真实 request id；取消不得遗留 pending 请求或关闭共享 client。 */
  @Test
  @Timeout(30)
  void cancellationAbortsToolDiscoveryAndKeepsSharedClientHealthy() throws Exception {
    String javaBin = ProcessHandle.current().info().command().orElse("java");
    String cp = System.getProperty("java.class.path");
    StdioMcpConfig config =
        new StdioMcpConfig(
            List.of(
                javaBin,
                "-Dtest.mcp.listDelayMillis=15000",
                "-cp",
                cp,
                TestMcpServer.class.getName()),
            tempDir.toString(),
            Map.of());

    try (McpClient client = McpClientFactory.createStdio(config, Duration.ofSeconds(20))) {
      McpCancellationToken token = new McpCancellationToken();
      ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
      AtomicReference<Throwable> cancelledFailure = new AtomicReference<>();
      try {
        Future<?> discovery =
            executor.submit(
                () -> {
                  try {
                    client.listTools(McpDeadline.of(Duration.ofSeconds(20)), token);
                  } catch (RuntimeException error) {
                    cancelledFailure.set(error);
                  }
                });

        awaitListStarted(client);
        token.cancel();
        discovery.get(10, TimeUnit.SECONDS);

        assertThat(cancelledFailure.get()).isInstanceOf(McpCancelledException.class);
        assertThat(awaitCancelledRequestIds(client))
            .as("tools/list cancellation must reach the server with its real request id")
            .isNotEqualTo("[]");
      } finally {
        executor.shutdownNow();
      }

      McpToolCallResult afterCancel =
          client.callTool("check_cwd", "{}", deadline(), McpCancellationToken.none());
      assertThat(afterCancel.error()).isFalse();
    }
  }

  /** 传输层的健康检查、取消声明与进程观测点必须与真实子进程状态一致： 运行中健康、关闭后不健康，且声明需要协议取消通知。 */
  @Test
  void reportsHealthAndProcessState() {
    StdioMcpConfig config = new StdioMcpConfig(buildJavaCommand(), tempDir.toString(), Map.of());
    CustomStdioMcpTransport transport =
        new CustomStdioMcpTransport(
            config.command(), config.cwd(), config.env(), allowAllDispatches());
    assertThat(transport.requiresCancellationNotification()).isTrue();
    assertThat(transport.getProcess()).isNull();
    // 启动前健康检查必须失败，而不是假装可用
    assertThatThrownBy(transport::checkHealth).isInstanceOf(IllegalStateException.class);

    DevNullOperationHandler handler = new DevNullOperationHandler();
    transport.start(handler);
    Process started = transport.getProcess();
    assertThat(started).isNotNull();
    assertThat(started.isAlive()).isTrue();
    transport.checkHealth();
    assertThat(transport.pendingRequestCount()).isZero();

    // 未知 request id 无法中止：必须报 false，且不发送任何协议消息
    assertThat(transport.abort(999_999L, "no such request")).isFalse();

    transport.close();
    // close 后进程必须被回收，健康检查随之失败
    assertThat(transport.getProcess()).isNull();
    assertThat(started.isAlive()).isFalse();
    assertThatThrownBy(transport::checkHealth).isInstanceOf(IllegalStateException.class);
  }

  /** {@link McpCallContext} 重载必须与直接消息重载等价：SDK 的监听回调路径使用前者。 */
  @Test
  void executesThroughCallContextOverloads() {
    StdioMcpConfig config = new StdioMcpConfig(buildJavaCommand(), tempDir.toString(), Map.of());
    CustomStdioMcpTransport transport =
        new CustomStdioMcpTransport(
            config.command(), config.cwd(), config.env(), allowAllDispatches());
    try {
      transport.start(new DevNullOperationHandler());
      McpCallContext context = new McpCallContext(null, new McpPingRequest(4242L));

      // 无响应重载：只发送，不登记在途请求
      transport.executeOperationWithoutResponse(context);
      assertThat(transport.pendingRequestCount()).isZero();

      // 有响应重载：登记该 request id，随后可由 abort 精确结束
      CompletableFuture<JsonNode> pending = transport.executeOperationWithResponse(context);
      assertThat(transport.pendingRequestCount()).isEqualTo(1);
      assertThat(transport.abort(4242L, "test abort")).isTrue();
      assertThat(pending.isCompletedExceptionally()).isTrue();
      assertThat(transport.pendingRequestCount()).isZero();
    } finally {
      transport.close();
    }
  }

  /** start 会替换既有进程：旧进程必须被终止，避免重复启动泄漏子进程。 */
  @Test
  void restartReplacesExistingProcess() {
    StdioMcpConfig config = new StdioMcpConfig(buildJavaCommand(), tempDir.toString(), Map.of());
    CustomStdioMcpTransport transport =
        new CustomStdioMcpTransport(
            config.command(), config.cwd(), config.env(), allowAllDispatches());
    try {
      DevNullOperationHandler handler = new DevNullOperationHandler();
      transport.start(handler);
      long firstPid = transport.getProcess().pid();

      transport.start(handler);
      long secondPid = transport.getProcess().pid();
      assertThat(secondPid).isNotEqualTo(firstPid);
      assertThat(ProcessHandle.of(firstPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    } finally {
      transport.close();
    }
  }

  /** 代际隔离测试：旧代进程退出绝不得 fail 新世代的在途请求，也绝不得触发 onFailureCallback。 */
  @Test
  @Timeout(30)
  void oldProcessExitDoesNotFailNewGenerationPendingRequestsOrTriggerCallback() throws Exception {
    StdioMcpConfig config = new StdioMcpConfig(buildJavaCommand(), tempDir.toString(), Map.of());
    CustomStdioMcpTransport transport =
        new CustomStdioMcpTransport(
            config.command(), config.cwd(), config.env(), allowAllDispatches());

    AtomicInteger failureCallbacks = new AtomicInteger(0);
    transport.onFailure(failureCallbacks::incrementAndGet);

    try {
      DevNullOperationHandler handler1 = new DevNullOperationHandler();
      transport.start(handler1);
      Process firstProcess = transport.getProcess();
      assertThat(firstProcess).isNotNull();

      // 重启进入第二代：第一代进程被 stop() 并退出
      DevNullOperationHandler handler2 = new DevNullOperationHandler();
      transport.start(handler2);
      Process secondProcess = transport.getProcess();
      assertThat(secondProcess).isNotNull().isNotSameAs(firstProcess);

      // 在第二代上发起一个在途请求
      McpCallContext context = new McpCallContext(null, new McpPingRequest(8888L));
      CompletableFuture<JsonNode> secondGenPending =
          transport.executeOperationWithResponse(context);
      assertThat(transport.pendingRequestCount()).isEqualTo(1);
      assertThat(secondGenPending.isDone()).isFalse();

      // 等待第一代进程退出完全结束（触发其 onExit 逻辑）
      firstProcess.onExit().get(5, TimeUnit.SECONDS);

      // 验证：第一代进程的退出绝不能把第二代的在途请求标记失败，也不能触发 failure callback
      assertThat(secondGenPending.isDone()).as("第一代进程退出不得影响第二代的在途请求").isFalse();
      assertThat(failureCallbacks.get()).as("第一代进程预期退出不得触发 failure callback").isZero();
      assertThat(transport.pendingRequestCount()).isEqualTo(1);

      // 验证第二代在途请求仍可正常中止
      assertThat(transport.abort(8888L, "cancel gen2")).isTrue();
      assertThat(secondGenPending.isCompletedExceptionally()).isTrue();
      assertThat(transport.pendingRequestCount()).isZero();
    } finally {
      transport.close();
    }
  }

  /**
   * 关闭必须幂等且不可逆：重复 close 与 close 后的 restart 都不得重新拉起子进程。
   *
   * <p>工厂在超时路径会由「放弃方」和「兜底清理」两次关闭同一传输，因此这里断言第二次关闭既不抛错也不会复活进程。
   */
  @Test
  void closeIsStickyAndNeverRespawns() {
    StdioMcpConfig config = new StdioMcpConfig(buildJavaCommand(), tempDir.toString(), Map.of());
    CustomStdioMcpTransport transport =
        new CustomStdioMcpTransport(
            config.command(), config.cwd(), config.env(), allowAllDispatches());
    DevNullOperationHandler handler = new DevNullOperationHandler();
    transport.start(handler);
    long pid = transport.getProcess().pid();

    transport.close();
    assertThat(transport.isClosed()).isTrue();
    // 重复关闭：幂等，不得抛错
    transport.close();
    transport.close();

    // 关闭后 start 必须 fail closed，绝不复活子进程
    assertThatThrownBy(() -> transport.start(handler)).isInstanceOf(McpException.class);
    assertThat(transport.getProcess()).isNull();
    assertThat(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)).isFalse();

    // 关闭后的请求必须以「已失败的 future」显式结束，而不是挂起等待一个永不到来的响应
    CompletableFuture<JsonNode> rejected =
        transport.executeOperationWithResponse(new McpPingRequest(1L));
    assertThat(rejected).isCompletedExceptionally();
    assertThat(transport.pendingRequestCount()).isZero();
  }

  /** 子进程意外退出（非主动 close 且非 restart 替换）时： 必须清理当前代的在途请求，通知 messageHandler，并触发 onFailureCallback。 */
  @Test
  @Timeout(30)
  void unexpectedProcessExitFailsPendingAndTriggersFailureCallback() throws Exception {
    StdioMcpConfig config = new StdioMcpConfig(buildJavaCommand(), tempDir.toString(), Map.of());
    CustomStdioMcpTransport transport =
        new CustomStdioMcpTransport(
            config.command(), config.cwd(), config.env(), allowAllDispatches());

    AtomicInteger failureCallbacks = new AtomicInteger(0);
    transport.onFailure(failureCallbacks::incrementAndGet);

    DevNullOperationHandler handler = new DevNullOperationHandler();
    transport.start(handler);
    Process p = transport.getProcess();
    assertThat(p).isNotNull();

    McpCallContext context = new McpCallContext(null, new McpPingRequest(9999L));
    CompletableFuture<JsonNode> pending = transport.executeOperationWithResponse(context);
    assertThat(transport.pendingRequestCount()).isEqualTo(1);

    // 强制终止子进程，模拟进程异常崩溃
    p.destroyForcibly();
    p.onExit().get(5, TimeUnit.SECONDS);

    // 短暂等待 onExit 回调执行完毕
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (failureCallbacks.get() == 0 && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }

    assertThat(failureCallbacks.get()).as("进程意外退出必须触发 failure callback").isEqualTo(1);
    assertThat(pending.isCompletedExceptionally()).as("在途请求必须失败").isTrue();
    assertThat(transport.pendingRequestCount()).isZero();
    assertThat(transport.getProcess()).isNull();
  }

  /** 传输启动前发送请求必须立即返回失败 future。 */
  @Test
  void executeBeforeStartFailsImmediately() {
    StdioMcpConfig config = new StdioMcpConfig(buildJavaCommand(), tempDir.toString(), Map.of());
    CustomStdioMcpTransport transport =
        new CustomStdioMcpTransport(
            config.command(), config.cwd(), config.env(), allowAllDispatches());
    CompletableFuture<JsonNode> future =
        transport.executeOperationWithResponse(new McpPingRequest(1L));
    assertThat(future).isCompletedExceptionally();
  }

  /**
   * 等待「慢调用确实仍在服务端执行」：通过同一连接探测服务端是否已开始处理该请求。
   *
   * <p>不依赖固定 sleep：慢调用开始后，同连接上的探测调用仍会正常返回，因此循环直到探测成功即可确定在途状态已建立。
   */
  private static void awaitCallStarted(McpClient client) {
    long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < waitDeadline) {
      McpToolCallResult probe =
          client.callTool(
              "check_cwd",
              "{}",
              McpDeadline.of(Duration.ofSeconds(5)),
              McpCancellationToken.none());
      if (!probe.error()) {
        return;
      }
      sleepSilently(10);
    }
    throw new AssertionError("slow MCP call did not start in time");
  }

  /**
   * 回读服务端记录的已取消 request id 集合（文本形如 {@code "[23]"}）。
   *
   * <p>取消通知与后续工具调用走同一连接，因此在该连接上再发一次普通调用即可确认「取消已到达服务端」这一事实。
   */
  private static String awaitCancelledRequestIds(McpClient client) {
    long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    String text = "[]";
    while (System.nanoTime() < waitDeadline) {
      McpToolCallResult probe =
          client.callTool(
              "cancelled_ids",
              "{}",
              McpDeadline.of(Duration.ofSeconds(5)),
              McpCancellationToken.none());
      text = ((TextResultContent) probe.contents().getFirst()).text();
      if (!"[]".equals(text)) {
        return text;
      }
      sleepSilently(20);
    }
    return text;
  }

  /** 通过同连接的快速探针等待异步 tools/list 已真正进入服务端。 */
  private static void awaitListStarted(McpClient client) {
    long waitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < waitDeadline) {
      McpToolCallResult probe =
          client.callTool(
              "list_started",
              "{}",
              McpDeadline.of(Duration.ofSeconds(5)),
              McpCancellationToken.none());
      if ("true".equals(((TextResultContent) probe.contents().getFirst()).text())) {
        return;
      }
      sleepSilently(20);
    }
    throw new AssertionError("tools/list did not start in time");
  }

  private static void sleepSilently(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while waiting", error);
    }
  }

  private static McpDeadline deadline() {
    return McpDeadline.of(Duration.ofSeconds(10));
  }

  /** 不拦截任何派发的闸门：用于只关心传输自身行为的场景。 */
  private static McpDispatchGate allowAllDispatches() {
    return (requestId, register) -> {
      register.run();
      return true;
    };
  }

  /** 只登记在途请求的空操作处理器：用于只验证传输层状态、不驱动 JSON-RPC 场景。 */
  private static final class DevNullOperationHandler extends McpOperationHandler {

    DevNullOperationHandler() {
      super(
          new ConcurrentHashMap<>(),
          null,
          null,
          null,
          () -> {},
          () -> {},
          () -> {},
          uri -> {},
          null,
          () -> {},
          () -> {},
          (requestId, reason) -> {});
    }

    @Override
    public void handle(JsonNode message) {
      // 不处理任何入站消息
    }
  }
}
