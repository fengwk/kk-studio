package fun.fengwk.kkstudio.platform.catalog.mcp.client;

import static io.modelcontextprotocol.client.McpClient.sync;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.common.result.JsonResultContent;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * MCP client 工厂：按配置创建官方 SDK 的同步 Streamable HTTP 客户端。
 *
 * <p><strong>单一总预算</strong>：初始化（MCP 握手）与后续调用共用同一个外层 {@link McpDeadline}；创建阶段消耗该 deadline
 * 的剩余预算，绝不重新获得一份独立预算，因此「初始化慢」与「调用慢」不会各自超时一次。
 *
 * <p>超时统一映射为 {@link McpTimeoutException}，失败时幂等关闭底层传输，绝不泄漏半连接。 「初始化恰好与预算到期同时完成」的竞态由 {@link
 * ClientHandoff} 原子收敛：已构建的 client 要么被调用方领走，要么由构建方当场关闭，不存在无人关闭的中间态。
 *
 * <p>协议版本固定为 {@value #PROTOCOL_VERSION}：本模块与 Platform 属于同一套可复用 MCP 能力，不允许出现「同一仓库内两个 client
 * 声明不同协议版本」的分裂。
 */
public final class McpClientFactory {

  private static final String CLIENT_NAME = "kk-studio-mcp";

  private static final String CLIENT_VERSION = "1.0.0";

  /** 协议版本，与 Platform 侧 reusable MCP client 保持一致。 */
  private static final String PROTOCOL_VERSION = "2025-11-25";

  /**
   * 传输预算容纳 64 MiB 内容最坏六倍 JSON 转义，加一份单条 JSON 预算用于信封与元数据。
   *
   * <p>SDK 默认 16 MiB 报文和 Jackson 默认字符串上限不能缩小既有内容预算；最终内容仍由 {@link McpResultExtractor} 精确计量。
   */
  private static final int MAX_RESPONSE_BYTES =
      6 * McpResultExtractor.MAX_CONTENT_BUDGET_BYTES + JsonResultContent.MAX_JSON_UTF8_BYTES;

  private static final JacksonMcpJsonMapper JSON_MAPPER =
      new JacksonMcpJsonMapper(
          JsonMapper.builder(
                  JsonFactory.builder()
                      .streamReadConstraints(
                          StreamReadConstraints.builder()
                              .maxStringLength(MAX_RESPONSE_BYTES)
                              .maxDocumentLength(MAX_RESPONSE_BYTES)
                              .build())
                      .build())
              .build());

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
    McpClientTransport transport;
    try {
      URI endpoint = URI.create(config.url());
      String endpointPath = endpoint.getRawPath();
      if (endpointPath == null || endpointPath.isEmpty()) {
        endpointPath = "/";
      }
      if (endpoint.getRawQuery() != null) {
        endpointPath = endpointPath + "?" + endpoint.getRawQuery();
      }
      HttpRequest.Builder requestBuilder = HttpRequest.newBuilder();
      config.headers().forEach(requestBuilder::header);
      transport =
          HttpClientStreamableHttpTransport.builder(config.url())
              .endpoint(endpointPath)
              .requestBuilder(requestBuilder)
              .jsonMapper(JSON_MAPPER)
              .maxResponseSize(MAX_RESPONSE_BYTES)
              .supportedProtocolVersions(List.of(PROTOCOL_VERSION))
              .connectTimeout(remaining)
              .build();
    } catch (IllegalArgumentException error) {
      // URL 与 header 都可能携带凭证，配置解析失败只暴露固定不透明文本。
      throw new McpException("MCP client initialization failed");
    }
    return create(transport, deadline);
  }

  private static McpClient create(McpClientTransport transport, McpDeadline deadline) {
    if (deadline.isExpired()) {
      closeTransportQuietly(transport);
      throw new McpTimeoutException("MCP client initialization timed out");
    }

    ExecutorService initializer =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "mcp-client-init");
              thread.setDaemon(true);
              return thread;
            });
    CompletableFuture<McpSyncClient> building = new CompletableFuture<>();
    ClientHandoff<McpSyncClient> handoff = new ClientHandoff<>();
    boolean handedOff = false;
    try {
      initializer.execute(
          () -> {
            McpSyncClient built = null;
            try {
              Duration initRemaining = deadline.requireRemaining();
              built =
                  sync(transport)
                      .requestTimeout(initRemaining)
                      .initializationTimeout(initRemaining)
                      .enableCallToolSchemaCaching(false)
                      .clientInfo(
                          McpSchema.Implementation.builder(CLIENT_NAME, CLIENT_VERSION).build())
                      .build();
              built.initialize();
              // 先交付再唤醒等待者：调用方一旦从 get(...) 正常返回，交付必然已完成（happens-before 由 future 的完成保证）。
              handoff.offer(built);
              building.complete(built);
            } catch (RuntimeException error) {
              // 构建或握手失败时传输可能已经建立：就地关闭可以最快回收。
              closeQuietly(built);
              closeTransportQuietly(transport);
              building.completeExceptionally(error);
            }
          });
      try {
        Duration waitRemaining = deadline.requireRemaining();
        McpSyncClient client =
            building.get(Math.max(1L, waitRemaining.toMillis()), TimeUnit.MILLISECONDS);
        handoff.take();
        // 之后由返回的 client 接管传输生命周期：本条路径绝不再关闭 transport。
        handedOff = true;
        return new SdkMcpClient(client);
      } catch (TimeoutException | McpTimeoutException error) {
        // 放弃并原子回收：若构建方已交付则由此处关闭，否则由构建方的 offer 观察到放弃后关闭。
        handoff.abandon();
        closeTransportQuietly(transport);
        throw new McpTimeoutException("MCP client initialization timed out");
      } catch (ExecutionException error) {
        Throwable cause = error.getCause() == null ? error : error.getCause();
        handoff.abandon();
        closeTransportQuietly(transport);
        if (containsTimeout(cause)) {
          throw new McpTimeoutException("MCP client initialization timed out");
        }
        throw new McpException("MCP client initialization failed");
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        handoff.abandon();
        closeTransportQuietly(transport);
        throw new McpException("MCP client initialization interrupted");
      }
    } finally {
      initializer.shutdownNow();
      if (!handedOff) {
        // 兜底覆盖全部四类交错：构建线程尚未 start / 正在 start / 已 start / client 恰好构建完成。
        // 传输的 sticky 终态保证关闭之后绝不再发起连接，且重复 close 是幂等的。
        closeTransportQuietly(transport);
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
    if (closeable == null) {
      return;
    }
    try {
      closeable.close();
    } catch (Exception ignored) {
      // 清理失败不覆盖原始异常。
    }
  }

  private static void closeTransportQuietly(McpClientTransport transport) {
    try {
      transport.close();
    } catch (RuntimeException ignored) {
      // 清理失败不覆盖原始异常。
    }
  }
}
