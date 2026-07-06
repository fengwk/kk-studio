package fun.fengwk.kkstudio.agent.tool.execution;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.agent.tool.ToolExecutionHandle;

import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ManagedToolExecutionHandle 统一管理 worker future 与工具下游取消句柄。
 *
 * @author fengwk
 */
@Slf4j
public class ManagedToolExecutionHandle implements ToolExecutionHandle {

  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final AtomicReference<Future<?>> futureRef = new AtomicReference<>();
  private final AtomicReference<ToolExecutionHandle> delegateRef = new AtomicReference<>();
  private final AtomicReference<Runnable> cancelHookRef = new AtomicReference<>();

  void setCancelHook(Runnable cancelHook) {
    cancelHookRef.set(cancelHook);
  }

  void bindFuture(Future<?> future) {
    if (future == null) {
      return;
    }
    if (!futureRef.compareAndSet(null, future)) {
      future.cancel(true);
      throw new IllegalStateException("tool execution future already bound");
    }
    if (cancelled.get()) {
      future.cancel(true);
    }
  }

  void bindDelegate(ToolExecutionHandle delegate) {
    if (delegate == null) {
      throw new IllegalArgumentException("delegate must not be null");
    }
    if (!delegateRef.compareAndSet(null, delegate)) {
      safeCancel(delegate);
      throw new IllegalStateException("tool execution delegate already bound");
    }
    if (cancelled.get()) {
      safeCancel(delegate);
    }
  }

  @Override
  public void cancel() {
    if (!cancelled.compareAndSet(false, true)) {
      return;
    }
    Future<?> future = futureRef.get();
    if (future != null) {
      future.cancel(true);
    }
    ToolExecutionHandle delegate = delegateRef.get();
    if (delegate != null) {
      safeCancel(delegate);
    }
    Runnable cancelHook = cancelHookRef.get();
    if (cancelHook != null) {
      try {
        cancelHook.run();
      } catch (Throwable error) {
        log.warn("[tool-execution] cancel hook failed", error);
      }
    }
  }

  @Override
  public boolean isCancelled() {
    return cancelled.get();
  }

  private void safeCancel(ToolExecutionHandle handle) {
    try {
      handle.cancel();
    } catch (Throwable error) {
      log.warn("[tool-execution] tool cancel failed", error);
    }
  }
}
