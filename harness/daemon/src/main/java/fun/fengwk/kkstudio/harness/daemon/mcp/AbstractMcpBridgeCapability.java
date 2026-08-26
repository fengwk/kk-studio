package fun.fengwk.kkstudio.harness.daemon.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.tool.capability.EnvironmentCapabilityResult;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** MCP 固定 bridge capability 的通用异步执行：取消/中断不会产生重复终态回调。 */
abstract class AbstractMcpBridgeCapability implements EnvironmentCapability {

  static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  final McpServerRegistry registry;
  private final ExecutorService executor;
  private final EnvironmentCapabilityDescriptor descriptor;

  AbstractMcpBridgeCapability(
      McpServerRegistry registry,
      ExecutorService executor,
      EnvironmentCapabilityDescriptor descriptor) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.executor = Objects.requireNonNull(executor, "executor");
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
  }

  @Override
  public final EnvironmentCapabilityDescriptor descriptor() {
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
    execution.worker =
        executor.submit(
            () -> {
              try {
                EnvironmentCapabilityResult result = run(request, execution);
                execution.complete(result);
              } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                execution.complete(error(request.call().id(), "Operation cancelled"));
              } catch (Exception error) {
                execution.complete(error(request.call().id(), failureMessage(error)));
              }
            });
    return execution;
  }

  abstract EnvironmentCapabilityResult run(
      EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception;

  protected String failureMessage(Exception error) {
    return error.getMessage();
  }

  static JsonNode arguments(EnvironmentCapabilityExecutionRequest request) throws Exception {
    return OBJECT_MAPPER.readTree(request.call().argumentsJson());
  }

  static String string(JsonNode args, String name) {
    JsonNode value = args.get(name);
    if (value == null || !value.isTextual()) {
      throw new IllegalArgumentException(name + " is required and must be a string");
    }
    return value.textValue();
  }

  static String optionalString(JsonNode args, String name) {
    JsonNode value = args.get(name);
    return value == null ? null : value.textValue();
  }

  static EnvironmentCapabilityResult error(String id, String message) {
    String detail = message == null || message.isBlank() ? "capability execution failed" : message;
    return new EnvironmentCapabilityResult(
        id, List.of(new TextToolContent("Error: " + detail)), true, "{}");
  }

  /** 可变执行状态，其终态回调保证恰好执行一次。 */
  static final class Execution implements EnvironmentCapabilityExecutionHandle {
    private final String callId;
    private final EnvironmentCapabilityExecutionListener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private volatile Future<?> worker;

    private Execution(String callId, EnvironmentCapabilityExecutionListener listener) {
      this.callId = callId;
      this.listener = listener;
    }

    @Override
    public void cancel() {
      if (cancelled.compareAndSet(false, true)) {
        Future<?> current = worker;
        if (current != null) {
          current.cancel(true);
        }
        complete(error(callId, "Operation cancelled"));
      }
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    void complete(EnvironmentCapabilityResult result) {
      if (terminal.compareAndSet(false, true)) {
        listener.onComplete(result);
      }
    }
  }
}
