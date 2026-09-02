package fun.fengwk.kkstudio.platform.catalog.mcp.runtime;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.harness.contributor.api.Tool;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.contributor.api.ToolExecutionRequest;
import fun.fengwk.kkstudio.harness.contributor.api.ToolOutcome;
import fun.fengwk.kkstudio.harness.contributor.api.ToolRequirements;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.platform.catalog.mcp.client.McpConnectionSpec;
import fun.fengwk.kkstudio.platform.catalog.mcp.client.McpToolCallOutcome;
import fun.fengwk.kkstudio.platform.catalog.mcp.client.McpToolClient;
import fun.fengwk.kkstudio.platform.catalog.mcp.client.McpToolClientFactory;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单个 MCP 工具的可执行 {@link Tool} 实现。
 *
 * <p>每次 {@link #execute} 都用当前 server 配置创建 per-call MCP client（Streamable HTTP）调用 source_name， 返回
 * text ToolResult（无 effects，sideEffect 恒为 NON_IDEMPOTENT，requirements 为 none）。异步 SPI 快速返回：
 * 调用在调用者提供的 executor 上执行，返回可取消 handle；client 关闭恰好一次。
 *
 * <p>连接失败/协议失败对外只暴露 {@link McpToolCallOutcome#failureMessage()} 的稳定通用文本，绝不泄漏 URL、 token 或 header。
 */
@Slf4j
public final class McpExecutableTool implements Tool {

  private final ToolDescriptor descriptor;
  private final String sourceName;
  private final McpConnectionSpec connection;
  private final McpToolClientFactory clientFactory;

  public McpExecutableTool(
      ToolDescriptor descriptor,
      String sourceName,
      McpConnectionSpec connection,
      McpToolClientFactory clientFactory) {
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    if (sourceName == null || sourceName.isBlank()) {
      throw new IllegalArgumentException("sourceName must not be blank");
    }
    this.sourceName = sourceName;
    this.connection = Objects.requireNonNull(connection, "connection");
    this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
  }

  @Override
  public ToolDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public ToolRequirements requirements() {
    return ToolRequirements.none();
  }

  @Override
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(listener, "listener");
    Execution execution = new Execution(request, listener);
    execution.start();
    return execution;
  }

  /** 单次执行的异步运行体：持有 per-call client 并保证单一 ownership 至多关闭一次。 */
  private final class Execution implements ToolExecutionHandle, Runnable {

    private final ToolExecutionRequest request;
    private final ToolExecutionListener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile McpToolClient client;

    private Execution(ToolExecutionRequest request, ToolExecutionListener listener) {
      this.request = request;
      this.listener = listener;
    }

    private void start() {
      // 异步 SPI：虚拟线程快速启动并立即返回，调用方得到可取消 handle。
      Thread.ofVirtual().name("mcp-tool-call").start(this);
    }

    @Override
    public void run() {
      try {
        if (cancelled.get()) {
          return;
        }
        McpToolClient perCallClient;
        try {
          perCallClient = clientFactory.create(connection);
        } catch (RuntimeException error) {
          log.warn("MCP client creation or handshake failed for tool: {}", sourceName);
          if (!cancelled.get()) {
            listener.onComplete(
                ToolOutcome.withoutEffects(
                    McpToolCallOutcome.failure(request.call().id()).result()));
          }
          return;
        }

        this.client = perCallClient;
        if (cancelled.get()) {
          return;
        }

        McpToolCallOutcome outcome;
        try {
          outcome =
              perCallClient.callTool(
                  sourceName, request.call().argumentsJson(), request.call().id());
        } catch (RuntimeException error) {
          log.warn("MCP tool call failed for tool: {}", sourceName);
          outcome = McpToolCallOutcome.failure(request.call().id());
        }

        if (cancelled.get()) {
          return;
        }
        listener.onComplete(ToolOutcome.withoutEffects(outcome.result()));
      } finally {
        closeClientOnce();
      }
    }

    private void closeClientOnce() {
      McpToolClient current = client;
      if (current != null && closed.compareAndSet(false, true)) {
        try {
          current.close();
        } catch (RuntimeException closeError) {
          log.debug(
              "failed to close per-call MCP client: {}", closeError.getClass().getSimpleName());
        }
      }
    }

    @Override
    public void cancel() {
      cancelled.set(true);
      closeClientOnce();
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }
  }
}
