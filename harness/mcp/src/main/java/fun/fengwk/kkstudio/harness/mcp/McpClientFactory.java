package fun.fengwk.kkstudio.harness.mcp;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MCP client 工厂：按配置创建 Streamable HTTP 客户端。
 *
 * <p><strong>单一总预算</strong>：初始化（MCP 握手）与后续调用共用同一个外层 {@link McpDeadline}；创建阶段消耗该 deadline
 * 的剩余预算，绝不重新获得一份独立预算，因此「初始化慢」与「调用慢」不会各自超时一次。
 *
 * <p>超时统一映射为 {@link McpTimeoutException}，失败时幂等关闭底层传输，绝不泄漏半连接。 「初始化恰好与预算到期同时完成」的竞态由 {@link
 * ClientHandoff} 原子收敛：已构建的 client 要么被调用方领走，要么由构建方当场关闭，不存在无人关闭的中间态。
 *
 * <p>协议版本与 Platform 侧 Reusable MCP client 对齐为 {@value #PROTOCOL_VERSION}：本模块与 Platform 属于同一套可复用 MCP
 * 能力，不允许出现「同一仓库内两个 client 声明不同协议版本」的分裂。
 */
public final class McpClientFactory {

  private static final String CLIENT_NAME = "kk-studio-mcp";

  /** 协议版本，与 Platform 侧 reusable MCP client 保持一致。 */
  private static final String PROTOCOL_VERSION = "2025-11-25";

  private McpClientFactory() {}

  /** 创建远端 Streamable HTTP MCP 客户端（per-call 模式）。 */
  public static McpClient createRemote(RemoteMcpConfig config, Duration timeout) {
    return createRemote(config, McpDeadline.of(timeout));
  }

  /** 基于外层 {@link McpDeadline} 创建远端 Streamable HTTP MCP 客户端。 */
  public static McpClient createRemote(RemoteMcpConfig config, McpDeadline deadline) {
    Objects.requireNonNull(config, "config");
    Objects.requireNonNull(deadline, "deadline");

    Duration remaining = deadline.requireRemaining();
    StreamableHttpMcpTransport.Builder transportBuilder =
        StreamableHttpMcpTransport.builder().url(config.url()).timeout(remaining);
    if (!config.headers().isEmpty()) {
      transportBuilder.customHeaders(config.headers());
    }
    StreamableHttpMcpTransport transport = transportBuilder.build();
    // Streamable HTTP 通过关闭 per-request SSE 流取消，无需额外的协议取消通道。
    return create(transport, "mcp-remote", deadline);
  }

  private static McpClient create(McpTransport transport, String keyPrefix, McpDeadline deadline) {
    if (deadline.isExpired()) {
      closeQuietly(transport);
      throw new McpTimeoutException("MCP client initialization timed out");
    }

    ExecutorService initializer =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "mcp-client-init");
              thread.setDaemon(true);
              return thread;
            });
    CompletableFuture<DefaultMcpClient> building = new CompletableFuture<>();
    ClientHandoff<DefaultMcpClient> handoff = new ClientHandoff<>();
    boolean handedOff = false;
    try {
      initializer.execute(
          () -> {
            try {
              Duration initRemaining = deadline.requireRemaining();
              DefaultMcpClient built =
                  DefaultMcpClient.builder()
                      .transport(transport)
                      .key(keyPrefix + "-" + UUID.randomUUID())
                      .clientName(CLIENT_NAME)
                      .protocolVersion(PROTOCOL_VERSION)
                      .initializationTimeout(initRemaining)
                      .toolExecutionTimeout(initRemaining)
                      .cacheToolList(false)
                      .subscribeToToolListChanges(false)
                      .autoHealthCheck(false)
                      .toolResultExtractor(new McpResultExtractor())
                      .build();
              // 先交付再唤醒等待者：调用方一旦从 get(...) 正常返回，交付必然已完成（happens-before 由 future 的完成保证）。
              handoff.offer(built);
              building.complete(built);
            } catch (RuntimeException error) {
              // 构建失败时传输可能已经建立，且 SDK 不会代客户端关闭传输：就地关闭可以最快回收。
              closeQuietly(transport);
              building.completeExceptionally(error);
            }
          });
      try {
        Duration waitRemaining = deadline.requireRemaining();
        DefaultMcpClient client =
            building.get(Math.max(1L, waitRemaining.toMillis()), TimeUnit.MILLISECONDS);
        handoff.take();
        // 之后由返回的 client 接管传输生命周期：本条路径绝不再关闭 transport。
        handedOff = true;
        return new LangChainMcpClient(client, transport);
      } catch (TimeoutException | McpTimeoutException error) {
        // 放弃并原子回收：若构建方已交付则由此处关闭，否则由构建方的 offer 观察到放弃后关闭。
        handoff.abandon();
        closeQuietly(transport);
        throw new McpTimeoutException("MCP client initialization timed out");
      } catch (ExecutionException error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        handoff.abandon();
        closeQuietly(transport);
        if (containsTimeout(cause)) {
          throw new McpTimeoutException("MCP client initialization timed out");
        }
        throw new McpException("MCP client initialization failed");
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        handoff.abandon();
        closeQuietly(transport);
        throw new McpException("MCP client initialization interrupted");
      }
    } finally {
      initializer.shutdownNow();
      if (!handedOff) {
        // 兜底覆盖全部四类交错：构建线程尚未 start / 正在 start / 已 start / client 恰好构建完成。
        // 传输的 sticky 终态保证关闭之后绝不再发起连接，且重复 close 是幂等的。
        closeQuietly(transport);
      }
    }
  }

  /** SDK 会把初始化超时包装成普通 RuntimeException，这里沿 cause 链识别超时语义。 */
  private static boolean containsTimeout(Throwable error) {
    Throwable current = error;
    int depth = 0;
    while (current != null && depth < 16) {
      if (current instanceof TimeoutException || current instanceof McpTimeoutException) {
        return true;
      }
      current = current.getCause();
      depth++;
    }
    return false;
  }

  private static void closeQuietly(AutoCloseable closeable) {
    try {
      closeable.close();
    } catch (Exception ignored) {
      // 清理失败不覆盖原始异常。
    }
  }
}
