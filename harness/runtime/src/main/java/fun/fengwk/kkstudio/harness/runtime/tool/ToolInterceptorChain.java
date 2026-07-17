package fun.fengwk.kkstudio.harness.runtime.tool;

import fun.fengwk.kkstudio.harness.runtime.permission.ToolSettings;
import fun.fengwk.kkstudio.harness.tool.ToolCall;
import fun.fengwk.kkstudio.harness.tool.ToolResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 按 Extension Host 输入顺序执行，并把唯一 permission boundary 固定在末端。 */
public final class ToolInterceptorChain {
  private final List<BeforeToolCallInterceptor> beforeInterceptors;
  private final List<AfterToolCallInterceptor> afterInterceptors;
  private final boolean permissionBoundaryPresent;

  public ToolInterceptorChain(
      List<BeforeToolCallInterceptor> beforeInterceptors,
      List<AfterToolCallInterceptor> afterInterceptors) {
    this.beforeInterceptors = freezeBefore(beforeInterceptors);
    this.afterInterceptors =
        List.copyOf(Objects.requireNonNull(afterInterceptors, "afterInterceptors"));
    permissionBoundaryPresent =
        this.beforeInterceptors.stream().anyMatch(PermissionBoundaryInterceptor.class::isInstance);
  }

  public boolean hasPermissionBoundary() {
    return permissionBoundaryPresent;
  }

  public BeforeToolCallResult before(
      ToolBinding originalBinding,
      ToolCall originalCall,
      ToolSettings settings,
      boolean yoloEnabled,
      Path workdir,
      Path environmentRoot) {
    originalCall.validateFor(originalBinding.descriptor());
    BeforeToolCallContext current =
        new BeforeToolCallContext(
            originalBinding, originalCall, settings, yoloEnabled, workdir, environmentRoot);
    BeforeToolCallResult currentResult =
        new BeforeToolCallResult(originalBinding, originalCall.argumentsJson());
    for (BeforeToolCallInterceptor interceptor : beforeInterceptors) {
      BeforeToolCallResult result;
      try {
        result = Objects.requireNonNull(interceptor.intercept(current), "interceptor result");
      } catch (RuntimeException error) {
        throw new ToolInterceptorException("beforeToolCall", interceptor, error);
      }
      boolean permissionBoundary = interceptor instanceof PermissionBoundaryInterceptor;
      if (!permissionBoundary && result.permissionAction() != null) {
        throw new ToolInterceptorException(
            "beforeToolCall",
            interceptor,
            new IllegalArgumentException("ordinary interceptor must not produce permission"));
      }
      if (permissionBoundary && result.permissionAction() == null) {
        throw new ToolInterceptorException(
            "beforeToolCall",
            interceptor,
            new IllegalArgumentException("permission boundary must produce permission"));
      }
      if (!result.binding().descriptor().name().equals(originalBinding.descriptor().name())) {
        throw new ToolInterceptorException(
            "beforeToolCall",
            interceptor,
            new IllegalArgumentException("interceptor must not rename a tool"));
      }
      if (permissionBoundary
          && (!result.binding().equals(current.binding())
              || !result.argumentsJson().equals(current.call().argumentsJson()))) {
        throw new ToolInterceptorException(
            "beforeToolCall",
            interceptor,
            new IllegalArgumentException(
                "permission boundary must not change the final binding or arguments"));
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
      currentResult = result;
      current =
          new BeforeToolCallContext(
              result.binding(),
              transformed,
              current.settings(),
              current.yoloEnabled(),
              current.workdir(),
              current.environmentRoot());
    }
    return currentResult;
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

  private static List<BeforeToolCallInterceptor> freezeBefore(
      List<BeforeToolCallInterceptor> interceptors) {
    Objects.requireNonNull(interceptors, "beforeInterceptors");
    List<BeforeToolCallInterceptor> ordinary = new ArrayList<>(interceptors.size());
    PermissionBoundaryInterceptor boundary = null;
    for (BeforeToolCallInterceptor interceptor : interceptors) {
      Objects.requireNonNull(interceptor, "beforeInterceptor");
      if (interceptor instanceof PermissionBoundaryInterceptor nextBoundary) {
        if (boundary != null) {
          throw new IllegalArgumentException(
              "at most one PermissionBoundaryInterceptor may be registered");
        }
        boundary = nextBoundary;
      } else {
        ordinary.add(interceptor);
      }
    }
    if (boundary != null) {
      ordinary.add(boundary);
    }
    return List.copyOf(ordinary);
  }
}
