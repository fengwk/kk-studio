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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/** Common asynchronous execution and strict JSON access for filesystem coding tools. */
abstract class AbstractCodingTool implements Tool {

  static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final ExecutorService EXECUTOR =
      Executors.newCachedThreadPool(
          new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
              Thread thread = new Thread(runnable, "daemon-coding-tool");
              thread.setDaemon(true);
              return thread;
            }
          });

  final CodingToolsConfig config;
  final WorkspacePathBoundary boundary;
  private final ToolDescriptor descriptor;

  AbstractCodingTool(CodingToolsConfig config, ToolDescriptor descriptor) {
    this.config = Objects.requireNonNull(config, "config");
    this.boundary = new WorkspacePathBoundary(config);
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
    execution.future =
        EXECUTOR.submit(
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

  static boolean optionalBoolean(JsonNode args, String name) {
    JsonNode value = args.get(name);
    return value != null && value.booleanValue();
  }

  static ToolResult success(String id, String text) {
    return new ToolResult(id, List.of(new TextToolContent(text)), false, "{}", false);
  }

  static ToolResult error(String id, String message) {
    return new ToolResult(id, List.of(new TextToolContent("Error: " + message)), true, "{}", false);
  }

  /** Mutable execution state whose terminal callback is guaranteed to run exactly once. */
  static final class Execution implements ToolExecutionHandle {
    private final String callId;
    private final ToolExecutionListener listener;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean terminal = new AtomicBoolean();
    private volatile Future<?> future;

    private Execution(String callId, ToolExecutionListener listener) {
      this.callId = callId;
      this.listener = listener;
    }

    @Override
    public void cancel() {
      if (cancelled.compareAndSet(false, true)) {
        Future<?> current = future;
        if (current != null) {
          current.cancel(true);
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
