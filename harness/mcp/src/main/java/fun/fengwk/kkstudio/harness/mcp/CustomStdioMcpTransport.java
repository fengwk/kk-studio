package fun.fengwk.kkstudio.harness.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.protocol.McpCancellationNotification;
import dev.langchain4j.mcp.protocol.McpClientMessage;
import dev.langchain4j.mcp.protocol.McpInitializationNotification;
import dev.langchain4j.mcp.protocol.McpInitializeRequest;
import dev.langchain4j.mcp.transport.stdio.JsonRpcIoHandler;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 定制 Stdio {@link McpTransport} 实现：
 *
 * <ul>
 *   <li>原样使用 command argv 数组，不经 shell 包装；
 *   <li>显式配置子进程工作目录 {@code pb.directory(new File(cwd))}；
 *   <li>覆盖子进程环境变量；
 *   <li>stderr 经内核通道重定向丢弃，完全不记录日志，杜绝 secrets 泄漏；
 *   <li>参考 SDK {@link JsonRpcIoHandler} 处理 stdin/stdout 上的 JSON-RPC 消息；
 *   <li>写出请求<strong>之前</strong>先经 {@link McpDispatchGate} 判定调用是否已取消，从而不丢失「取消早于派发」这一窗口内的取消意图；
 *   <li>自行登记每个在途 request id，从而支持请求级取消（{@link #abort}）；
 *   <li>关闭时安全杀死子进程树，防止进程泄漏。
 * </ul>
 *
 * <p>在途登记与取消判定由闸门在同一个临界区内完成，因此取消方与本传输对 request id 的观察顺序是确定的，不存在「取消被丢弃但请求仍发出」的交错。
 */
public final class CustomStdioMcpTransport implements McpTransport, McpRequestAborter {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final List<String> command;
  private final String cwd;
  private final Map<String, String> environment;
  private final McpDispatchGate dispatchGate;

  /** 生命周期互斥：{@link #start} 的「判定未关闭 + 拉起新代」与 {@link #close} 的「置终态 + 回收」整体串行。 */
  private final Object lifecycleLock = new Object();

  private volatile Generation currentGeneration;
  private volatile Runnable onFailureCallback;

  /** sticky 终态：一旦关闭，后续 {@link #start} 一律 fail closed，绝不再拉起子进程。 */
  private volatile boolean closed;

  public CustomStdioMcpTransport(
      List<String> command,
      String cwd,
      Map<String, String> environment,
      McpDispatchGate dispatchGate) {
    this.command = List.copyOf(Objects.requireNonNull(command, "command"));
    this.cwd = Objects.requireNonNull(cwd, "cwd");
    this.environment = environment == null ? Map.of() : Map.copyOf(environment);
    this.dispatchGate = Objects.requireNonNull(dispatchGate, "dispatchGate");
  }

  /**
   * 拉起子进程并开始处理 JSON-RPC。
   *
   * <p>与 {@link #close()} 整体串行：若关闭已经发生（包括关闭发生在「调用方超时放弃」期间），本方法必须 fail closed 而不再 spawn； 若 spawn
   * 已经进入临界区，则随后的关闭必然能看到该进程并回收它（连同进程树）。两种情况都不会留下无人管理的子进程。
   */
  @Override
  public void start(McpOperationHandler handler) {
    synchronized (lifecycleLock) {
      if (closed) {
        // 传输已进入终态：绝不再拉起子进程，否则关闭方无法再回收它。
        throw new McpException("MCP transport is closed");
      }
      Generation old = this.currentGeneration;
      this.currentGeneration = null;
      if (old != null) {
        old.stop();
      }
      Objects.requireNonNull(handler, "handler");
      Process p = null;
      try {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(cwd));
        if (!environment.isEmpty()) {
          pb.environment().putAll(environment);
        }
        // stderr 经重定向丢弃，避免子进程输出过大阻塞且绝不回显或记录
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        p = pb.start();

        JsonRpcIoHandler ioHandler =
            new JsonRpcIoHandler(p.getInputStream(), p.getOutputStream(), handler::handle, false);
        Thread ioThread = new Thread(ioHandler, "mcp-stdio-io-" + p.pid());
        ioThread.setDaemon(true);
        ioThread.start();

        Generation gen = new Generation(p, handler, ioHandler);
        this.currentGeneration = gen;

        p.onExit()
            .thenRun(
                () -> {
                  Runnable callback;
                  synchronized (lifecycleLock) {
                    if (closed || this.currentGeneration != gen) {
                      // 已经主动关闭，或者已经被新启动的进程（restart）替代：
                      // 旧进程的退出属于预期行为，绝不能把旧代的退出当作新代的意外故障，
                      // 更不能 fail 新代的 pendingRequests 或触发 onFailureCallback。
                      return;
                    }
                    this.currentGeneration = null;
                    callback = this.onFailureCallback;
                  }
                  gen.failPendingRequests("MCP process has exited");
                  gen.messageHandler.cancelAllPendingOperations("MCP process has exited");
                  if (callback != null) {
                    callback.run();
                  }
                });
      } catch (IOException | RuntimeException error) {
        // spawn 之后仍可能失败（IO handler 构造等）：必须就地回收，否则该进程无人管理。
        if (p != null) {
          try {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
          } catch (Exception ignored) {
          }
        }
        throw new McpException("failed to start MCP subprocess");
      }
    }
  }

  @Override
  public CompletableFuture<JsonNode> initialize(McpInitializeRequest request) {
    return execute(toJson(request), request.getId())
        .thenCompose(
            response ->
                execute(toJson(new McpInitializationNotification()), null)
                    .thenCompose(notification -> CompletableFuture.completedFuture(response)));
  }

  @Override
  public CompletableFuture<JsonNode> executeOperationWithResponse(McpClientMessage message) {
    return execute(toJson(message), message.getId());
  }

  @Override
  public CompletableFuture<JsonNode> executeOperationWithResponse(McpCallContext context) {
    return executeOperationWithResponse(context.message());
  }

  @Override
  public void executeOperationWithoutResponse(McpClientMessage message) {
    execute(toJson(message), null);
  }

  @Override
  public void executeOperationWithoutResponse(McpCallContext context) {
    executeOperationWithoutResponse(context.message());
  }

  /** 序列化出站消息；本模块的消息类型不含循环引用或自定义序列化，失败即代表进程内状态异常。 */
  private static String toJson(Object message) {
    try {
      return OBJECT_MAPPER.writeValueAsString(message);
    } catch (JsonProcessingException error) {
      throw new McpException("failed to serialize MCP message");
    }
  }

  private CompletableFuture<JsonNode> execute(String json, Long id) {
    if (closed) {
      return CompletableFuture.failedFuture(new McpException("MCP IO handler is not initialized"));
    }
    Generation gen = this.currentGeneration;
    if (gen == null) {
      // 传输尚未启动或已退出：必须显式失败，绝不静默挂起等待一个永不到来的响应。
      return CompletableFuture.failedFuture(new McpException("MCP IO handler is not initialized"));
    }
    CompletableFuture<JsonNode> future = new CompletableFuture<>();
    if (id != null) {
      // 完整派发原子临界区：登记在途与底层写出都在闸门锁内完成。
      // 取消先到 → 登记与写出均不发生，原请求完全不入管道；
      // 派发先到 → 原请求完整写出到管道后才释放锁，取消方发送通知必然排在原请求之后。
      try {
        boolean dispatched =
            dispatchGate.dispatchOrAbort(
                id,
                () -> {
                  gen.registerPending(id, future);
                  gen.ioHandler.submit(json);
                });
        if (!dispatched) {
          future.completeExceptionally(
              new McpCancelledException("MCP request cancelled by caller"));
          return future;
        }
      } catch (IOException error) {
        // 子进程已退出（管道断裂等）：本次请求显式失败，避免调用方无限等待。
        future.completeExceptionally(new McpException("failed to write to MCP subprocess"));
        return future;
      }
    } else {
      // 无 id 的通知等消息直接写出
      try {
        gen.ioHandler.submit(json);
      } catch (IOException error) {
        future.completeExceptionally(new McpException("failed to write to MCP subprocess"));
        return future;
      }
      future.complete(null);
    }
    return future;
  }

  /**
   * 中止指定 request id 的在途请求：结束本地等待，并发送 {@code notifications/cancelled} 让服务端停止执行。
   *
   * <p>只影响该 request，同一连接上的其它并发请求与连接本身都不受影响。request 不存在时返回 {@code false}（例如已被服务端抢先结束）。
   */
  @Override
  public boolean abort(long requestId, String reason) {
    Generation gen = this.currentGeneration;
    if (gen == null) {
      return false;
    }
    return gen.abort(requestId, reason);
  }

  @Override
  public void checkHealth() {
    Generation gen = this.currentGeneration;
    if (gen == null || !gen.process.isAlive()) {
      throw new IllegalStateException("MCP subprocess is not alive");
    }
  }

  @Override
  public boolean requiresCancellationNotification() {
    return true;
  }

  @Override
  public void onFailure(Runnable callback) {
    this.onFailureCallback = callback;
  }

  /** 获取底层受管子进程实例（主要供测试校验 pid/cwd/退出状态）。 */
  public Process getProcess() {
    Generation gen = this.currentGeneration;
    return gen != null ? gen.process : null;
  }

  /** 在途请求数（主要供测试断言取消后不再保留请求）。 */
  public int pendingRequestCount() {
    Generation gen = this.currentGeneration;
    return gen != null ? gen.pendingRequests.size() : 0;
  }

  /**
   * 进入终态并回收当前进程。
   *
   * <p>{@link #closed} 一旦置位不可逆：置位与回收在同一临界区内完成，因此 {@link #start} 不可能在关闭之后再次 spawn；反过来，若 spawn
   * 已完成，本方法必然持有该进程引用并将其（连同进程树）回收。
   */
  private void stopCurrentProcess() {
    synchronized (lifecycleLock) {
      closed = true;
      Generation gen = this.currentGeneration;
      this.currentGeneration = null;
      if (gen != null) {
        gen.stop();
      }
    }
  }

  /** 是否已进入终态；终态之后 {@link #start} 不再拉起任何子进程。 */
  public boolean isClosed() {
    return closed;
  }

  @Override
  public void close() {
    stopCurrentProcess();
  }

  /**
   * 单代子进程及其在途请求的隔离容器。
   *
   * <p>每一代子进程拥有独立的 {@link #pendingRequests}：旧代进程退出或被回收时，只会清理属于该代的在途请求， 绝不会跨代触碰新启动世代的在途请求。
   */
  private static final class Generation {

    final Process process;
    final McpOperationHandler messageHandler;
    final JsonRpcIoHandler ioHandler;
    final Map<Long, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();

    Generation(Process process, McpOperationHandler messageHandler, JsonRpcIoHandler ioHandler) {
      this.process = process;
      this.messageHandler = messageHandler;
      this.ioHandler = ioHandler;
    }

    void registerPending(long requestId, CompletableFuture<JsonNode> future) {
      pendingRequests.put(requestId, future);
      future.whenComplete((response, error) -> pendingRequests.remove(requestId, future));
      messageHandler.startOperation(requestId, future);
    }

    boolean abort(long requestId, String reason) {
      CompletableFuture<JsonNode> future = pendingRequests.remove(requestId);
      if (future == null) {
        return false;
      }
      future.completeExceptionally(new McpCancelledException("MCP request cancelled by caller"));
      McpCancellationNotification cancellation = new McpCancellationNotification(requestId, reason);
      try {
        ioHandler.submit(OBJECT_MAPPER.writeValueAsString(cancellation));
      } catch (Exception ignored) {
        // 服务端可能已结束该请求或子进程已退出；本地等待已结束，取消语义仍然成立。
      }
      return true;
    }

    void failPendingRequests(String reason) {
      for (Map.Entry<Long, CompletableFuture<JsonNode>> entry : pendingRequests.entrySet()) {
        CompletableFuture<JsonNode> future = entry.getValue();
        if (pendingRequests.remove(entry.getKey(), future)) {
          future.completeExceptionally(new McpException(reason));
        }
      }
    }

    void stop() {
      failPendingRequests("MCP transport is closing");
      if (ioHandler != null) {
        try {
          ioHandler.close();
        } catch (Exception ignored) {
        }
      }
      if (process != null) {
        try {
          process.descendants().forEach(ProcessHandle::destroyForcibly);
          process.destroy();
          if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly();
          }
        } catch (InterruptedException e) {
          process.destroyForcibly();
          Thread.currentThread().interrupt();
        } catch (Exception ignored) {
        }
      }
    }
  }
}
