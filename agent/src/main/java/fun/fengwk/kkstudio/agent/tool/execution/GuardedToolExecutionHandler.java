package fun.fengwk.kkstudio.agent.tool.execution;

import lombok.extern.slf4j.Slf4j;

import fun.fengwk.kkstudio.agent.session.payload.IndexedToolContentDelta;
import fun.fengwk.kkstudio.agent.session.payload.ToolContent;
import fun.fengwk.kkstudio.agent.tool.ToolCallRequest;
import fun.fengwk.kkstudio.agent.tool.ToolExecutionHandler;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * GuardedToolExecutionHandler 保护 Agent 不受非法或迟到工具回调影响。
 *
 * @author fengwk
 */
@Slf4j
class GuardedToolExecutionHandler implements ToolExecutionHandler {

  private final ToolCallRequest request;
  private final ToolExecutionListener listener;
  private final ManagedToolExecutionHandle handle;
  private final Runnable onTerminal;
  private final ToolExecutionContentGuard contentGuard;
  private final AtomicBoolean terminal = new AtomicBoolean();

  GuardedToolExecutionHandler(
      ToolCallRequest request,
      ToolExecutionListener listener,
      ManagedToolExecutionHandle handle,
      Runnable onTerminal) {
    this.request = requireNonNull(request, "request");
    this.listener = requireNonNull(listener, "listener");
    this.handle = requireNonNull(handle, "handle");
    this.onTerminal = requireNonNull(onTerminal, "onTerminal");
    this.contentGuard = new ToolExecutionContentGuard(this::warn);
  }

  @Override
  public void onPartial(List<IndexedToolContentDelta> partial) {
    if (terminal.get()) {
      warn("partial_after_terminal");
      return;
    }
    if (partial == null || partial.isEmpty()) {
      warn("empty_partial");
      return;
    }
    List<IndexedToolContentDelta> sanitized;
    try {
      sanitized = contentGuard.sanitizePartial(partial);
    } catch (IllegalArgumentException error) {
      fail(error, true);
      return;
    }
    if (sanitized.isEmpty()) {
      warn("empty_sanitized_partial");
      return;
    }
    listener.onPartial(sanitized);
  }

  @Override
  public void onComplete(List<ToolContent> result) {
    if (!terminal.compareAndSet(false, true)) {
      warn("complete_after_terminal");
      return;
    }
    List<ToolContent> safeResult;
    try {
      safeResult = contentGuard.validateCompleteResult(result);
    } catch (IllegalArgumentException error) {
      terminal.set(false);
      fail(error, true);
      return;
    }
    onTerminal.run();
    listener.onComplete(safeResult);
  }

  @Override
  public void onError(Throwable error) {
    fail(error, false);
  }

  void onTimeout() {
    fail(new ToolTimeoutException(request.getToolCallId(), request.getToolName(), 0L), true);
  }

  void onTimeout(long timeoutSeconds) {
    fail(
        new ToolTimeoutException(request.getToolCallId(), request.getToolName(), timeoutSeconds),
        true);
  }

  void onRuntimeCancel() {
    if (!terminal.compareAndSet(false, true)) {
      return;
    }
    onTerminal.run();
  }

  private void fail(Throwable error, boolean cancelExecution) {
    if (!terminal.compareAndSet(false, true)) {
      warn("error_after_terminal");
      return;
    }
    onTerminal.run();
    listener.onError(error == null ? new IllegalStateException("unknown tool error") : error);
    if (cancelExecution) {
      handle.cancel();
    }
  }

  private void warn(String reason) {
    log.warn(
        "[tool-execution] ignore tool callback: toolCallId={} toolName={} reason={}",
        request.getToolCallId(),
        request.getToolName(),
        reason);
  }

  private static <T> T requireNonNull(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " must not be null");
    }
    return value;
  }
}
