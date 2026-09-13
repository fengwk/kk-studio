package fun.fengwk.kkstudio.harness.daemon.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fun.fengwk.kkstudio.harness.common.json.JsonValues;
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
import fun.fengwk.kkstudio.harness.mcp.McpToolDefinition;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 管理专用本地 MCP 工具发现 Capability：{@code mcp.local.discover}。
 *
 * <p>结果恒为 JSON object envelope：{@code
 * {"serverId":…,"configVersion":…,"tools":[{"name","description","inputSchema"}]}}，
 * 使调用方无需依赖「数组还是对象」的猜测，也不会把工具列表与其它形状混淆。
 *
 * <p>预算语义与 {@link McpLocalCallCapability} 一致：单一绝对 deadline 覆盖 lazy 初始化与 tools/list；取消会精确中止该在途
 * request，不关闭共享 client；失败文本恒为固定不透明字符串。
 */
public final class McpLocalDiscoverCapability implements EnvironmentCapability {

  private static final String INVALID_REQUEST_MESSAGE = "invalid mcp.local.discover request";
  private static final String EXECUTION_FAILED_MESSAGE = "mcp.local.discover failed";
  private static final String EXECUTION_TIMEOUT_MESSAGE = "mcp.local.discover timed out";
  private static final String INVALID_TOOL_LIST_MESSAGE =
      "mcp.local.discover returned invalid tool list";
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private final DaemonLocalMcpManager manager;
  private final DaemonLocalMcpParser parser;
  private final ExecutorService executor;
  private final EnvironmentCapabilityDescriptor descriptor;

  public McpLocalDiscoverCapability(DaemonLocalMcpManager manager, ExecutorService executor) {
    this(manager, new DaemonLocalMcpParser(), executor);
  }

  public McpLocalDiscoverCapability(
      DaemonLocalMcpManager manager, DaemonLocalMcpParser parser, ExecutorService executor) {
    this.manager = Objects.requireNonNull(manager, "manager");
    this.parser = Objects.requireNonNull(parser, "parser");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.descriptor =
        EnvironmentCapabilityCatalog.require(EnvironmentCapabilityIds.MCP_LOCAL_DISCOVER);
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
    DaemonLocalMcpConfig config;
    try {
      config = parser.parseDiscover(request.call().argumentsJson());
    } catch (RuntimeException error) {
      execution.complete(EnvironmentCapabilityResult.error(callId, INVALID_REQUEST_MESSAGE));
      return;
    }

    McpDeadline deadline = McpDeadline.of(composeBudget(request, config));
    McpCancellationToken token = execution.token();

    DaemonLocalMcpManager.ManagedCall call = null;
    boolean failed = false;
    try {
      call = manager.acquire(config);
      if (token.isCancelled()) {
        return;
      }
      McpClient client = call.getClient(deadline);
      List<McpToolDefinition> tools = client.listTools(deadline, token);
      String envelope = encodeEnvelope(config, tools);
      execution.complete(EnvironmentCapabilityResult.json(callId, envelope));
    } catch (McpCancelledException error) {
      // 取消已由 Execution 给出终态；共享 client 仍然健康，绝不销毁。
    } catch (InvalidToolListException error) {
      // 服务端返回重名工具属于协议数据问题，client 仍然健康，因此不销毁实例。
      execution.complete(EnvironmentCapabilityResult.error(callId, INVALID_TOOL_LIST_MESSAGE));
    } catch (McpTimeoutException error) {
      execution.complete(EnvironmentCapabilityResult.error(callId, EXECUTION_TIMEOUT_MESSAGE));
    } catch (RuntimeException error) {
      failed = true;
      if (!execution.isCancelled()) {
        execution.complete(EnvironmentCapabilityResult.error(callId, EXECUTION_FAILED_MESSAGE));
      }
    } finally {
      if (call != null) {
        call.release(failed);
      }
    }
  }

  /** 编码 {@code {serverId,configVersion,tools[]}} envelope；工具名必须唯一（record 已保证非空）。 */
  private static String encodeEnvelope(DaemonLocalMcpConfig config, List<McpToolDefinition> tools) {
    ObjectNode envelope = OBJECT_MAPPER.createObjectNode();
    envelope.put("serverId", config.serverId());
    envelope.put("configVersion", config.configVersion());
    ArrayNode array = envelope.putArray("tools");
    Set<String> names = new HashSet<>();
    for (McpToolDefinition tool : tools) {
      if (!names.add(tool.name())) {
        throw new InvalidToolListException();
      }
      ObjectNode toolNode = array.addObject();
      toolNode.put("name", tool.name());
      toolNode.put("description", tool.description() == null ? "" : tool.description());
      toolNode.set("inputSchema", JsonValues.readTree(tool.inputSchemaJson()));
    }
    return envelope.toString();
  }

  /** 取请求预算与配置超时的较小值；语义与 call 能力保持一致。 */
  private static Duration composeBudget(
      EnvironmentCapabilityExecutionRequest request, DaemonLocalMcpConfig config) {
    Duration configBudget = Duration.ofMillis(config.timeoutMillis());
    Duration requestBudget = request.effectiveTimeout();
    if (requestBudget.isZero() || requestBudget.isNegative()) {
      return configBudget;
    }
    return requestBudget.compareTo(configBudget) > 0 ? configBudget : requestBudget;
  }

  /** 服务端工具列表不满足契约（空名或重名）时抛出，属于结果数据问题而非连接失败。 */
  private static final class InvalidToolListException extends RuntimeException {}

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
