package fun.fengwk.kkstudio.harness.daemon.mcp;

import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityCatalog;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityIds;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;
import fun.fengwk.kkstudio.harness.mcp.McpCancellationToken;
import fun.fengwk.kkstudio.harness.mcp.McpCancelledException;
import fun.fengwk.kkstudio.harness.mcp.McpClient;
import fun.fengwk.kkstudio.harness.mcp.McpDeadline;
import fun.fengwk.kkstudio.harness.mcp.McpTimeoutException;
import fun.fengwk.kkstudio.harness.mcp.McpToolCallResult;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 执行本地 MCP 工具调用的 Environment Capability：{@code mcp.local.call}。
 *
 * <p>预算语义：一次调用只使用<strong>一个绝对 deadline</strong>，取「请求预算」与「配置 timeoutMillis」的较小值，并从本次执行开始计时； 该
 * deadline 同时覆盖 lazy 初始化（拉起子进程 + MCP 握手）与工具调用，任何阶段都不重置预算。
 *
 * <p>遵循单终态与取消语义：取消立即结束本地等待、中止在途 MCP request，但绝不关闭共享 client，因此同版本其它并发调用不受影响。失败文本恒为固定不透明字符串，
 * 绝不回显本地路径、环境变量或敏感入参。
 */
public final class McpLocalCallCapability implements EnvironmentCapability {

  private static final String INVALID_REQUEST_MESSAGE = "invalid mcp.local.call request";
  private static final String EXECUTION_FAILED_MESSAGE = "mcp.local.call failed";
  private static final String EXECUTION_TIMEOUT_MESSAGE = "mcp.local.call timed out";

  private final DaemonLocalMcpManager manager;
  private final DaemonLocalMcpParser parser;
  private final ExecutorService executor;
  private final EnvironmentCapabilityDescriptor descriptor;

  public McpLocalCallCapability(DaemonLocalMcpManager manager, ExecutorService executor) {
    this(manager, new DaemonLocalMcpParser(), executor);
  }

  public McpLocalCallCapability(
      DaemonLocalMcpManager manager, DaemonLocalMcpParser parser, ExecutorService executor) {
    this.manager = Objects.requireNonNull(manager, "manager");
    this.parser = Objects.requireNonNull(parser, "parser");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.descriptor = EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.MCP_LOCAL_CALL);
  }

  @Override
  public EnvironmentCapabilityDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public EnvironmentCapabilityExecutionHandle execute(
      EnvironmentCapabilityExecutionRequest request,
      EnvironmentCapabilityExecutionListener listener) {
    if (!descriptor.equals(request.descriptor())) {
      throw new IllegalArgumentException("request descriptor does not match capability descriptor");
    }
    Objects.requireNonNull(listener, "listener");
    Execution execution = new Execution(request.call().id(), listener);
    execution.worker = executor.submit(() -> run(request, execution));
    return execution;
  }

  private void run(EnvironmentCapabilityExecutionRequest request, Execution execution) {
    String callId = request.call().id();
    DaemonLocalMcpCallRequest callRequest;
    try {
      callRequest = parser.parseCall(request.call().argumentsJson());
    } catch (RuntimeException error) {
      execution.complete(EnvironmentCapabilityResult.error(callId, INVALID_REQUEST_MESSAGE));
      return;
    }

    McpDeadline deadline = McpDeadline.of(composeBudget(request, callRequest.config()));
    McpCancellationToken token = execution.token();

    DaemonLocalMcpManager.ManagedCall call = null;
    boolean failed = false;
    try {
      call = manager.acquire(callRequest.config());
      if (token.isCancelled()) {
        return;
      }
      McpClient client = call.getClient(deadline);
      McpToolCallResult outcome =
          client.callTool(callRequest.toolName(), callRequest.argumentsJson(), deadline, token);
      execution.complete(
          new EnvironmentCapabilityResult(
              callId, outcome.contents(), outcome.error(), outcome.detailsJson()));
    } catch (McpCancelledException error) {
      // 取消已由 Execution 给出终态；共享 client 仍然健康，绝不销毁。
    } catch (McpTimeoutException error) {
      // 超时已中止本次在途 request，连接可继续复用；只有请求本身失败。
      execution.complete(EnvironmentCapabilityResult.error(callId, EXECUTION_TIMEOUT_MESSAGE));
    } catch (RuntimeException error) {
      // 连接/进程/协议层面的失败：标记实例不可信，待 active 调用归零后销毁并允许后续重建。
      failed = true;
      if (!execution.isCancelled()) {
        execution.complete(EnvironmentCapabilityResult.error(callId, EXECUTION_FAILED_MESSAGE));
      }
    } finally {
      if (call != null) {
        // 取消与超时都属于「调用被放弃」而不是 client 损坏：绝不把共享 client 标记为失败，否则会打断同版本其它并发调用。
        call.release(failed);
      }
    }
  }

  /** 取请求预算与配置超时的较小值，形成一次调用的绝对预算。 */
  private static Duration composeBudget(
      EnvironmentCapabilityExecutionRequest request, DaemonLocalMcpConfig config) {
    Duration configBudget = Duration.ofMillis(config.timeoutMillis());
    Duration requestBudget = request.effectiveTimeout();
    if (requestBudget.isZero() || requestBudget.isNegative()) {
      return configBudget;
    }
    return requestBudget.compareTo(configBudget) > 0 ? configBudget : requestBudget;
  }

  /** 单次执行的句柄：承载终态、工作 Future 与调用级取消令牌。 */
  private static final class Execution implements EnvironmentCapabilityExecutionHandle {

    private final String callId;
    private final EnvironmentCapabilityExecutionListener listener;
    private final McpCancellationToken token = new McpCancellationToken();
    private final AtomicBoolean terminal = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile Future<?> worker;

    private Execution(String callId, EnvironmentCapabilityExecutionListener listener) {
      this.callId = callId;
      this.listener = listener;
    }

    McpCancellationToken token() {
      return token;
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    @Override
    public void cancel() {
      if (cancelled.compareAndSet(false, true)) {
        // 先取消令牌：在途 MCP request 会被精确中止（结束等待 + notifications/cancelled），而不是只停止本地等待。
        token.cancel();
        Future<?> current = worker;
        if (current != null) {
          current.cancel(true);
        }
        complete(EnvironmentCapabilityResult.error(callId, "capability execution cancelled"));
      }
    }

    void complete(EnvironmentCapabilityResult result) {
      if (terminal.compareAndSet(false, true)) {
        listener.onComplete(result);
      }
    }
  }
}
