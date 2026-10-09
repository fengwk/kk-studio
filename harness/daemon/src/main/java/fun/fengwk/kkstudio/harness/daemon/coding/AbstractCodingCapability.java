package fun.fengwk.kkstudio.harness.daemon.coding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fun.fengwk.kkstudio.harness.common.tool.ToolErrorGuidance;
import fun.fengwk.kkstudio.harness.common.tool.ToolErrorGuidance.ExecutionFact;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapability;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityDescriptor;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionHandle;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionListener;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityExecutionRequest;
import fun.fengwk.kkstudio.harness.environment.capability.EnvironmentCapabilityResult;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** 文件系统 coding capability 的通用异步执行与严格 JSON 访问。 */
abstract class AbstractCodingCapability implements EnvironmentCapability {

  /** 受控诊断的失败产生点所在包：本模块自身，以及 read 窗口核心（binary/编码/越界诊断的固定文案）。 */
  private static final String[] CONTROLLED_DIAGNOSTIC_PACKAGES = {
    "fun.fengwk.kkstudio.harness.daemon.coding.", "fun.fengwk.kkstudio.harness.common.text."
  };

  private static final String VERIFY_EFFECT_NEXT_ACTION =
      "Verify whether the intended effect already took place before deciding what to do next";

  static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  final CodingToolsConfig config;
  private final ExecutorService executor;
  private final EnvironmentCapabilityDescriptor descriptor;

  AbstractCodingCapability(
      CodingToolsConfig config,
      ExecutorService executor,
      EnvironmentCapabilityDescriptor descriptor) {
    this.config = Objects.requireNonNull(config, "config");
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
                execution.complete(finalizeResult(result));
              } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                execution.complete(error(request.call().id(), cancelledMessage()));
              } catch (Exception error) {
                execution.complete(error(request.call().id(), failureMessage(error)));
              }
            });
    return execution;
  }

  /** 取消可能发生在副作用之后：只声明结果未确认，绝不声称未执行。 */
  private static String cancelledMessage() {
    return ToolErrorGuidance.message(
        "Operation cancelled",
        ExecutionFact.UNCERTAIN,
        "Check whether the operation already took effect before deciding what to do next");
  }

  /**
   * capability 未被自身捕获的失败，按真实 stage 与失败产生点分类：
   *
   * <ul>
   *   <li>{@link ToolInputRejectedException} 是本模块已确认的派发前输入拒绝，可以声明未执行；
   *   <li>由本模块失败产生点显式生成的受控诊断（binary/编码、搜索限界、LSP 未配置或超时等）保留其固定安全文案，但因为可能发生在副作用之后，只声明结果不可确认；
   *   <li>其余异常（Jackson、IO、JDK 子类等）的 message 可能内联参数片段或凭据，一律用固定安全文案，绝不回显。
   * </ul>
   */
  private static String failureMessage(Exception error) {
    if (error instanceof ToolInputRejectedException) {
      return ToolErrorGuidance.message(
          error.getMessage(),
          ExecutionFact.NOT_EXECUTED,
          "Correct the arguments to match the tool schema, then call the tool again");
    }
    if (isControlledDiagnostic(error)) {
      return ToolErrorGuidance.message(
          nonBlank(error), ExecutionFact.UNCERTAIN, VERIFY_EFFECT_NEXT_ACTION);
    }
    return ToolErrorGuidance.message(
        "The capability failed while running and the underlying error is not repeated to avoid"
            + " leaking details",
        ExecutionFact.UNCERTAIN,
        VERIFY_EFFECT_NEXT_ACTION);
  }

  /**
   * 失败是否由本模块的失败产生点显式生成：这类异常的顶层栈帧落在本包，文案是本模块自己的固定说明（可含调用方给出的绝对路径或 binary 名），不携带凭据、原始 arguments 或异常栈。
   * 其他异常（Jackson、IO、JDK）的顶层栈帧不在本包，message 可能内联参数凭据，因此不回显。
   */
  private static boolean isControlledDiagnostic(Throwable error) {
    StackTraceElement[] frames = error.getStackTrace();
    if (frames.length == 0) {
      return false;
    }
    String origin = frames[0].getClassName();
    for (String prefix : CONTROLLED_DIAGNOSTIC_PACKAGES) {
      if (origin.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private static String nonBlank(Throwable error) {
    String message = error.getMessage();
    return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
  }

  /**
   * 终态外化：把单个超过内联阈值的大文本结果落到本地 durable 全文，并返回有界 head/tail 预览与绝对路径；其它结果原样返回。
   *
   * <p>与 {@link #spoolsLargeTextOutput()} 配合：默认启用；{@code read} 的结果本身就是精确有界窗口，覆盖为不启用。
   */
  private EnvironmentCapabilityResult finalizeResult(EnvironmentCapabilityResult result) {
    if (!spoolsLargeTextOutput()) {
      return result;
    }
    return LargeTextResultSpooler.spool(config.textOutputStore(), result);
  }

  /** 是否把单个大文本终态外化为本地全文；默认启用。 */
  boolean spoolsLargeTextOutput() {
    return true;
  }

  abstract EnvironmentCapabilityResult run(
      EnvironmentCapabilityExecutionRequest request, Execution execution) throws Exception;

  static JsonNode arguments(EnvironmentCapabilityExecutionRequest request) throws Exception {
    try {
      return OBJECT_MAPPER.readTree(request.call().argumentsJson());
    } catch (JsonProcessingException error) {
      // Jackson 的解析异常 message 会内联原始 arguments 片段（可能含凭据），绝不能外发；这里只保留固定文案。
      throw new ToolInputRejectedException("tool arguments could not be parsed as JSON");
    }
  }

  static String string(JsonNode args, String name) {
    JsonNode value = args.get(name);
    if (value == null || !value.isTextual()) {
      throw new ToolInputRejectedException(name + " is required and must be a string");
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
      throw new ToolInputRejectedException(name + " must be a positive integer <= " + maximum);
    }
    return value.intValue();
  }

  static int requiredPositiveInt(JsonNode args, String name) {
    JsonNode value = args.get(name);
    if (value == null
        || !value.isIntegralNumber()
        || value.longValue() < 1
        || value.longValue() > Integer.MAX_VALUE) {
      throw new ToolInputRejectedException(name + " is required and must be a positive integer");
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
      throw new ToolInputRejectedException(name + " must be a non-negative integer");
    }
    return value.intValue();
  }

  static boolean optionalBoolean(JsonNode args, String name) {
    JsonNode value = args.get(name);
    return value != null && value.booleanValue();
  }

  static EnvironmentCapabilityResult success(String id, String text) {
    return EnvironmentCapabilityResult.success(id, text);
  }

  static EnvironmentCapabilityResult error(String id, String message) {
    return EnvironmentCapabilityResult.error(id, message);
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
        complete(error(callId, cancelledMessage()));
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
