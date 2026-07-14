package fun.fengwk.kkstudio.harness.runtime.tool;

import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 稳定 priority 顺序的可信 Tool interceptor chain。 */
public final class ToolInterceptorChain {
  private final List<BeforeToolCallInterceptor> beforeInterceptors;
  private final List<AfterToolCallInterceptor> afterInterceptors;

  public ToolInterceptorChain(
      List<BeforeToolCallInterceptor> beforeInterceptors,
      List<AfterToolCallInterceptor> afterInterceptors) {
    this.beforeInterceptors = stableBefore(beforeInterceptors);
    this.afterInterceptors = stableAfter(afterInterceptors);
  }

  public BeforeToolCallContext before(ToolBinding originalBinding, ToolCall originalCall) {
    originalCall.validateFor(originalBinding.descriptor());
    BeforeToolCallContext current = new BeforeToolCallContext(originalBinding, originalCall);
    for (BeforeToolCallInterceptor interceptor : beforeInterceptors) {
      BeforeToolCallResult result;
      try {
        result = Objects.requireNonNull(interceptor.intercept(current), "interceptor result");
      } catch (RuntimeException error) {
        throw new ToolInterceptorException("beforeToolCall", interceptor, error);
      }
      if (!result.binding().descriptor().name().equals(originalBinding.descriptor().name())) {
        throw new ToolInterceptorException(
            "beforeToolCall",
            interceptor,
            new IllegalArgumentException("interceptor must not rename a tool"));
      }
      ToolCall transformed;
      try {
        transformed =
            new ToolCall(
                originalCall.id(), result.binding().descriptor().name(), result.argumentsJson());
        transformed.validateFor(result.binding().descriptor());
      } catch (RuntimeException error) {
        throw new ToolInterceptorException("beforeToolCall schema validation", interceptor, error);
      }
      current = new BeforeToolCallContext(result.binding(), transformed);
    }
    return current;
  }

  public ToolResult after(AfterToolCallContext original) {
    AfterToolCallContext current = original;
    for (AfterToolCallInterceptor interceptor : afterInterceptors) {
      ToolResult result;
      try {
        result = Objects.requireNonNull(interceptor.intercept(current), "interceptor result");
      } catch (RuntimeException error) {
        throw new ToolInterceptorException("afterToolCall", interceptor, error);
      }
      if (!result.toolCallId().equals(original.call().id())) {
        throw new ToolInterceptorException(
            "afterToolCall",
            interceptor,
            new IllegalArgumentException("interceptor must not change toolCallId"));
      }
      current =
          new AfterToolCallContext(
              original.invocationId(), original.binding(), original.call(), result);
    }
    return current.result();
  }

  private static List<BeforeToolCallInterceptor> stableBefore(
      List<BeforeToolCallInterceptor> interceptors) {
    List<BeforeToolCallInterceptor> copy =
        new ArrayList<>(Objects.requireNonNull(interceptors, "beforeInterceptors"));
    copy.sort(Comparator.comparingInt(BeforeToolCallInterceptor::priority));
    return List.copyOf(copy);
  }

  private static List<AfterToolCallInterceptor> stableAfter(
      List<AfterToolCallInterceptor> interceptors) {
    List<AfterToolCallInterceptor> copy =
        new ArrayList<>(Objects.requireNonNull(interceptors, "afterInterceptors"));
    copy.sort(Comparator.comparingInt(AfterToolCallInterceptor::priority));
    return List.copyOf(copy);
  }
}
