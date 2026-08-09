package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.tool.TextToolContent;
import fun.fengwk.kkstudio.harness.tool.ToolDescriptor;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import fun.fengwk.kkstudio.harness.tool.execution.Tool;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionHandle;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionListener;
import fun.fengwk.kkstudio.harness.tool.execution.ToolExecutionRequest;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** 文件系统 coding tools 的通用异步执行与严格 JSON 访问。 */
abstract class AbstractCodingTool implements Tool {

  static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  final CodingToolsConfig config;
  final EnvironmentPathBoundary boundary;
  private final ToolDescriptor descriptor;

  AbstractCodingTool(CodingToolsConfig config, ToolDescriptor descriptor) {
    this.config = Objects.requireNonNull(config, "config");
    this.boundary = new EnvironmentPathBoundary(config);
    this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
  }

  @Override
  public final ToolDescriptor descriptor() {
    return descriptor;
  }

  @Override
  public ToolExecutionHandle execute(ToolExecutionRequest request, ToolExecutionListener listener) {
    if (!descriptor.equals(request.descriptor())) {
      throw new IllegalArgumentException("request descriptor does not match tool descriptor");
    }
    Objects.requireNonNull(listener, "listener");
    Execution execution = new Execution(request.call().id(), listener);
    execution.worker =
        Thread.ofVirtual()
            .name("daemon-coding-tool")
            .start(
                () -> {
                  try {
                    ToolResult result = run(request, execution);
                    execution.complete(listener, result);
                  } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    execution.complete(listener, error(request.call().id(), "Operation cancelled"));
                  } catch (Exception error) {
                    execution.complete(listener, error(request.call().id(), error.getMessage()));
                  }
                });
    return execution;
  }

  abstract ToolResult run(ToolExecutionRequest request, Execution execution) throws Exception;

  static JsonNode arguments(ToolExecutionRequest request) throws Exception {
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

  static ToolResult success(String id, String text) {
    return new ToolResult(id, List.of(new TextToolContent(text)), false, "{}", false);
  }

  static ToolResult error(String id, String message) {
    String detail = message == null || message.isBlank() ? "tool execution failed" : message;
    return new ToolResult(id, List.of(new TextToolContent("Error: " + detail)), true, "{}", false);
  }

  /** 可变执行状态，其终态回调保证恰好执行一次。 */
  static final class Execution implements ToolExecutionHandle {
    private final String callId;
    private final ToolExecutionListener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private volatile Thread worker;

    private Execution(String callId, ToolExecutionListener listener) {
      this.callId = callId;
      this.listener = listener;
    }

    @Override
    public void cancel() {
      if (cancelled.compareAndSet(false, true)) {
        Thread current = worker;
        if (current != null) {
          current.interrupt();
        }
        complete(listener, error(callId, "Operation cancelled"));
      }
    }

    @Override
    public boolean isCancelled() {
      return cancelled.get();
    }

    void complete(ToolExecutionListener listener, ToolResult result) {
      if (terminal.compareAndSet(false, true)) {
        listener.onComplete(result);
      }
    }

    void partial(ToolExecutionListener listener, ToolResult result) {
      if (!terminal.get() && !cancelled.get()) {
        listener.onPartial(result);
      }
    }
  }
}
