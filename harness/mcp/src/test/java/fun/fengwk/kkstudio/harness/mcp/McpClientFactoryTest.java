package fun.fengwk.kkstudio.harness.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * {@link McpClientFactory} 初始化语义测试。
 *
 * <p>成功路径由 {@link RemoteMcpClientTest}
 * 用真实服务端覆盖；本测试专注失败收敛：预算已耗尽、握手被服务端拒绝、握手无响应超时、调用方线程被中断，以及这些路径都不得遗留初始化线程。
 *
 * <p>失败分类是对外契约：超时必须映射为 {@link McpTimeoutException}，其余初始化失败为 {@link McpException}，且都只携带固定不透明文本。
 */
class McpClientFactoryTest {

  private static final String INIT_FAILED = "MCP client initialization failed";
  private static final String INIT_TIMED_OUT = "MCP client initialization timed out";
  private static final String INIT_INTERRUPTED = "MCP client initialization interrupted";

  private HttpServer server;

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  /** createRemote 的入参是显式契约：缺少 config 或 deadline 都不允许被静默补默认值。 */
  @Test
  void rejectsMissingConfigOrDeadline() {
    RemoteMcpConfig config = new RemoteMcpConfig("http://127.0.0.1:1/mcp", Map.of());
    assertThatThrownBy(() -> McpClientFactory.createRemote(null, Duration.ofSeconds(1)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> McpClientFactory.createRemote(config, (McpDeadline) null))
        .isInstanceOf(NullPointerException.class);
  }

  /** 预算已耗尽时不得再发起握手：必须立即以超时结束，而不是进入初始化流程。 */
  @Test
  void failsWithoutHandshakeWhenDeadlineAlreadyExpired() {
    RemoteMcpConfig config = new RemoteMcpConfig("http://127.0.0.1:1/mcp", Map.of());
    McpDeadline expired = McpDeadline.ofDeadline(Instant.now().minusSeconds(1));

    assertThatThrownBy(() -> McpClientFactory.createRemote(config, expired))
        .isInstanceOf(McpTimeoutException.class)
        .hasMessageContaining("deadline exceeded");
    assertNoInitializerThreadsLeft();
  }

  /** 服务端拒绝握手时必须映射为初始化失败（而非超时），并回收初始化线程。 */
  @Test
  @Timeout(30)
  void mapsRejectedHandshakeToInitializationFailure() throws IOException {
    startServer(exchange -> respond(exchange, 500, "nope"));

    assertThatThrownBy(() -> McpClientFactory.createRemote(config(), McpDeadline.of(seconds(5))))
        .isInstanceOf(McpException.class)
        .isNotInstanceOf(McpTimeoutException.class)
        .hasMessage(INIT_FAILED);
    assertNoInitializerThreadsLeft();
  }

  /** 服务端不响应握手时必须在预算内以超时结束，并回收初始化线程，绝不留下半连接。 */
  @Test
  @Timeout(30)
  void mapsUnansweredHandshakeToTimeoutAndReclaimsInitializer() throws IOException {
    startServer(exchange -> sleepSeconds(3));

    long startNanos = System.nanoTime();
    assertThatThrownBy(
            () -> McpClientFactory.createRemote(config(), McpDeadline.of(Duration.ofMillis(300))))
        .isInstanceOf(McpTimeoutException.class)
        .hasMessage(INIT_TIMED_OUT);
    long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

    assertThat(elapsedMillis).as("初始化超时必须按预算收敛").isLessThan(10_000L);
    assertNoInitializerThreadsLeft();
  }

  /** 调用方线程被中断时必须按中断报告失败并归还中断标记，同时回收初始化线程。 */
  @Test
  @Timeout(30)
  void mapsInterruptedCallerToInterruptedFailureAndRestoresFlag() throws IOException {
    startServer(exchange -> sleepSeconds(3));

    try {
      Thread.currentThread().interrupt();
      assertThatThrownBy(() -> McpClientFactory.createRemote(config(), McpDeadline.of(seconds(5))))
          .isInstanceOf(McpException.class)
          .isNotInstanceOf(McpTimeoutException.class)
          .hasMessage(INIT_INTERRUPTED);
      assertThat(Thread.currentThread().isInterrupted()).as("中断标记必须归还给调用者").isTrue();
    } finally {
      Thread.interrupted();
    }

    assertNoInitializerThreadsLeft();
  }

  private RemoteMcpConfig config() {
    return new RemoteMcpConfig(
        "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", Map.of());
  }

  private void startServer(HttpHandler handler) throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/mcp", handler);
    server.start();
  }

  private static void respond(HttpExchange exchange, int status, String body) throws IOException {
    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream out = exchange.getResponseBody()) {
      out.write(bytes);
    }
  }

  private static void sleepSeconds(long seconds) {
    try {
      Thread.sleep(Duration.ofSeconds(seconds).toMillis());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
    }
  }

  private static Duration seconds(long value) {
    return Duration.ofSeconds(value);
  }

  /**
   * 初始化线程必须被回收：工厂为每次初始化创建单线程执行器，失败路径若忘记终止就会在长时间运行的进程中持续累积。
   *
   * <p>线程终止是异步的，这里轮询到稳定状态再断言，避免用固定 sleep 造成偶发失败。
   */
  private static void assertNoInitializerThreadsLeft() {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (!initializerThreads().isEmpty() && System.nanoTime() < deadline) {
      try {
        Thread.sleep(20);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while waiting for initializer cleanup", error);
      }
    }
    assertThat(initializerThreads()).as("失败路径不得遗留初始化线程").isEmpty();
  }

  private static List<Thread> initializerThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(Thread::isAlive)
        .filter(thread -> "mcp-client-init".equals(thread.getName()))
        .toList();
  }
}
