package fun.fengwk.kkstudio.harness.daemon.coding;

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

/** 文件系统 coding capability 的通用异步执行与严格 JSON 访问。 */
abstract class AbstractCodingCapability implements EnvironmentCapability {

  static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  final CodingToolsConfig config;
  final EnvironmentPathBoundary boundary;
  private final ExecutorService executor;
  private final EnvironmentCapabilityDescriptor descriptor;

  AbstractCodingCapability(
      CodingToolsConfig config,
      ExecutorService executor,
      EnvironmentCapabilityDescriptor descriptor) {
    this.config = Objects.requireNonNull(config, "config");
    this.boundary = new EnvironmentPathBoundary(config);
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
                execution.complete(error(request.call().id(), error.getMessage()));
              }
            });
    return execution;
  }

  abstract EnvironmentCapabilityResult run(
      EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception;

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

  static int optionalPositiveInt(JsonNode args, String name, int defaultValue, int maximum) {
    JsonNode value = args.get(name);
    if (value == null) {
      return defaultValue;
    }
    if (!value.isInt() || value.intValue() < 1 || value.intValue() > maximum) {
      throw new IllegalArgumentException(name + " must be a positive integer <= " + maximum);
    }
    return value.intValue();
  }

  static int requiredPositiveInt(JsonNode args, String name) {
    JsonNode value = args.get(name);
    if (value == null
        || !value.isIntegralNumber()
        || value.longValue() < 1
        || value.longValue() > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(name + " is required and must be a positive integer");
    }
    return value.intValue();
  }

  static int optionalNonNegativeInt(JsonNode args, String name, int defaultValue) {
    JsonNode value = args.get(name);
    if (value == null) {
      return defaultValue;
    }
    if (!value.isIntegralNumber()
        || value.longValue() < 0
        || value.longValue() > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(name + " must be a non-negative integer");
    }
    return value.intValue();
  }

  static boolean optionalBoolean(JsonNode args, String name) {
    JsonNode value = args.get(name);
    return value != null && value.booleanValue();
  }

  static EnvironmentCapabilityResult success(String id, String text) {
    return new EnvironmentCapabilityResult(id, List.of(new TextToolContent(text)), false, "{}");
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
