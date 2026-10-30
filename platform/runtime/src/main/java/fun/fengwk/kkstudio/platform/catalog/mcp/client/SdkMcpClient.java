package fun.fengwk.kkstudio.platform.catalog.mcp.client;

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import fun.fengwk.kkstudio.common.json.JsonValues;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 基于官方 MCP Java SDK {@link McpSyncClient} 实现的 {@link McpClient} 包装器。
 *
 * <p>SDK 的 {@code callTool}/{@code listTools} 是同步阻塞调用。
 *
 * <p>取消与超时都按「调用级」处理：立即结束本地等待并中断执行线程，且不关闭共享 client，因此同一 client 上的其它并发调用不受影响。官方同步 SDK
 * 不提供独立的协议取消通道，在途请求由服务端响应或 SDK 请求超时收敛。
 *
 * <p>阻塞 SDK 调用跑在至多 {@value #MAX_CONCURRENT_OPERATIONS} 个 worker 的 {@link ThreadPoolExecutor}
 * 上，交接队列为 {@link SynchronousQueue}，不保存等待任务：并发上限与外层工具 admission 的默认并发 64 对齐，不另设配置。池满或 client
 * 已关闭时提交立即拒绝，并映射为可重试的 {@link McpException}，绝不挂起调用方。{@code future.cancel} 只结束本地等待、不释放容量：不响应
 * interrupt 的 worker 仍在执行任务，不会被 idle worker 接收新任务，因此容量上界始终是 executor 的真实 worker 上界。
 */
final class SdkMcpClient implements McpClient {

  /**
   * 单个 client 上同时执行的 SDK 阻塞调用上限。
   *
   * <p>沿用外层 Harness 工具 admission 的默认并发 {@code 64}，不新增 MCP 配置项。
   */
  static final int MAX_CONCURRENT_OPERATIONS = 64;

  /** 空闲 worker 的回收时间：worker 数量在负载回落后收敛，池不会长期占着线程。 */
  private static final long WORKER_KEEP_ALIVE_SECONDS = 60L;

  /**
   * 模块自有 JSON（工具 schema）的 Jackson 3 mapper。
   *
   * <p>显式开启 {@link SerializationFeature#FAIL_ON_EMPTY_BEANS}：Jackson 3 默认关闭该开关，会把无可序列化属性的 value 写成
   * {@code {}}；这里保持既有约定，让「无法编码的 schema」继续走失败回退，而不是产出看似合法的空对象 JSON。
   */
  private static final ObjectMapper OBJECT_MAPPER =
      JsonMapper.builder().enable(SerializationFeature.FAIL_ON_EMPTY_BEANS).build();

  private static final TypeReference<Map<String, Object>> MAP_TYPE =
      new TypeReference<Map<String, Object>>() {};

  private final McpSyncClient client;
  private final ThreadPoolExecutor workers = newWorkerPool();
  private final McpResultExtractor extractor = new McpResultExtractor();

  /**
   * 有界、无排队的阻塞调用池：只在有 idle worker 时直接交接，否则新建 worker，达到 {@link #MAX_CONCURRENT_OPERATIONS} 后拒绝。
   *
   * <p>worker 惰性创建且不预热——容量上界只由真实创建的 worker 决定，而不是预建一批可能永远空闲的线程。
   */
  private static ThreadPoolExecutor newWorkerPool() {
    return new ThreadPoolExecutor(
        0,
        MAX_CONCURRENT_OPERATIONS,
        WORKER_KEEP_ALIVE_SECONDS,
        TimeUnit.SECONDS,
        new SynchronousQueue<>(),
        runnable -> {
          Thread thread = new Thread(runnable, "mcp-client-worker");
          thread.setDaemon(true);
          return thread;
        },
        new ThreadPoolExecutor.AbortPolicy());
  }

  SdkMcpClient(McpSyncClient client) {
    this.client = Objects.requireNonNull(client, "client");
  }

  @Override
  public List<McpToolDefinition> listTools(McpDeadline deadline, McpCancellationToken token) {
    Objects.requireNonNull(deadline, "deadline");
    Objects.requireNonNull(token, "token");
    McpSchema.ListToolsResult result =
        runOperation(deadline, token, "MCP listTools", client::listTools);
    List<McpToolDefinition> definitions = new ArrayList<>(result.tools().size());
    for (McpSchema.Tool tool : result.tools()) {
      definitions.add(toDefinition(tool));
    }
    return List.copyOf(definitions);
  }

  @Override
  public McpToolCallResult callTool(
      String name, String argumentsJson, McpDeadline deadline, McpCancellationToken token) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name must not be blank");
    }
    Objects.requireNonNull(deadline, "deadline");
    Objects.requireNonNull(token, "token");
    Map<String, Object> arguments = parseArguments(normalizeArguments(argumentsJson));
    McpSchema.CallToolRequest request =
        McpSchema.CallToolRequest.builder(name).arguments(arguments).build();
    McpSchema.CallToolResult result =
        runOperation(deadline, token, "MCP tool call", () -> client.callTool(request));
    return extractor.extract(result);
  }

  /** 在 worker 线程上执行一次 SDK 阻塞调用，并按总预算等待。 */
  private <T> T runOperation(
      McpDeadline deadline, McpCancellationToken token, String what, SdkCall<T> sdkCall) {
    deadline.checkNotExpired();
    if (token.isCancelled()) {
      throw new McpCancelledException("MCP operation cancelled by caller");
    }
    Future<T> future;
    try {
      future =
          workers.submit(
              () -> {
                try {
                  return sdkCall.call();
                } catch (CancellationException error) {
                  throw new McpCancelledException("MCP operation cancelled by caller");
                } finally {
                  // worker 线程会被池复用：异常路径留下的 interrupt 状态会污染后续任务的取消判定。
                  Thread.interrupted();
                }
              });
    } catch (RejectedExecutionException rejected) {
      // 池满或已关闭都不是永久性协议失败：调用方可以在容量恢复或 client 重建后重试。
      if (workers.isShutdown()) {
        throw new McpException(what + " rejected because the MCP client is closed");
      }
      throw new McpException(what + " rejected because the MCP client is at capacity");
    }
    token.onCancel(() -> future.cancel(true));
    try {
      Duration waitBudget = deadline.requireRemaining();
      return future.get(Math.max(1L, waitBudget.toMillis()), TimeUnit.MILLISECONDS);
    } catch (TimeoutException | McpTimeoutException error) {
      future.cancel(true);
      throw new McpTimeoutException(what + " timed out");
    } catch (CancellationException error) {
      throw new McpCancelledException("MCP operation cancelled by caller");
    } catch (ExecutionException error) {
      Throwable cause = error.getCause() != null ? error.getCause() : error;
      // 取消会让 SDK 把中断包装成普通执行失败：调用方已决定取消时必须如实报告取消，否则上层会把
      // 「调用方主动取消」误判为连接或进程失败，进而销毁仍健康的共享 client。
      if (token.isCancelled()) {
        throw new McpCancelledException("MCP operation cancelled by caller");
      }
      if (cause instanceof McpCancelledException cancelled) {
        throw cancelled;
      }
      if (containsTimeout(cause)) {
        throw new McpTimeoutException(what + " timed out");
      }
      throw new McpException(what + " failed");
    } catch (InterruptedException error) {
      future.cancel(true);
      if (token.isCancelled()) {
        throw new McpCancelledException("MCP operation cancelled by caller");
      }
      Thread.currentThread().interrupt();
      throw new McpException(what + " interrupted");
    }
  }

  private McpToolDefinition toDefinition(McpSchema.Tool tool) {
    String inputSchemaJson = "{}";
    Map<String, Object> inputSchema = tool.inputSchema();
    if (inputSchema != null && !inputSchema.isEmpty()) {
      try {
        inputSchemaJson = OBJECT_MAPPER.writeValueAsString(inputSchema);
      } catch (JacksonException error) {
        throw new McpException("failed to encode MCP tool input schema");
      }
    }
    return new McpToolDefinition(tool.name(), tool.description(), inputSchemaJson);
  }

  /**
   * 校验并规范化工具入参：只接受严格 JSON object。
   *
   * <p>复用仓库统一的严格 JSON 门禁 {@link JsonValues}：重复键与尾随 token 都属于畸形输入，必须本地拒绝，而不是让服务端按「哪个键胜出」的偶然实现执行。
   * 错误文本恒为固定不透明字符串，绝不回显 payload 片段，也不保留底层解析异常作为 cause。
   */
  private static String normalizeArguments(String argumentsJson) {
    try {
      return JsonValues.requireJsonObject(argumentsJson, "MCP tool arguments");
    } catch (IllegalArgumentException error) {
      throw new McpException("MCP tool arguments must be a strict JSON object");
    }
  }

  private static Map<String, Object> parseArguments(String strictJsonObject) {
    try {
      return OBJECT_MAPPER.readValue(strictJsonObject, MAP_TYPE);
    } catch (JacksonException error) {
      throw new McpException("MCP tool arguments must be a strict JSON object");
    }
  }

  /** SDK 会把请求超时包装成普通执行失败，这里沿 cause 链识别超时语义。 */
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

  @Override
  public void close() {
    workers.shutdownNow();
    try {
      client.close();
    } catch (RuntimeException error) {
      throw new McpException("MCP client close failed");
    }
  }

  /** 底层 SDK 阻塞调用。 */
  @FunctionalInterface
  private interface SdkCall<T> {
    T call();
  }
}
